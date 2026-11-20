import http from 'node:http';
import { lstat } from 'node:fs/promises';
import { dirname, isAbsolute, join } from 'node:path';
import { homedir } from 'node:os';
import { Bridge, inject as bridgeServices, serve } from './index.mjs';

export const name = 'rivo-dsh-bridge';
export const inject = [...bridgeServices, 'configEditor'];
const ROUTE = '/api/rivo-relay/config';

function socketSetting(value) {
  if (typeof value !== 'string') throw new Error('invalid_settings');
  const path = value.trim();
  if (path && (!isAbsolute(path) || path.includes('\0') || Buffer.byteLength(path) > 100)) {
    throw new Error('invalid_settings');
  }
  return path;
}

async function privateDirectory(path) {
  const directory = await lstat(dirname(path));
  return directory.isDirectory() && directory.uid === process.getuid() && !(directory.mode & 0o077);
}

async function callRelay(socketPath, action, data, signal) {
  if (!await privateDirectory(socketPath)) throw new Error('unsafe admin directory');
  const socket = await lstat(socketPath);
  if (!socket.isSocket() || socket.uid !== process.getuid() || socket.mode & 0o077) {
    throw new Error('unsafe admin socket');
  }
  const body = JSON.stringify(data);
  return new Promise((resolve, reject) => {
    const request = http.request({ socketPath, path: action, method: 'POST',
      signal: AbortSignal.any([signal, AbortSignal.timeout(2500)]),
      headers: { 'content-type': 'application/json', 'content-length': Buffer.byteLength(body) },
    }, async response => {
      try {
        if (![200, 409, 503].includes(response.statusCode)) throw new Error('admin request failed');
        const chunks = [];
        let size = 0;
        for await (const chunk of response) {
          size += chunk.length;
          if (size > 128 * 1024) throw new Error('admin response too large');
          chunks.push(chunk);
        }
        const value = JSON.parse(Buffer.concat(chunks));
        if (!['ready', 'wrong_bridge', 'bridge_disconnected', 'unsupported_route', 'updating'].includes(value?.state)) {
          throw new Error('invalid admin response');
        }
        resolve(value);
      } catch (error) {
        response.destroy();
        reject(error);
      }
    });
    request.on('error', reject);
    request.end(body);
  });
}

function reply(value, status = 200) {
  return Response.json(value, { status, headers: { 'cache-control': 'no-store' } });
}

export async function apply(ctx, config = {}) {
  const rivoDir = join(process.env.DSH_HOME ?? join(homedir(), '.dsh'), 'rivo');
  const defaultSocket = join(rivoDir, 'bridge.sock');
  const defaultAdminSocket = join(rivoDir, 'relay-admin.sock');
  const settings = { socketPath: config.socketPath || '', adminSocket: config.adminSocket || '' };
  const socketPath = settings.socketPath || defaultSocket;
  const adminSocket = settings.adminSocket || defaultAdminSocket;
  const bridge = new Bridge(ctx);
  let bridgeReady = false;
  try {
    const close = await serve(bridge, socketPath);
    ctx.effect(() => close, 'rivo-dsh-bridge.server');
    bridgeReady = true;
  } catch (error) {
    bridge.close();
    ctx.logger('rivo-dsh-bridge').warn('Unable to open bridge socket: %s', error.message);
  }

  const status = async (request, action, pairingId) => {
    const snapshot = { settings, socketPath, adminSocket, bridgeReady, state: 'bridge_unavailable' };
    if (!bridgeReady) return reply(snapshot, action === '/pair' ? 409 : 200);
    try {
      const result = await callRelay(adminSocket, action, { generation: bridge.generation, pairingId }, request.signal);
      return reply({ ...snapshot, ...result }, action === '/pair' && result.state !== 'ready' ? 409 : 200);
    } catch {
      return reply({ ...snapshot, state: 'relay_unavailable' }, action === '/pair' ? 503 : 200);
    }
  };

  ctx.connection.fetch.register({ path: ROUTE, methods: ['GET', 'POST'], requestBody: 'buffered', async fetch(request) {
    if (request.method === 'GET') {
      const pairingId = new URL(request.url).searchParams.get('pairingId');
      if (pairingId !== null && !/^[0-9a-f]{32}$/.test(pairingId)) return reply({ state: 'invalid_request' }, 400);
      return status(request, '/status', pairingId ?? undefined);
    }
    // A cross-origin form cannot send this header. The DSH owner carrier also checks access.
    if (request.headers.get('x-rivo-relay') !== '1' || request.headers.get('content-type') !== 'application/json') {
      return reply({ state: 'invalid_request' }, 400);
    }
    let body;
    try {
      const raw = await request.text();
      if (raw.length > 2048) throw new Error();
      body = JSON.parse(raw);
    } catch {
      return reply({ state: 'invalid_request' }, 400);
    }
    if (body?.action === 'pair') return status(request, '/pair');
    if (body?.action !== 'save') return reply({ state: 'invalid_request' }, 400);
    let next;
    try {
      next = { socketPath: socketSetting(body.socketPath), adminSocket: socketSetting(body.adminSocket) };
      const nextSocket = next.socketPath || defaultSocket;
      if (nextSocket === (next.adminSocket || defaultAdminSocket)) throw new Error('invalid_settings');
      if (nextSocket !== socketPath) {
        if (await lstat(nextSocket).catch(error => {
          if (error.code !== 'ENOENT') throw error;
        })) throw new Error('invalid_settings');
        const safe = await privateDirectory(nextSocket).catch(error => {
          if (error.code !== 'ENOENT') throw error;
          return true;
        });
        if (!safe) throw new Error('invalid_settings');
      }
    } catch {
      return reply({ state: 'invalid_settings' }, 400);
    }
    if (next.socketPath === settings.socketPath && next.adminSocket === settings.adminSocket) return reply({ saved: true });
    try {
      await ctx.configEditor.edit(ctx.fiber.entry, current => {
        if ((current.socketPath || '') !== settings.socketPath || (current.adminSocket || '') !== settings.adminSocket) {
          throw new Error('configuration changed');
        }
        return { ...current, ...next };
      });
      return reply({ saved: true });
    } catch {
      return reply({ state: 'save_failed' }, 409);
    }
  } });
}
