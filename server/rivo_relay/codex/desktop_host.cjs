/* QiGuang protocol-1 adapter. Load inside the Desktop main process and pass its
 * existing local AppServerConnection. This module never starts a native daemon. */
'use strict';
const fs = require('node:fs');
const net = require('node:net');
const path = require('node:path');
const crypto = require('node:crypto');
const MAX_FRAME = 16 * 1024 * 1024;
const HUMAN_REQUESTS = [
  'item/commandExecution/requestApproval', 'item/fileChange/requestApproval',
  'item/tool/requestUserInput', 'item/permissions/requestApproval',
  'execCommandApproval', 'applyPatchApproval',
];

async function startDesktopBridge({ connection, directory, appVersion = null }) {
  for (const method of ['sendAppServerRequest', 'handleClientResponse',
    'getConnectionState', 'registerInternalNotificationHandler',
    'registerInternalConnectionStateHandler', 'registerInternalServerRequestHandler']) {
    if (typeof connection?.[method] !== 'function') throw new Error(`Unsupported Desktop host: ${method}`);
  }
  fs.mkdirSync(directory, { recursive: true, mode: 0o700 });
  const dir = fs.lstatSync(directory);
  if (!dir.isDirectory() || dir.isSymbolicLink() || (dir.mode & 0o077) || dir.uid !== process.getuid()) {
    throw new Error('Bridge directory must be owner-only');
  }
  const socketPath = path.join(directory, 'bridge.sock');
  const tokenPath = path.join(directory, 'token');
  // Never unlink another owner's running or stale endpoint implicitly.
  if (fs.existsSync(socketPath) || fs.existsSync(tokenPath)) throw new Error('Bridge endpoint already exists');
  const token = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(tokenPath, token, { mode: 0o600, flag: 'wx' });
  const peers = new Set();
  const pending = new Map();
  let closed = false;
  function send(peer, value) {
    if (peer.socket.destroyed) return;
    const frame = Buffer.from(JSON.stringify(value) + '\n');
    if (frame.length > MAX_FRAME || peer.socket.writableLength + frame.length > MAX_FRAME * 2) {
      peer.socket.destroy(); return;
    }
    peer.socket.write(frame);
  }
  function broadcast(message) {
    for (const peer of peers) if (peer.authenticated) send(peer, { method: 'app-server/message', params: { message } });
  }
  const disposers = [
    connection.registerInternalNotificationHandler(message => {
      if (['serverRequest/resolved', 'serverRequest/completed'].includes(message.method)) {
        pending.delete(JSON.stringify(message.params?.requestId));
      }
      broadcast(message);
    }),
    connection.registerInternalConnectionStateHandler(state => {
      if (!['connected', 'ready'].includes(state)) pending.clear();
      broadcast({ method: 'qiguang/desktopConnectionState', params: { state } });
    }),
    connection.registerInternalServerRequestHandler({ methods: HUMAN_REQUESTS, handler: message => {
      if (![...peers].some(peer => peer.authenticated)) return null;
      pending.set(JSON.stringify(message.id), message);
      broadcast(message);
      // Observe human requests without taking them away from Desktop's UI.
      // Desktop's dynamic tools and automatic handlers keep their own ownership.
      return null;
    } }),
  ];
  const server = net.createServer(socket => {
    const peer = { socket, authenticated: false, buffer: Buffer.alloc(0), inFlight: 0 };
    peers.add(peer);
    const handshake = setTimeout(() => { if (!peer.authenticated) socket.destroy(); }, 5000);
    socket.on('close', () => { clearTimeout(handshake); peers.delete(peer); });
    socket.on('error', () => {});
    socket.on('data', chunk => {
      peer.buffer = Buffer.concat([peer.buffer, chunk]);
      let boundary;
      while ((boundary = peer.buffer.indexOf(10)) >= 0) {
        const raw = peer.buffer.subarray(0, boundary);
        peer.buffer = peer.buffer.subarray(boundary + 1);
        if (raw.length > MAX_FRAME || peer.inFlight >= 64) { socket.destroy(); return; }
        let message;
        try { message = JSON.parse(raw); } catch { socket.destroy(); return; }
        if (!message || typeof message !== 'object' || !Number.isSafeInteger(message.id)) { socket.destroy(); return; }
        peer.inFlight++;
        handle(peer, message).finally(() => peer.inFlight--);
      }
      if (peer.buffer.length > MAX_FRAME) socket.destroy();
    });
  });
  async function handle(peer, message) {
    let sent = false;
    try {
      const params = message.params || {};
      let result;
      if (!peer.authenticated) {
        const supplied = Buffer.from(typeof params.token === 'string' ? params.token : '');
        if (message.method !== 'hello' || params.protocolVersion !== 1 ||
            supplied.length !== token.length || !crypto.timingSafeEqual(supplied, Buffer.from(token))) {
          throw new Error('Bridge authentication failed');
        }
        peer.authenticated = true;
        result = { protocolVersion: 1, connectionState: connection.getConnectionState(),
          appVersion, platform: process.platform, transportKind: connection.getTransportKind?.(),
          provenance: 'rivo-desktop-host-protocol-1', capabilities: {
            nativeRpc: true, sharedNotifications: true, humanResponses: true,
            desktopOwnedTools: true, nativeHostProbe: true,
          } };
      } else if (message.method === 'hello') {
        throw new Error('Bridge already authenticated');
      } else if (!['connected', 'ready'].includes(connection.getConnectionState())) {
        throw new Error('Desktop app-server is not connected');
      } else if (message.method === 'rpc') {
        if (typeof params.method !== 'string' || ['initialize', 'initialized'].includes(params.method)) {
          throw new Error('Desktop owns native initialization');
        }
        sent = true;
        result = await connection.sendAppServerRequest(params.method, params.params || {});
      } else if (message.method === 'server/respond') {
        const key = JSON.stringify(params.requestId);
        if (!pending.has(key)) throw new Error('Native request has expired');
        pending.delete(key);
        sent = true;
        await connection.handleClientResponse({ id: params.requestId, result: params.result });
        result = {};
      } else {
        throw new Error('Unknown bridge method');
      }
      send(peer, { id: message.id, result: result || {} });
    } catch (error) {
      send(peer, { id: message.id, error: { message: error.message || 'Desktop request failed',
        code: error.code, requestSent: typeof error.requestSent === 'boolean' ? error.requestSent : sent } });
      if (!peer.authenticated) peer.socket.end();
    }
  }
  try {
    await new Promise((resolve, reject) => { server.once('error', reject); server.listen(socketPath, resolve); });
    fs.chmodSync(socketPath, 0o600);
  } catch (error) {
    for (const dispose of disposers) dispose();
    fs.unlinkSync(tokenPath);
    throw error;
  }
  return { socketPath, close: async () => {
    if (closed) return;
    closed = true;
    for (const dispose of disposers) dispose();
    for (const peer of peers) peer.socket.destroy();
    pending.clear();
    await new Promise(resolve => server.close(resolve));
    if (fs.existsSync(socketPath)) fs.unlinkSync(socketPath);
    if (fs.existsSync(tokenPath) && fs.readFileSync(tokenPath, 'utf8') === token) fs.unlinkSync(tokenPath);
  } };
}
module.exports = { startDesktopBridge };
