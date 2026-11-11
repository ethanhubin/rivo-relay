import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, stat, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import http from 'node:http';
import { Bridge, serve } from './index.mjs';

function fixture() {
  let now = 1000;
  const listeners = new Map(), calls = [];
  const ctx = {
    on(name, callback) { listeners.set(name, callback); return () => listeners.delete(name); },
    connection: { createPeer: () => ({ dispose() {} }) },
    workspaceRegistry: { list: () => [{ id: 'workspace-1', path: '/tmp/project', title: 'Project', sessionIds: ['s'] }], archivedSessionIds: [], pinnedSessionIds: [] },
    typertGateway: {
      async invoke(input) { calls.push(input); return input.method === 'prompt' ? { accepted: true } : { items: [] }; },
      async stream() { return (async function* () { yield { type: 'snapshot', records: [], cursor: 7, hasMore: false, assistantStream: { revision: 1 } }; })(); },
    },
  };
  const bridge = new Bridge(ctx, { clock: () => now, leaseMs: 100 });
  return { bridge, ctx, calls, listeners, advance: value => { now += value; } };
}

test('prompt preserves durable request identity without inventing a turn/message ID', async t => {
  const { bridge, calls } = fixture(); t.after(() => bridge.close());
  const receipt = await bridge.rpc('prompt', { sessionId: 's', requestId: 'r', text: '你好' });
  assert.deepEqual(receipt, { accepted: true, requestId: 'r' });
  assert.deepEqual(calls[0].args.request, { sessionId: 's', requestId: 'r', mode: 'queue', content: [{ type: 'text', text: '你好' }] });
  assert.equal(calls[0].namespace, 'session');
});

test('snapshot uses official follow opening and cancels its temporary subscription', async t => {
  const { bridge, calls } = fixture(); t.after(() => bridge.close());
  const snapshot = await bridge.rpc('snapshot', { sessionId: 's' });
  assert.equal(snapshot.page.cursor, 7);
  assert.deepEqual(snapshot.page.assistantStream, { revision: 1 });
  assert.equal(calls[0].method, 'state');
  assert.equal(snapshot.generation, bridge.generation);
});

test('event gaps and a replacement generation demand a baseline reset', async t => {
  const { bridge } = fixture(); t.after(() => bridge.close());
  assert.equal((await bridge.poll('old', 0)).reset, true);
  for (let n = 0; n < 600; n++) bridge.publish({ type: 'session', sessionId: 's', event: { seq: n } });
  assert.equal((await bridge.poll(bridge.generation, 0)).reset, true);
  const latest = await bridge.poll(bridge.generation, 599);
  assert.equal(latest.reset, false); assert.equal(latest.events.length, 1); assert.equal(latest.cursor, 600);
});

test('approval requires an active watcher and event consumer; replies are one-shot', async t => {
  const { bridge } = fixture(); t.after(() => bridge.close());
  let native = 0;
  const next = async () => { native++; return 'rejected'; };
  const request = { agent: { session: { id: 's' } }, toolName: 'bash', reason: 'write outside workspace' };
  assert.equal(await bridge.approval(request, next), 'rejected');
  bridge.watch('s'); await bridge.poll(null, 0);
  const pending = bridge.approval(request, next);
  const [key] = bridge.pending.keys();
  assert.equal(native, 1);
  assert.throws(() => bridge.respond({ key, outcome: 'always' }), /Unsupported/);
  bridge.respond({ key, outcome: 'allowed-once' });
  assert.equal(await pending, 'allowed-once');
  assert.throws(() => bridge.respond({ key, outcome: 'allowed-once' }), /no longer owned/);
});

test('lost consumer invalidates pending key and delegates to native approval', async t => {
  const { bridge, advance } = fixture(); t.after(() => bridge.close());
  bridge.watch('s'); await bridge.poll(null, 0);
  let native = 0;
  const pending = bridge.approval({ agent: { session: { id: 's' } }, toolName: 'bash' }, async () => { native++; return 'rejected'; });
  const [key] = bridge.pending.keys();
  advance(101); bridge.expireApprovals();
  assert.equal(await pending, 'rejected'); assert.equal(native, 1);
  assert.throws(() => bridge.respond({ key, outcome: 'allowed-once' }), /no longer owned/);
});

test('cancelled request and plugin unload invalidate approval replies', async t => {
  const { bridge } = fixture(); t.after(() => bridge.close());
  bridge.watch('s'); await bridge.poll(null, 0);
  const abort = new AbortController();
  const pending = bridge.approval({ agent: { session: { id: 's' } }, toolName: 'bash', signal: abort.signal }, () => 'rejected');
  const [key] = bridge.pending.keys(); abort.abort();
  assert.equal(await pending, 'cancelled');
  assert.throws(() => bridge.respond({ key, outcome: 'allowed-once' }), /no longer owned/);
  const another = bridge.approval({ agent: { session: { id: 's' } }, toolName: 'bash' }, () => 'rejected');
  bridge.close(); assert.equal(await another, 'rejected');
});

test('receipt uses admitted message provenance, never matching user text', async t => {
  const { bridge, ctx } = fixture(); t.after(() => bridge.close());
  let records = [{ event: { type: 'user/message', data: { content: [{ type: 'text', text: 'r' }], source: { kind: 'user', rpcId: 'other' } } } }];
  ctx.typertGateway.invoke = async () => ({ records, hasMore: false, cursor: 1 });
  assert.deepEqual(await bridge.rpc('receipt', { sessionId: 's', requestId: 'r' }), { accepted: false });
  records = [{ event: { type: 'agent/inbox/spliced', data: { inserted: [{ source: { kind: 'user', rpcId: 'r' } }] } } }];
  assert.deepEqual(await bridge.rpc('receipt', { sessionId: 's', requestId: 'r' }), { accepted: true, requestId: 'r' });
});

function request(socketPath, method, path, body) {
  return new Promise((resolve, reject) => {
    const req = http.request({ socketPath, method, path, headers: { 'content-type': 'application/json' } }, res => {
      const chunks = []; res.on('data', chunk => chunks.push(chunk));
      res.on('end', () => resolve({ status: res.statusCode, body: JSON.parse(Buffer.concat(chunks).toString()) }));
    });
    req.on('error', reject); req.end(body === undefined ? undefined : JSON.stringify(body));
  });
}

test('Unix transport uses private socket and cleans only its own endpoint', async t => {
  const folder = await mkdtemp(join(tmpdir(), 'rivo-dsh-test-'));
  const socketPath = join(folder, 'bridge.sock');
  const { bridge } = fixture();
  const close = await serve(bridge, socketPath);
  t.after(async () => { await close(); await rm(folder, { recursive: true }); });
  assert.equal((await stat(socketPath)).mode & 0o777, 0o600);
  const status = await request(socketPath, 'POST', '/rpc', { method: 'status' });
  assert.equal(status.body.result.connected, true);
  const rejected = await request(socketPath, 'POST', '/rpc', { method: 'prompt', params: { sessionId: 's', requestId: '', text: 'hello' } });
  assert.equal(rejected.body.error.uncertain, false);
  const reset = await request(socketPath, 'GET', '/events?cursor=0');
  assert.equal(reset.body.reset, true);
});

test('permission changes only the resolved session and propagates native refusal', async t => {
  const { bridge, ctx } = fixture(); t.after(() => bridge.close());
  const session = { id: 's' }; const writes = [];
  ctx.sessionController = { resolveAgent: async () => ({ agent: { session } }) };
  ctx.get = name => name === 'permissionPresets' ? { set: (target, preset) => writes.push([target, preset]), current: () => 'workspace' } : undefined;
  assert.deepEqual(await bridge.rpc('permission', { sessionId: 's', preset: 'workspace' }), { currentValue: 'workspace' });
  assert.deepEqual(writes, [[session, 'workspace']]);
  ctx.sessionController.resolveAgent = async () => ({ error: { code: 'session/writer-held', message: 'occupied' } });
  await assert.rejects(bridge.rpc('permission', { sessionId: 's', preset: 'workspace' }), /occupied/);
  assert.equal(writes.length, 1);
});

test('cancel fences the native turn immediately before the synchronous cancel', async t => {
  const { bridge, ctx, calls } = fixture(); t.after(() => bridge.close());
  let boundary = { openTurnStartSeq: 3, lastTurn: 2 }, cancelled = 0;
  ctx.agents = { get: () => ({ session: { id: 's' } }), turnBoundaryOf: () => boundary };
  ctx.sessionController = { cancel: ({ sessionId }) => { assert.equal(sessionId, 's'); cancelled++; return { accepted: true }; } };
  await assert.rejects(bridge.rpc('cancel', { sessionId: 's', expectedTurn: 1 }), /no longer matches/);
  assert.equal(cancelled, 0);
  assert.deepEqual(await bridge.rpc('cancel', { sessionId: 's', expectedTurn: 2 }), { accepted: true });
  assert.equal(cancelled, 1); assert.equal(calls.length, 0);
  boundary = { openTurnStartSeq: null, lastTurn: 2 };
  await assert.rejects(bridge.rpc('cancel', { sessionId: 's', expectedTurn: 2 }), /no longer matches/);
  assert.equal(cancelled, 1);
});
