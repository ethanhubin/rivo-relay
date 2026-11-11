/** Local adapter for the existing DSH Host; never launches or owns an Agent runtime. */
import http from 'node:http';
import { mkdir, chmod, lstat, unlink } from 'node:fs/promises';
import { dirname, join, isAbsolute } from 'node:path';
import { homedir } from 'node:os';
import { randomUUID } from 'node:crypto';

export const name = 'rivo-dsh-bridge';
export const inject = ['sessionController', 'typertGateway', 'connection', 'workspaceRegistry', 'agents'];
const ALLOWED_OUTCOMES = new Set(['allowed-once', 'rejected', 'cancelled']);
const MAX_BODY = 1024 * 1024;
const MAX_EVENTS = 512;
const MAX_EVENT_BYTES = 4 * 1024 * 1024;
function failure(code, message) { return Object.assign(new Error(message), { code }); }
function string(value, name) {
  if (typeof value !== 'string' || !value.trim() || value.length > 4096) throw failure('invalid_argument', `${name} must be a nonempty string`);
  return value;
}
function record(value) { return value && typeof value === 'object' && !Array.isArray(value); }
function integer(value, name, min = 0, max = Number.MAX_SAFE_INTEGER) {
  if (!Number.isSafeInteger(value) || value < min || value > max) throw failure('invalid_argument', `Invalid ${name}`);
  return value;
}
function acceptedEvent(event, requestId) {
  const matches = message => message?.source?.kind === 'user' && message.source.rpcId === requestId;
  return event.type === 'user/message' && matches(event.data)
    || event.type === 'agent/inbox/spliced' && Array.isArray(event.data?.inserted) && event.data.inserted.some(matches);
}

export class Bridge {
  constructor(ctx, { leaseMs = 30000, clock = Date.now } = {}) {
    this.ctx = ctx; this.clock = clock; this.leaseMs = leaseMs;
    this.generation = randomUUID(); this.cursor = 0; this.events = []; this.eventBytes = 0;
    this.pending = new Map(); this.watched = new Map(); this.waiters = new Set();
    this.lastPoll = -Infinity; this.closed = false;
    this.lifetime = new AbortController();
    this.peer = ctx.connection.createPeer({ principal: { kind: 'owner' }, interactions: 'none' });
    this.disposers = [
      ctx.on('session/event', (session, event) => {
        if (this.isWatched(session.id)) this.publish({ type: 'session', sessionId: session.id, event });
      }, { global: true }),
      ctx.on('agent/assistant-stream', ({ agent, frame }) => {
        if (this.isWatched(agent.session.id)) this.publish({ type: 'assistant-stream', sessionId: agent.session.id, frame });
      }, { global: true }),
      ctx.on('api-session/status', (sessionId, running) => this.publish({ type: 'status', sessionId, running }), { global: true }),
      ctx.on('api-session/added', () => this.publish({ type: 'catalog' }), { global: true }),
      ctx.on('api-session/removed', () => this.publish({ type: 'catalog' }), { global: true }),
      ctx.on('approval/request', (request, next) => this.approval(request, next), { global: true, prepend: true }),
    ];
    this.leaseTimer = setInterval(() => this.expireApprovals(), Math.min(1000, leaseMs));
    this.leaseTimer.unref();
  }
  isWatched(id) { return (this.watched.get(id) ?? 0) > this.clock(); }
  watch(id) {
    for (const [key, until] of this.watched) if (until <= this.clock()) this.watched.delete(key);
    if (this.watched.size >= 128 && !this.watched.has(id)) this.watched.delete(this.watched.keys().next().value);
    this.watched.set(id, this.clock() + 30 * 60 * 1000);
  }
  publish(value) {
    if (this.closed) return;
    const event = JSON.parse(JSON.stringify({ ...value, cursor: ++this.cursor }));
    const bytes = Buffer.byteLength(JSON.stringify(event));
    if (bytes > MAX_EVENT_BYTES) { this.events = []; this.eventBytes = 0; }
    else {
      this.events.push({ event, bytes }); this.eventBytes += bytes;
      while (this.events.length > MAX_EVENTS || this.eventBytes > MAX_EVENT_BYTES) this.eventBytes -= this.events.shift().bytes;
    }
    for (const wake of this.waiters) wake();
  }
  async poll(generation, cursor, signal) {
    if (this.waiters.size >= 8) throw failure('busy', 'Too many event readers');
    this.lastPoll = this.clock();
    const result = () => {
      const oldest = this.events[0]?.event.cursor ?? this.cursor + 1;
      const reset = generation !== this.generation || !Number.isSafeInteger(cursor) || cursor < oldest - 1 || cursor > this.cursor;
      return { generation: this.generation, cursor: this.cursor, reset,
        events: reset ? [] : this.events.filter(row => row.event.cursor > cursor).map(row => row.event) };
    };
    const first = result();
    if (first.reset || first.events.length || this.closed) return first;
    await new Promise(resolve => {
      const timer = setTimeout(done, 15000);
      const self = this;
      function done() { clearTimeout(timer); self.waiters.delete(done); signal?.removeEventListener('abort', done); resolve(); }
      this.waiters.add(done); signal?.addEventListener('abort', done, { once: true });
      if (signal?.aborted || this.closed) done();
    });
    this.lastPoll = this.clock();
    return result();
  }
  async native(method, request, signal = this.lifetime.signal) {
    return this.ctx.typertGateway.invoke({ namespace: 'session', method,
      args: method === 'modelCatalog' ? {} : { [method === 'list' ? '_request' : 'request']: request },
      peer: this.peer, signal });
  }
  async opening(request, signal) {
    const controller = new AbortController();
    const combined = AbortSignal.any([signal, controller.signal]);
    const iterator = (await this.ctx.typertGateway.stream({ namespace: 'session', method: 'follow',
      args: { request }, peer: this.peer, signal: combined }))[Symbol.asyncIterator]();
    try {
      const first = await iterator.next();
      if (first.done || first.value.type !== 'snapshot') throw failure('invalid_snapshot', 'DSH returned no opening snapshot');
      return first.value;
    } finally { controller.abort(); await iterator.return?.(); }
  }
  async rpc(method, params = {}, signal = this.lifetime.signal) {
    if (this.closed) throw failure('disconnected', 'Bridge is closed');
    if (!record(params)) throw failure('invalid_argument', 'params must be an object');
    if (method === 'status') return { connected: true, generation: this.generation, protocol: 1, supportedRuntimeVersion: '0.2.2-rc.1' };
    if (method === 'projects') {
      const registry = this.ctx.workspaceRegistry;
      return { items: registry.list().map(w => ({ workspaceId: w.id, path: w.path, title: w.title, sessionIds: [...w.sessionIds], createdAt: w.createdAt, updatedAt: w.updatedAt })),
        archivedSessionIds: [...registry.archivedSessionIds], pinnedSessionIds: [...registry.pinnedSessionIds] };
    }
    if (method === 'sessions') return this.native('list', {}, signal);
    if (method === 'models') return this.native('modelCatalog', {}, signal);
    if (method === 'permissionCatalog') {
      const service = this.ctx.get('permissionPresets');
      if (!service) throw failure('unsupported', 'DSH permission presets unavailable');
      return service.catalog();
    }
    if (method === 'respond') return this.respond(params);
    if (method === 'create') {
      const request = {};
      for (const key of ['workspaceId', 'cwd', 'sessionId', 'agentPreset']) if (params[key] !== undefined) request[key] = string(params[key], key);
      const result = await this.native('create', request, signal); this.watch(result.sessionId); return result;
    }
    const sessionId = string(params.sessionId, 'sessionId');
    this.watch(sessionId);
    if (method === 'permission') {
      const preset = string(params.preset, 'preset');
      const service = this.ctx.get('permissionPresets');
      if (!service) throw failure('unsupported', 'DSH permission presets unavailable');
      const resolved = await this.ctx.sessionController.resolveAgent(sessionId);
      if (resolved.error) throw failure(resolved.error.code ?? 'session_unavailable', resolved.error.message);
      const agent = resolved.agent;
      if (!agent?.session) throw failure('invalid_agent', 'DSH returned no session agent');
      service.set(agent.session, preset);
      return { currentValue: service.current(agent.session) };
    }
    if (method === 'prompt') {
      const requestId = string(params.requestId, 'requestId');
      const text = params.text;
      if (typeof text !== 'string' || !text.trim() || Buffer.byteLength(text) > MAX_BODY / 2) throw failure('invalid_argument', 'Invalid prompt text');
      const result = await this.native('prompt', { sessionId, requestId, mode: 'queue', content: [{ type: 'text', text }] }, signal);
      if (result?.accepted !== true) throw failure('invalid_receipt', 'DSH returned no acceptance');
      return { accepted: true, requestId };
    }
    if (method === 'cancel') {
      const expectedTurn = integer(params.expectedTurn, 'expectedTurn', 1);
      // Do not await gateway preparation between checking the boundary and cancelling.
      // The controller synchronously applies its own ownership fence and native cancel.
      signal.throwIfAborted();
      const agent = this.ctx.agents.get(sessionId);
      const boundary = agent && this.ctx.agents.turnBoundaryOf(agent.session);
      if (!boundary || boundary.openTurnStartSeq === null || boundary.lastTurn !== expectedTurn) {
        throw failure('turn_changed', 'DSH active turn no longer matches the cancellation request');
      }
      return this.ctx.sessionController.cancel({ sessionId });
    }
    if (method === 'rename') return this.native('rename', { sessionId, title: string(params.title, 'title') }, signal);
    if (method === 'snapshot') {
      const readCursor = this.cursor;
      const request = { address: { kind: 'session', sessionId }, maxMessages: params.maxMessages === undefined ? 12 : integer(params.maxMessages, 'maxMessages', 1, 100) };
      let page;
      if (params.beforeSeq !== undefined || params.throughSeq !== undefined) {
        for (const key of ['beforeSeq', 'throughSeq']) if (params[key] !== undefined) request[key] = integer(params[key], key);
        page = await this.native('events', request, signal);
      } else page = await this.opening({ ...request, assistantStream: true }, signal);
      const state = await this.native('state', { sessionId }, signal);
      return { generation: this.generation, cursor: readCursor, page, state,
        requests: [...this.pending.values()].filter(p => p.value.sessionId === sessionId).map(p => p.value) };
    }
    if (method === 'receipt') {
      const requestId = string(params.requestId, 'requestId');
      let beforeSeq, throughSeq;
      for (let count = 0; count < 100; count++) {
        const request = { address: { kind: 'session', sessionId }, maxMessages: 100,
          ...(beforeSeq === undefined ? {} : { beforeSeq }), ...(throughSeq === undefined ? {} : { throughSeq }) };
        const page = await this.native('events', request, signal);
        if (page.records.some(row => acceptedEvent(row.event, requestId))) return { accepted: true, requestId };
        if (!page.hasMore) return { accepted: false };
        throughSeq ??= page.records.at(-1)?.event.seq;
        const first = page.records[0]?.event.seq;
        if (first === undefined || first === beforeSeq) throw failure('invalid_history', 'DSH history cursor did not advance');
        beforeSeq = first;
      }
      throw failure('history_limit', 'Receipt is not proven within the bounded history scan');
    }
    throw failure('unsupported', `Unsupported bridge method: ${method}`);
  }
  approval(request, next) {
    const sessionId = request.agent.session.id;
    if (this.closed || !this.isWatched(sessionId) || this.clock() - this.lastPoll > this.leaseMs || this.pending.size >= 64) return next();
    const key = `${this.generation}:${randomUUID()}`;
    const value = { key, sessionId, toolName: request.toolName, ...(request.reason === undefined ? {} : { reason: request.reason }), ...(request.callId === undefined ? {} : { callId: request.callId }) };
    return new Promise((resolve, reject) => {
      const abort = () => settle('cancelled');
      const settle = outcome => {
        if (!this.pending.delete(key)) return false;
        request.signal?.removeEventListener('abort', abort);
        this.publish({ type: 'approval-resolved', sessionId, key });
        if (outcome === 'delegate') Promise.resolve().then(next).then(resolve, reject);
        else resolve(outcome);
        return true;
      };
      this.pending.set(key, { value, settle });
      request.signal?.addEventListener('abort', abort, { once: true });
      this.publish({ type: 'approval', sessionId, request: value });
      if (request.signal?.aborted) abort();
    });
  }
  respond(params) {
    const key = string(params.key, 'key');
    if (!ALLOWED_OUTCOMES.has(params.outcome)) throw failure('invalid_decision', 'Unsupported approval outcome');
    const pending = this.pending.get(key);
    if (!pending || !key.startsWith(this.generation + ':') || this.clock() - this.lastPoll > this.leaseMs) {
      pending?.settle('delegate'); throw failure('stale_approval', 'Approval is no longer owned by this connection');
    }
    pending.settle(params.outcome); return { accepted: true };
  }
  expireApprovals() {
    if (this.clock() - this.lastPoll > this.leaseMs) for (const pending of [...this.pending.values()]) pending.settle('delegate');
  }
  close() {
    if (this.closed) return;
    for (const pending of [...this.pending.values()]) pending.settle('delegate');
    this.closed = true; clearInterval(this.leaseTimer); this.lifetime.abort();
    for (const dispose of this.disposers) dispose?.();
    this.peer.dispose(); for (const wake of this.waiters) wake();
  }
}

export async function serve(bridge, socketPath) {
  if (!isAbsolute(socketPath) || Buffer.byteLength(socketPath) > 100) throw failure('invalid_socket', 'Use an absolute Unix socket path shorter than 100 bytes');
  const parent = dirname(socketPath);
  await mkdir(parent, { recursive: true, mode: 0o700 });
  const directory = await lstat(parent);
  if (!directory.isDirectory() || directory.uid !== process.getuid() || directory.mode & 0o077) throw failure('unsafe_socket_directory', 'Socket directory must be owned by this user with mode 0700');
  try { await lstat(socketPath); throw failure('socket_exists', 'Bridge socket already exists; remove only after verifying its owner is stopped'); }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
  const server = http.createServer(async (req, res) => {
    const controller = new AbortController();
    res.on('close', () => controller.abort());
    const signal = AbortSignal.any([controller.signal, bridge.lifetime.signal]);
    try {
      let result;
      const url = new URL(req.url, 'http://localhost');
      if (req.method === 'GET' && url.pathname === '/events') {
        result = await bridge.poll(url.searchParams.get('generation'), Number(url.searchParams.get('cursor')), signal);
      } else if (req.method === 'POST' && url.pathname === '/rpc') {
        let bytes = 0; const chunks = [];
        for await (const chunk of req) { bytes += chunk.length; if (bytes > MAX_BODY) throw failure('body_too_large', 'Request exceeds 1 MiB'); chunks.push(chunk); }
        const body = JSON.parse(Buffer.concat(chunks).toString('utf8'));
        if (!record(body)) throw failure('invalid_argument', 'RPC body must be an object');
        result = await bridge.rpc(string(body.method, 'method'), body.params ?? {}, signal);
      } else { res.writeHead(404); res.end(); return; }
      res.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' });
      res.end(JSON.stringify(req.method === 'GET' ? result : { result }));
    } catch (error) {
      if (!res.headersSent) res.writeHead(400, { 'content-type': 'application/json', 'cache-control': 'no-store' });
      const definite = new Set(['invalid_argument', 'unsupported', 'invalid_decision', 'stale_approval', 'body_too_large', 'turn_changed']);
      res.end(JSON.stringify({ error: { code: error.code ?? 'bridge_error', message: error.message,
        uncertain: !definite.has(error.code) } }));
    }
  });
  server.maxConnections = 16; server.requestTimeout = 30000; server.headersTimeout = 10000;
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(socketPath, resolve); });
  await chmod(socketPath, 0o600);
  const identity = await lstat(socketPath);
  return async () => {
    bridge.close(); server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
    try { const current = await lstat(socketPath); if (current.ino === identity.ino && current.dev === identity.dev) await unlink(socketPath); }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
  };
}

export async function apply(ctx, config = {}) {
  const bridge = new Bridge(ctx);
  let close;
  try {
    close = await serve(bridge, config.socketPath ?? join(process.env.DSH_HOME ?? join(homedir(), '.dsh'), 'rivo', 'bridge.sock'));
    ctx.effect(() => close, 'rivo-dsh-bridge.server');
  } catch (error) { if (close) await close(); else bridge.close(); throw error; }
}
