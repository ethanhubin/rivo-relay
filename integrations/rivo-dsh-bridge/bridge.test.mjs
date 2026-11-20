import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, stat, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import http from 'node:http';
import { createHash } from 'node:crypto';
import { Bridge, serve } from './index.mjs';

function fixture() {
  let now = 1000;
  const listeners = new Map(), calls = [];
  const ctx = {
    on(name, callback) { listeners.set(name, callback); return () => listeners.delete(name); },
    connection: { createPeer: () => ({ dispose() {} }) },
    workspaceRegistry: { list: () => [{ id: 'workspace-1', path: '/tmp/project', title: 'Project', sessionIds: ['s'] }], archivedSessionIds: [], pinnedSessionIds: [] },
    typertGateway: {
      getPendingRemoteEvents: () => [],
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
  assert.equal(rejected.body.error.certainty, 'rejected');
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

test('update status skips inactive migration-required history and reads live state without watching or renewing approval lease', async t => {
  const { bridge, ctx, calls } = fixture(); t.after(() => bridge.close());
  const lastPoll = bridge.lastPoll;
  ctx.typertGateway.invoke = async input => {
    calls.push(input);
    if (input.method === 'list') return { items: [
      { sessionId: 'live', agentAvailable: true, running: false, projections: { kind: 'cached', values: { inbox: { 'next-turn': ['stale'] } } } },
      { sessionId: 'cold', agentAvailable: false, running: false, formatStatus: 'migration-required' },
    ] };
    assert.equal(input.method, 'state');
    if (input.args.request.sessionId === 'cold') throw new Error('Historical session requires migration');
    return { cursor: -1, active: true, running: false, queue: [], pendingInput: [] };
  };
  assert.deepEqual(await bridge.rpc('updateStatus'), { generation: bridge.generation, cursor: 0, idle: true });
  assert.deepEqual(calls.map(call => call.method), ['list', 'state']);
  assert.equal(calls[1].args.request.sessionId, 'live');
  assert.equal(bridge.watched.size, 0); assert.equal(bridge.lastPoll, lastPoll);
});

test('update status accepts inactive history only when catalog and global pending input are clear', async t => {
  const { bridge, ctx, calls } = fixture(); t.after(() => bridge.close());
  const row = { sessionId: 'cold', agentAvailable: false, running: false, formatStatus: 'migration-required' };
  ctx.typertGateway.invoke = async input => {
    calls.push(input);
    assert.equal(input.method, 'list');
    return { items: [row] };
  };
  assert.equal((await bridge.rpc('updateStatus')).idle, true);
  row.running = true;
  assert.equal((await bridge.rpc('updateStatus')).idle, false);
  row.running = false;
  ctx.typertGateway.getPendingRemoteEvents = () => [{ event: 'user-questions/request', agentId: 'cold' }];
  assert.equal((await bridge.rpc('updateStatus')).idle, false);
  assert.deepEqual(calls.map(call => call.method), ['list', 'list', 'list']);
});

test('update status rejects execution, queued input, and all pending native interaction types', async t => {
  const { bridge, ctx } = fixture(); t.after(() => bridge.close());
  const baseline = { cursor: 1, active: true, running: false, queue: [], pendingInput: [] };
  let state;
  ctx.typertGateway.invoke = async input => input.method === 'list' ? { items: [{ sessionId: 's', agentAvailable: true, running: false }] } : state;
  for (const change of [
    { running: true }, { queue: [{ placement: 'queued' }] }, { queue: [{ placement: 'steering' }] },
    { queue: [{ placement: 'context' }] }, { pendingInput: ['approval'] },
    { pendingInput: ['question'] }, { pendingInput: ['other'] },
  ]) {
    state = { ...baseline, ...change };
    assert.equal((await bridge.rpc('updateStatus')).idle, false, JSON.stringify(change));
  }
});

test('update status never treats malformed passive state as idle', async t => {
  const { bridge, ctx } = fixture(); t.after(() => bridge.close());
  const baseline = { cursor: 0, active: true, running: false, queue: [], pendingInput: [] };
  let state;
  ctx.typertGateway.invoke = async input => input.method === 'list' ? { items: [{ sessionId: 's', agentAvailable: true, running: false }] } : state;
  for (const candidate of [null, {}, { ...baseline, cursor: undefined }, { ...baseline, cursor: -2 },
    { ...baseline, active: undefined }, { ...baseline, active: 0 }, { ...baseline, running: undefined },
    { ...baseline, running: 'false' }, { ...baseline, queue: null }, { ...baseline, pendingInput: null },
    { ...baseline, active: false }, { ...baseline, active: false, queue: undefined }]) {
    state = candidate;
    assert.equal((await bridge.rpc('updateStatus')).idle, false, JSON.stringify(candidate));
  }
});

test('update status rejects incomplete catalog and native read failures', async t => {
  const { bridge, ctx } = fixture(); t.after(() => bridge.close());
  const baseline = { sessionId: 's', agentAvailable: true, running: false };
  for (const catalog of [null, {}, { items: null }, { items: [{}] }, { items: [baseline, baseline] },
    ...[{ sessionId: 's' }, { ...baseline, agentAvailable: undefined }, { ...baseline, agentAvailable: 'false' },
      { ...baseline, running: undefined }, { ...baseline, running: 'false' }].map(row => ({ items: [row] }))]) {
    ctx.typertGateway.invoke = async () => catalog;
    assert.equal((await bridge.rpc('updateStatus')).idle, false);
  }
  ctx.typertGateway.invoke = async () => { throw new Error('native state unavailable'); };
  assert.equal((await bridge.rpc('updateStatus')).idle, false);
  ctx.typertGateway.invoke = async input => {
    if (input.method === 'list') return { items: [baseline] };
    throw new Error('live state unavailable');
  };
  assert.equal((await bridge.rpc('updateStatus')).idle, false);
});

test('update status notices unwatched inbox changes even when the event cursor stays unchanged', async t => {
  const { bridge, ctx, listeners } = fixture(); t.after(() => bridge.close());
  ctx.typertGateway.invoke = async input => {
    if (input.method === 'list') return { items: [{ sessionId: 'unwatched', agentAvailable: true, running: false }] };
    listeners.get('session/event')({ id: 'unwatched' }, { type: 'agent/inbox/spliced' });
    return { cursor: 1, active: true, running: false, queue: [], pendingInput: [] };
  };
  assert.deepEqual(await bridge.rpc('updateStatus'), { generation: bridge.generation, cursor: 0, idle: false });
  assert.equal(bridge.watched.size, 0);
});

test('update status rejects catalog or running changes during the native reads', async t => {
  const { bridge, ctx, listeners } = fixture(); t.after(() => bridge.close());
  for (const event of ['api-session/added', 'api-session/removed', 'api-session/status']) {
    ctx.typertGateway.invoke = async input => {
      if (input.method === 'list') return { items: [{ sessionId: 's', agentAvailable: true, running: false }] };
      listeners.get(event)('s', false);
      return { cursor: 0, active: true, running: false, queue: [], pendingInput: [] };
    };
    assert.equal((await bridge.rpc('updateStatus')).idle, false);
  }
});

test('update status rejects bridge-owned approvals without renewing or settling them', async t => {
  const { bridge, calls } = fixture(); t.after(() => bridge.close());
  bridge.watch('s'); await bridge.poll(null, 0);
  const approval = bridge.approval({ agent: { session: { id: 's' } }, toolName: 'bash' }, () => 'rejected');
  const lastPoll = bridge.lastPoll;
  assert.equal((await bridge.rpc('updateStatus')).idle, false);
  assert.equal(bridge.pending.size, 1); assert.equal(bridge.lastPoll, lastPoll); assert.equal(calls.length, 0);
  bridge.close(); assert.equal(await approval, 'rejected');
});

test('update status rechecks native pending questions that need not emit session events', async t => {
  const { bridge, ctx } = fixture(); t.after(() => bridge.close());
  let pending = [];
  ctx.typertGateway.getPendingRemoteEvents = () => pending;
  ctx.typertGateway.invoke = async input => {
    if (input.method === 'list') return { items: ['first', 'second'].map(sessionId => ({ sessionId, agentAvailable: true, running: false })) };
    if (input.args.request.sessionId === 'second') pending = [{ event: 'user-questions/request', agentId: 'first' }];
    return { cursor: 0, active: true, running: false, queue: [], pendingInput: [] };
  };
  assert.deepEqual(await bridge.rpc('updateStatus'), { generation: bridge.generation, cursor: 0, idle: false });
});

test('update status rejects cancellation and bridge close while native state is being read', async t => {
  for (const close of [false, true]) {
    const { bridge, ctx } = fixture(); t.after(() => bridge.close());
    const controller = new AbortController();
    ctx.typertGateway.invoke = async input => {
      if (input.method === 'list') return { items: [{ sessionId: 's', agentAvailable: true, running: false }] };
      if (close) bridge.close(); else controller.abort();
      return { cursor: 0, active: true, running: false, queue: [], pendingInput: [] };
    };
    assert.equal((await bridge.rpc('updateStatus', {}, controller.signal)).idle, false);
  }
});

const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg==', 'base64');
function imageRef(data = PNG) {
  return { attachmentId: 'sha256:' + createHash('sha256').update(data).digest('hex'),
    mediaType: 'image/png', bytes: data.length, width: 1, height: 1, name: 'picture.png' };
}
function imageEvent(seq = 20) {
  return { seq, type: 'tool/result', data: { message: { content: [{ type: 'image', attachment: imageRef() }] } } };
}

test('snapshot publishes image locators and code-point Markdown offsets without paths or image bytes', async t => {
  const { bridge, ctx } = fixture(); t.after(() => bridge.close());
  const body = '😀 中文 ![one](</outside workspace/first.png>) ![two](/tmp/second(2).png) [save](/tmp/third.png) ![remote](https://example.com/fourth.png)';
  const event = { seq: 21, type: 'assistant/message', data: { message: { content: [{ type: 'reasoning', text: 'private' }, { type: 'text', text: body }] } } };
  ctx.typertGateway.stream = async () => (async function* () {
    yield { type: 'snapshot', records: [{ event: imageEvent() }, { event }],
      projections: { values: { modelSelection: { next: { provider: 'p', model: 'm', reasoningEffort: 'high' } } } } };
  })();
  const value = await bridge.rpc('snapshot', { sessionId: 's' });
  assert.deepEqual(value.selected, { provider: 'p', model: 'm', reasoningEffort: 'high' });
  assert.equal(value.media.length, 4);
  assert.deepEqual(value.media.slice(1).map(m => [...body].slice(m.start,m.end).join('')),
    ['/outside workspace/first.png', '/tmp/second(2).png', '/tmp/third.png']);
  assert.deepEqual(value.media.slice(1).map(m => m.imageIndex), [0,1,2]);
  assert.equal(value.media.every(m => !('source' in m) && !('attachment' in m) && !('data' in m)), true);
  assert.equal(value.page.records[1].event.data.message.content[1].text, body);
});

test('Markdown media skips literal code, resolves shared references once, and keeps code-point coordinates', async t => {
  const { bridge, ctx } = fixture(); t.after(() => bridge.close());
  const body = [
    '😀 `![inline](/no/inline.png)` and ``![ticks](/no/ticks.png) `code` ``',
    '', '```markdown', '![fenced](/no/fence.png)', '```',
    '', '> ~~~~', '> ![quoted](/no/quote.png)', '> ~~~~',
    '', '- ```', '  ![listed](/no/list.png)', '  ```',
    '', '    ![indented](/no/indent.png)', '\t![tab](/no/tab.png)',
    '', '<!--', '![comment](/no/comment.png)', '-->',
    '', '![one][SHARED] ![two][shared] [download][shared]',
    '![Collapsed][] and ![shortcut] and ![nested [label]](/yes/nested(a(b)).png "title")',
    '', '[shared]: </yes/shared image.png> "shared title"',
    '[collapsed]: /yes/collapsed.png', '[shortcut]:', '  /yes/shortcut.png',
    '[unused]: /no/unused.png', '[SHARED]: /no/duplicate.png',
  ].join('\n');
  const event = { seq: 2, type: 'assistant/message', data: { message: { content: [{ type: 'text', text: body }] } } };
  ctx.typertGateway.stream = async () => (async function* () { yield { type: 'snapshot', records: [{ event }] }; })();
  const value = await bridge.rpc('snapshot', { sessionId: 's' });
  assert.deepEqual(value.media.map(item => [...body].slice(item.start,item.end).join('')),
    ['/yes/nested(a(b)).png', '/yes/shared image.png', '/yes/collapsed.png', '/yes/shortcut.png']);
  assert.deepEqual(value.media.map(item => item.imageIndex), [0,1,2,3]);
  assert.equal(value.media.filter(item => item.name === 'shared image.png').length, 1);
  assert.deepEqual(value.media[1].referenceUses.map(use => [...body].slice(use.start,use.end).join('')),
    ['![one][SHARED]', '![two][shared]', '[download][shared]']);
  assert.deepEqual(value.media[1].referenceUses.map(({ label, image }) => ({ label, image })),
    [{ label: 'one', image: true }, { label: 'two', image: true }, { label: 'download', image: false }]);
  assert.equal([...body].slice(value.media[2].referenceUses[0].start,value.media[2].referenceUses[0].end).join(''), '![Collapsed][]');
  assert.equal([...body].slice(value.media[3].referenceUses[0].start,value.media[3].referenceUses[0].end).join(''), '![shortcut]');
  assert.equal(value.page.records[0].event.data.message.content[0].text, body);
  for (let i = 1; i < value.media.length; i++) assert.ok(value.media[i-1].end <= value.media[i].start);
});

test('Markdown media distinguishes escaped and unmatched delimiters from literal code', async t => {
  const { bridge, ctx } = fixture(); t.after(() => bridge.close());
  const body = '\\[literal](/no/escaped.png) ` unmatched ![real](/yes/a\\(b\\).png)\n\n'
    + '![label `code`](/yes/label.png) [plain](/yes/plain.png) ![remote][ref]\n\n[ref]: https://example.com/image.png';
  const event = { seq: 2, type: 'assistant/message', data: { message: { content: [{ type: 'text', text: body }] } } };
  ctx.typertGateway.stream = async () => (async function* () { yield { type: 'snapshot', records: [{ event }] }; })();
  const value = await bridge.rpc('snapshot', { sessionId: 's' });
  assert.deepEqual(value.media.map(item => [...body].slice(item.start,item.end).join('')),
    ['/yes/a\\(b\\).png', '/yes/label.png', '/yes/plain.png']);
});

test('a backslash inside a code span does not escape its closing backtick', async t => {
  const { bridge, ctx } = fixture(); t.after(() => bridge.close());
  const body = '`![literal](/no/inside.png)\\` ![real](/yes/outside.png)';
  const event = { seq: 2, type: 'assistant/message', data: { message: { content: [{ type: 'text', text: body }] } } };
  ctx.typertGateway.stream = async () => (async function* () { yield { type: 'snapshot', records: [{ event }] }; })();
  const value = await bridge.rpc('snapshot', { sessionId: 's' });
  assert.deepEqual(value.media.map(item => [...body].slice(item.start,item.end).join('')), ['/yes/outside.png']);
});

test('reference media re-resolves only the native history definition, without trusting caller paths', async t => {
  const { bridge, ctx } = fixture(); t.after(() => bridge.close());
  const body = '![first][pic] ![second][PIC]\n\n[pic]: </outside/shared image.png>';
  const event = { seq: 2, type: 'assistant/message', data: { message: { content: [{ type: 'text', text: body }] } } };
  ctx.typertGateway.invoke = async input => {
    if (input.method === 'events') return { records: [{ event }] };
    assert.equal(input.args.path, '/outside/shared image.png');
    const info = { absolutePath: input.args.path, version: 'v1', bytes: PNG.length };
    return input.method === 'stat' ? info : { ...info, offset: 0, data: PNG };
  };
  assert.equal((await bridge.rpc('media', { sessionId: 's', eventSeq: 2, blockIndex: 0, imageIndex: 0 })).data, PNG.toString('base64'));
  await assert.rejects(bridge.rpc('media', { sessionId: 's', eventSeq: 2, blockIndex: 0, imageIndex: 1 }), /No supported image/);
});

test('media reads the exact historical attachment and checks content identity', async t => {
  const { bridge, ctx, calls } = fixture(); t.after(() => bridge.close());
  let payload = PNG.toString('base64');
  ctx.typertGateway.invoke = async input => {
    calls.push(input);
    if (input.method === 'events') return { records: [{ event: imageEvent() }] };
    assert.equal(input.method, 'attachment'); assert.deepEqual(input.args.request.attachment, imageRef());
    return { data: payload, attachment: imageRef() };
  };
  const locator = { sessionId: 's', eventSeq: 20, blockIndex: 0, imageIndex: 0 };
  const value = await bridge.rpc('media', locator);
  assert.equal(value.data, PNG.toString('base64')); assert.equal(value.sha256, imageRef().attachmentId.slice(7));
  assert.equal(value.mediaType, 'image/png'); assert.equal(value.width, 1); assert.equal(bridge.watched.size, 0);
  assert.deepEqual(calls[0].args.request, { address: { kind: 'session', sessionId: 's' }, throughSeq: 20, maxMessages: 1 });
  await assert.rejects(bridge.rpc('media', { ...locator, path: '/untrusted/file.png' }), /locator only/);
  await assert.rejects(bridge.rpc('media', { ...locator, eventSeq: 19 }), /No supported image/);
  payload = Buffer.from('different bytes').toString('base64');
  await assert.rejects(bridge.rpc('media', locator), /integrity/);
});

test('media resolves only the historical Markdown image, including outside-workspace paths', async t => {
  const { bridge, ctx, calls } = fixture(); t.after(() => bridge.close());
  const event = { seq: 2, type: 'assistant/message', data: { message: { content: [
    { type: 'text', text: '![first](/outside/first.png) ![second](</outside/second image.png>)' }] } } };
  const info = { absolutePath: '/outside/second image.png', version: 'v1', bytes: PNG.length };
  let version = 'v1';
  ctx.typertGateway.invoke = async input => {
    calls.push(input);
    if (input.method === 'events') return { records: [{ event }] };
    assert.equal(input.namespace, 'workspaceFiles'); assert.equal(input.args.workspaceFileScopeId, 's');
    assert.equal(input.args.path, info.absolutePath);
    return input.method === 'stat' ? { ...info, version } : { ...info, offset: 0, data: PNG, eof: true };
  };
  const locator = { sessionId: 's', eventSeq: 2, blockIndex: 0, imageIndex: 1 };
  assert.equal((await bridge.rpc('media', locator)).data, PNG.toString('base64'));
  assert.deepEqual(calls.map(c => c.method), ['events','stat','readBytes','stat']);
  version = 'v2';
  await assert.rejects(bridge.rpc('media', locator), /changed/);
});

test('media refuses oversized, non-image, missing and changed native file reads', async t => {
  const { bridge, ctx } = fixture(); t.after(() => bridge.close());
  const event = { seq: 2, type: 'assistant/message', data: { message: { content: [{ type: 'text', text: '![x](/out/image.png)' }] } } };
  const locator = { sessionId: 's', eventSeq: 2, blockIndex: 0 };
  for (const mode of ['large','text','changed','missing']) {
    let stats = 0, reads = 0;
    const data = mode === 'text' ? Buffer.from('not a raster image') : PNG;
    ctx.typertGateway.invoke = async input => {
      if (input.method === 'events') return { records: [{ event }] };
      if (mode === 'missing') throw new Error('native file unavailable');
      const info = { absolutePath: '/out/image.png', version: 'v1', bytes: mode === 'large' ? 20*1024*1024+1 : data.length };
      if (input.method === 'stat') { stats++; return { ...info, version: mode === 'changed' && stats > 1 ? 'v2' : 'v1' }; }
      reads++; return { ...info, offset: 0, data, eof: true };
    };
    await assert.rejects(bridge.rpc('media', locator));
    if (mode === 'large') assert.equal(reads, 0);
  }
});

test('media follows the native preview filesystem resolver for referenced symlinks and relative paths', async t => {
  const { bridge, ctx } = fixture(); t.after(() => bridge.close());
  let source = '/outside/linked.png';
  const resolved = [];
  const target = { displayPath: '/resolved/image.png' };
  ctx.fs = {
    async resolve(path, options) { resolved.push({ path, cwd: options.cwd }); return target; },
    async stat(value) { assert.equal(value, target); return { type: 'file', size: PNG.length, version: 'v1' }; },
    processPath: value => value.displayPath,
    async readByteRange(value, range) { assert.equal(value, target); return PNG.subarray(range.offset,range.offset+range.length); },
  };
  ctx.typertGateway.invoke = async input => {
    if (input.method === 'list') return { items: [{ sessionId: 's', cwd: '/workspace' }] };
    assert.equal(input.method, 'events');
    return { records: [{ event: { seq: 1, type: 'assistant/message', data: { message: { content: [{ type: 'text', text: `![image](${source})` }] } } } }] };
  };
  const locator = { sessionId: 's', eventSeq: 1, blockIndex: 0 };
  assert.equal((await bridge.rpc('media', locator)).data, PNG.toString('base64'));
  source = 'images/relative.png';
  assert.equal((await bridge.rpc('media', locator)).data, PNG.toString('base64'));
  assert.deepEqual(resolved, [{ path: '/outside/linked.png', cwd: undefined }, { path: 'images/relative.png', cwd: '/workspace' }]);
});

test('history file images preserve BMP, ICO, AVIF and SVG bytes for Relay validation and preview', async t => {
  const { bridge, ctx } = fixture(); t.after(() => bridge.close());
  const bmp = Buffer.alloc(58); bmp.write('BM'); bmp.writeUInt32LE(58,2); bmp.writeUInt32LE(54,10);
  bmp.writeUInt32LE(40,14); bmp.writeInt32LE(1,18); bmp.writeInt32LE(1,22); bmp.writeUInt16LE(1,26); bmp.writeUInt16LE(24,28);
  const ico = Buffer.alloc(22+PNG.length); ico.writeUInt16LE(1,2); ico.writeUInt16LE(1,4); ico[6]=1; ico[7]=1;
  ico.writeUInt16LE(1,10); ico.writeUInt16LE(32,12); ico.writeUInt32LE(PNG.length,14); ico.writeUInt32LE(22,18); PNG.copy(ico,22);
  const avifHeader = Buffer.alloc(24); avifHeader.writeUInt32BE(24,0); avifHeader.write('ftyp',4); avifHeader.write('avif',8); avifHeader.write('avif',16);
  const svg = Buffer.from('<svg xmlns="http://www.w3.org/2000/svg" width="1" height="1"><path d="M0 0h1v1H0z"/></svg>');
  for (const [suffix, data, mime] of [['bmp',bmp,'image/bmp'], ['ico',ico,'image/x-icon'], ['avif',avifHeader,'image/avif'], ['svg',svg,'image/svg+xml']]) {
    const path = `/work/image.${suffix}`;
    ctx.typertGateway.invoke = async input => {
      if (input.method === 'events') return { records: [{ event: { seq: 1, type: 'assistant/message', data: {
        message: { content: [{ type: 'text', text: `[download](${path}?v=1#preview)` }] } } } }] };
      assert.equal(input.args.path,path);
      const info = { absolutePath: path, version: 'v1', bytes: data.length };
      return input.method === 'stat' ? info : { ...info, offset: 0, data, eof: true };
    };
    const value = await bridge.rpc('media', { sessionId: 's', eventSeq: 1, blockIndex: 0 });
    assert.equal(value.mediaType,mime); assert.equal(value.data,data.toString('base64')); assert.equal(value.size,data.length);
    assert.equal(value.sha256,createHash('sha256').update(data).digest('hex'));
    assert.equal(value.width,undefined); assert.equal(value.height,undefined);
  }
});

test('model selection uses the session route for effort-only updates and can restore adapter default effort', async t => {
  const { bridge, ctx, calls } = fixture(); t.after(() => bridge.close());
  let current = { provider: 'p', model: 'm', reasoningEffort: 'medium' };
  const agent = { model: { current: () => current } };
  ctx.agents = { get: () => agent }; ctx.sessionController = { resolveAgent: async () => ({ agent }) };
  ctx.typertGateway.invoke = async input => {
    calls.push(input); assert.equal(input.method, 'selectModel');
    const { sessionId, ...selected } = input.args.request; current = selected; return { selected };
  };
  assert.deepEqual(await bridge.rpc('selectModel', { sessionId: 's', selection: { reasoningEffort: 'high' } }),
    { selected: { provider: 'p', model: 'm', reasoningEffort: 'high' } });
  await bridge.rpc('selectModel', { sessionId: 's', selection: { provider: 'p', model: 'm', reasoningEffort: 'high' } });
  assert.equal(calls.length, 2);
  await bridge.rpc('selectModel', { sessionId: 's', selection: { reasoningEffort: null } });
  assert.deepEqual(current, { provider: 'p', model: 'm' });
});

test('explicit selection of an effective default reaches native persistence and native deduplication', async t => {
  const { bridge, ctx } = fixture(); t.after(() => bridge.close());
  const fallback = { provider: 'p', model: 'm' };
  let recorded, writes = 0, calls = 0;
  ctx.agents = { get: () => ({ model: { current: () => recorded ?? fallback } }) };
  ctx.typertGateway.invoke = async input => {
    assert.equal(input.method, 'selectModel'); calls++;
    const { sessionId, ...selected } = input.args.request;
    if (JSON.stringify(recorded) !== JSON.stringify(selected)) { recorded = selected; writes++; }
    return { selected };
  };
  await bridge.rpc('selectModel', { sessionId: 's', selection: fallback });
  await bridge.rpc('selectModel', { sessionId: 's', selection: fallback });
  assert.deepEqual(recorded, fallback); assert.equal(writes, 1); assert.equal(calls, 2);
});

test('create preserves the planned identity; recovery requires native header identity and current workspace membership', async t => {
  const { bridge, ctx, calls } = fixture(); t.after(() => bridge.close());
  const workspace = { id: 'w', path: '/project', sessionIds: [] };
  ctx.workspaceRegistry.get = id => id === 'w' ? workspace : undefined;
  let rows = [];
  ctx.typertGateway.invoke = async input => {
    calls.push(input);
    if (input.method === 'create') {
      assert.deepEqual(input.args.request, { sessionId: 'planned', workspaceId: 'w' });
      rows = [{ sessionId: 'planned', cwd: '/project' }];
      throw Object.assign(new Error('persisted but attach failed'), { code: 'session/workspace-attach-failed' });
    }
    assert.equal(input.method, 'list'); return { items: rows };
  };
  await assert.rejects(bridge.rpc('create', { sessionId: 'planned', workspaceId: 'w' }), /attach failed/);
  const receipt = () => bridge.rpc('createReceipt', { sessionId: 'planned', workspaceId: 'w' });
  assert.deepEqual(await receipt(), { generation: bridge.generation, created: false });
  workspace.sessionIds.push('planned');
  assert.deepEqual(await receipt(), { generation: bridge.generation, created: true, sessionId: 'planned', workspaceId: 'w' });
  rows[0].cwd = '/other'; assert.equal((await receipt()).created, false);
  rows = []; assert.equal((await receipt()).created, false);
  assert.equal(calls.filter(call => call.method === 'create').length, 1);
  assert.equal(bridge.watched.size, 0);
});

test('sessions carry generation and only unambiguous native workspace membership', async t => {
  const { bridge, ctx } = fixture(); t.after(() => bridge.close());
  ctx.workspaceRegistry.list = () => [{ id: 'a', sessionIds: ['s','shared'] }, { id: 'b', sessionIds: ['shared'] }];
  ctx.typertGateway.invoke = async () => ({ items: ['s','shared','external'].map(sessionId => ({ sessionId, running: true })) });
  const result = await bridge.rpc('sessions');
  assert.equal(result.generation, bridge.generation);
  assert.deepEqual(result.items.map(row => row.projectId), ['a',null,null]);
});

test('HTTP certainty describes the admission phase, never a broad native error-code whitelist', async t => {
  const folder = await mkdtemp(join(tmpdir(), 'rivo-dsh-certainty-'));
  const { bridge, ctx } = fixture();
  const socketPath = join(folder, 'bridge.sock'), close = await serve(bridge, socketPath);
  t.after(async () => { await close(); await rm(folder, { recursive: true }); });
  let selected = false, admitted = false, failSelection = true;
  ctx.typertGateway.invoke = async input => {
    if (input.method === 'events') return { records: [], hasMore: false };
    if (input.method === 'selectModel') {
      selected = true;
      if (failSelection) throw Object.assign(new Error('selection outcome unavailable'), { code: 'session/model-unavailable' });
      return { selected: input.args.request };
    }
    assert.equal(input.method, 'prompt'); admitted = true;
    throw Object.assign(new Error('reply lost after admission'), { code: 'invalid_argument' });
  };
  const send = (method, params) => request(socketPath, 'POST', '/rpc', { method, params });
  const params = { sessionId: 's', requestId: 'r', text: 'hello', selection: { provider: 'p', model: 'm' } };
  let response = await send('prompt', params);
  assert.equal(selected, true); assert.equal(admitted, false);
  assert.equal(response.body.error.certainty, 'rejected'); assert.equal(response.body.error.uncertain, false);
  response = await send('selectModel', { sessionId: 's', selection: params.selection });
  assert.equal(response.body.error.certainty, 'unknown'); assert.equal(response.body.error.uncertain, true);
  failSelection = false;
  response = await send('prompt', params);
  assert.equal(admitted, true); assert.equal(response.body.error.certainty, 'unknown'); assert.equal(response.body.error.uncertain, true);
  response = await send('selectModel', { sessionId: 's', selection: { provider: 'p' } });
  assert.equal(response.body.error.certainty, 'rejected');
  ctx.typertGateway.invoke = async () => {
    throw Object.assign(new Error('Select an available model before sending a message.'), { code: 'session/model-unavailable' });
  };
  response = await send('selectModel', { sessionId: 's', selection: params.selection });
  assert.equal(response.body.error.certainty, 'rejected'); assert.equal(response.body.error.uncertain, false);
  for (const [code, message, certainty] of [
    ['gateway/bad-request', 'prompt content must include non-whitespace text or an attachment', 'rejected'],
    ['session/invalid-time-zone', 'clientTimeZone must be UTC or a valid IANA Area/Location name', 'rejected'],
    ['gateway/bad-request', 'unknown native phase', 'unknown'],
    ['session/invalid-time-zone', 'unknown native phase', 'unknown'],
  ]) {
    ctx.typertGateway.invoke = async () => { throw Object.assign(new Error(message), { code }); };
    response = await send('prompt', { sessionId: 's', requestId: 'r', text: 'hello' });
    assert.equal(response.body.error.certainty, certainty, message);
    assert.equal(response.body.error.uncertain, certainty === 'unknown', message);
  }
});

test('a queued selection cancelled before native invocation is definitely rejected', async t => {
  const { bridge, ctx } = fixture(); t.after(() => bridge.close());
  let release, calls = 0;
  const wait = new Promise(resolve => { release = resolve; });
  ctx.typertGateway.invoke = async () => { calls++; await wait; return { selected: { provider: 'p', model: 'm' } }; };
  const first = bridge.rpc('selectModel', { sessionId: 's', selection: { provider: 'p', model: 'm' } });
  const controller = new AbortController();
  const second = bridge.rpc('selectModel', { sessionId: 's', selection: { provider: 'p', model: 'other' } }, controller.signal);
  const rejected = assert.rejects(second, error => error.certainty === 'rejected');
  await Promise.resolve(); controller.abort(); release(); await first; await rejected;
  assert.equal(calls, 1);
});

test('mixed prompt preserves native image and staged-file identities and only returns input acceptance', async t => {
  const { bridge, ctx, calls } = fixture(); t.after(() => bridge.close());
  ctx.typertGateway.invoke = async input => { calls.push(input); return { accepted: true }; };
  const content = [{ type: 'text', text: '' }, { type: 'image', mediaType: 'image/png', data: PNG.toString('base64'), name: 'p.png' },
    { type: 'file', receiptId: 'native-receipt' }];
  assert.deepEqual(await bridge.rpc('prompt', { sessionId: 's', requestId: 'r', content }), { accepted: true, requestId: 'r' });
  assert.deepEqual(calls[0].args.request.content, content);
  for (const part of [{ type: 'file', path: '/untrusted' }, { type: 'image', attachment: imageRef() },
    { type: 'image', mediaType: 'image/png', data: 'YQ==' + '\n' }]) {
    await assert.rejects(bridge.rpc('prompt', { sessionId: 's', requestId: 'x', content: [part] }));
  }
  assert.equal(calls.length, 1);
});

test('accepted prompt replay cannot change the model even when its input remains queued', async t => {
  const { bridge, ctx, calls } = fixture(); t.after(() => bridge.close());
  ctx.typertGateway.invoke = async input => {
    calls.push(input);
    return { hasMore: false, records: [{ event: { seq: 1, type: 'agent/inbox/spliced',
      data: { inserted: [{ source: { kind: 'user', rpcId: 'r' } }] } } }] };
  };
  assert.deepEqual(await bridge.rpc('prompt', { sessionId: 's', requestId: 'r', text: 'same', selection: { provider: 'p', model: 'other' } }),
    { accepted: true, requestId: 'r' });
  assert.deepEqual(calls.map(c => c.method), ['events']);
});

test('settings and prompt are serialized, while a rejected prompt does not roll back native model selection', async t => {
  const { bridge, ctx, calls } = fixture(); t.after(() => bridge.close());
  let release, selected;
  const wait = new Promise(resolve => { release = resolve; });
  ctx.typertGateway.invoke = async input => {
    calls.push(input.method);
    if (input.method === 'selectModel') { await wait; selected = input.args.request.model; return { selected: input.args.request }; }
    if (input.method === 'events') return { records: [], hasMore: false };
    if (input.method === 'prompt') throw new Error('native rejected prompt');
    return { items: [] };
  };
  const setting = bridge.rpc('selectModel', { sessionId: 's', selection: { provider: 'p', model: 'm' } });
  const prompt = bridge.rpc('prompt', { sessionId: 's', requestId: 'r', text: 'hello' });
  const rejected = assert.rejects(prompt, /native rejected/);
  await Promise.resolve(); assert.equal((await bridge.rpc('updateStatus')).idle, false);
  assert.deepEqual(calls, ['selectModel']); release(); await setting; await rejected;
  assert.deepEqual(calls, ['selectModel','prompt']); assert.equal(selected, 'm');
});

test('uploads use native session-scoped receipts and accept the declared 20 MiB limit', async t => {
  const { bridge, ctx } = fixture(); t.after(() => bridge.close());
  const seen = [];
  ctx.fileUploads = {
    async uploadStream(request) {
      let size = 0; for await (const chunk of request.data) size += chunk.length;
      seen.push({ sessionId: request.sessionId, size, name: request.name });
      return { receiptId: 'file-receipt', file: { attachmentId: 'sha256:' + '0'.repeat(64), bytes: size, name: request.name } };
    },
    async uploadImageStream(request) {
      assert.equal(request.mediaType, 'image/png'); assert.equal(request.sessionId, 's');
      return { receiptId: 'image-receipt', image: imageRef() };
    },
  };
  const data = Buffer.alloc(20 * 1024 * 1024).toString('base64');
  const uploaded = await bridge.rpc('upload', { sessionId: 's', data, name: '../test.bin' });
  assert.equal(uploaded.receiptId, 'file-receipt');
  assert.deepEqual(seen, [{ sessionId: 's', size: 20*1024*1024, name: 'test.bin' }]);
  assert.equal((await bridge.rpc('upload', { sessionId: 's', data: PNG.toString('base64'), mediaType: 'image/png' })).receiptId, 'image-receipt');
  assert.equal((await bridge.rpc('status')).capabilities.includes('files.upload'), true);
  await assert.rejects(bridge.rpc('upload', { sessionId: 's', data: data + 'AAAA' }), /oversized/);
});


test('native plan selection precedes prompt, restores pending mode and skips accepted replay', async t => {
  const { bridge, ctx, calls } = fixture(); t.after(() => bridge.close());
  const agent = { session: {} }; let active = false; const selections = [];
  ctx.get = name => name === 'planMode' ? {
    set(actual, value) { assert.equal(actual, agent); selections.push(value); active = value; },
    get() { return { active: false, pending: active }; },
  } : undefined;
  ctx.agents = new Map([['s', agent]]);
  ctx.sessionController = { async resolveAgent() { return { agent }; } };
  const realRpc = bridge.rpc.bind(bridge); let accepted = false;
  bridge.rpc = async (method, params, signal) => method === 'receipt' ? { accepted } : realRpc(method, params, signal);
  assert.ok((await bridge.rpc('status')).capabilities.includes('session.plan'));
  await bridge.rpc('prompt', { sessionId: 's', requestId: 'p', text: 'Plan', collaborationMode: 'plan' });
  assert.deepEqual(selections, [true]);
  assert.equal(calls.at(-1).args.request.mode, 'queue');
  assert.deepEqual((await bridge.rpc('snapshot', { sessionId: 's' })).plan, { active: false, pending: true });
  accepted = true;
  await bridge.rpc('prompt', { sessionId: 's', requestId: 'p', text: 'Plan', collaborationMode: 'default' });
  assert.deepEqual(selections, [true]);
  accepted = false;
  await bridge.rpc('prompt', { sessionId: 's', requestId: 'q', text: 'Act', collaborationMode: 'default' });
  assert.deepEqual(selections, [true, false]);
});

test('missing native plan service never admits a plan prompt', async t => {
  const { bridge, calls } = fixture(); t.after(() => bridge.close());
  assert.ok(!(await bridge.rpc('status')).capabilities.includes('session.plan'));
  const original = bridge.rpc.bind(bridge);
  bridge.rpc = (method, params, signal) => method === 'receipt' ? Promise.resolve({ accepted: false }) : original(method, params, signal);
  await assert.rejects(bridge.rpc('prompt', { sessionId: 's', requestId: 'p', text: 'Plan', collaborationMode: 'plan' }), { code: 'unsupported', certainty: 'rejected' });
  assert.ok(!calls.some(call => call.method === 'prompt'));
});

test('plan review preserves native choices and rejects forged or repeated answers', async t => {
  const { bridge } = fixture(); t.after(() => bridge.close());
  bridge.watch('s'); await bridge.poll(null, 0);
  const questions = [{ id: 'review', question: 'Approve?', detail: '# Plan\nDo work',
    options: [{ label: 'Approve' }, { label: 'Keep planning' }], intent: { kind: 'plan-review', approve: 'Approve' } }];
  const result = bridge.questions({ agent: { session: { id: 's' } }, questions }, () => assert.fail('unexpected delegation'));
  const [key] = bridge.pending.keys();
  assert.deepEqual(bridge.pending.get(key).value.questions, questions);
  assert.throws(() => bridge.respond({ key, answers: [{ id: 'review', selected: [], custom: 'approve' }] }), { code: 'invalid_decision' });
  assert.throws(() => bridge.respond({ key, answers: [{ id: 'review', selected: ['Always'] }] }), { code: 'invalid_decision' });
  const answers = [{ id: 'review', selected: ['Approve'] }];
  bridge.respond({ key, answers }); assert.deepEqual(await result, { answers });
  assert.throws(() => bridge.respond({ key, answers }), { code: 'stale_approval' });
});

test('ordinary questions require all current ids and support a choice and free text', async t => {
  const { bridge } = fixture(); t.after(() => bridge.close());
  bridge.watch('s'); await bridge.poll(null, 0);
  const questions = [{ id: 'choice', question: 'Which?', options: [{ label: 'A' }] }, { id: 'text', question: 'Explain' }];
  const result = bridge.questions({ agent: { session: { id: 's' } }, questions }, () => assert.fail());
  const [key] = bridge.pending.keys();
  assert.throws(() => bridge.respond({ key, answers: [{ id: 'choice', selected: ['A'] }] }), { code: 'invalid_decision' });
  const answers = [{ id: 'choice', selected: ['A'] }, { id: 'text', selected: [], custom: 'Details' }];
  bridge.respond({ key, answers }); assert.deepEqual(await result, { answers });
  assert.equal(await bridge.questions({ agent: { session: { id: 's' } }, questions: [{ ...questions[0], multiSelect: true }] }, () => 'native'), 'native');
});

test('timed questions release their claim on lost lease and invalidate old replies', async t => {
  const { bridge, ctx, advance } = fixture(); t.after(() => bridge.close());
  let released = false;
  ctx.get = () => ({ async *attachWait(agent, callId, signal) {
    assert.equal(callId, 'call'); yield 1000;
    await new Promise(resolve => signal.aborted ? resolve() : signal.addEventListener('abort', resolve, { once: true }));
    released = true;
  } });
  bridge.watch('s'); await bridge.poll(null, 0);
  const request = { agent: { session: { id: 's' } }, questions: [{ id: 'q', question: 'Why?' }], wait: { timed: true, callId: 'call' } };
  const result = bridge.questions(request, () => 'native'); const [key] = bridge.pending.keys();
  await new Promise(resolve => setImmediate(resolve));
  advance(101); bridge.expireApprovals(); assert.equal(await result, 'native');
  await new Promise(resolve => setImmediate(resolve)); assert.equal(released, true);
  assert.throws(() => bridge.respond({ key, answers: [{ id: 'q', selected: [], custom: 'late' }] }), { code: 'stale_approval' });
});

test('aborted questions reject and a missing timed claim delegates', async t => {
  const { bridge, ctx } = fixture(); t.after(() => bridge.close());
  bridge.watch('s'); await bridge.poll(null, 0);
  const abort = new AbortController();
  const request = { agent: { session: { id: 's' } }, questions: [{ id: 'q', question: 'Why?' }], signal: abort.signal };
  const result = bridge.questions(request, () => assert.fail());
  const rejected = assert.rejects(result, { code: 'ASK_ABORTED' }); abort.abort(); await rejected;
  ctx.get = () => ({ async *attachWait() {} });
  assert.equal(await bridge.questions({ ...request, signal: undefined, wait: { timed: true, callId: 'gone' } }, () => 'native'), 'native');
  assert.equal(bridge.pending.size, 0);
});

test('cold plan projection uses pending as a toggle flag, not the desired mode', async t => {
  const { bridge, ctx } = fixture(); t.after(() => bridge.close());
  for (const active of [false, true]) for (const pending of [false, true]) {
    ctx.typertGateway.stream = async () => (async function* () {
      yield { type: 'snapshot', records: [], cursor: 7, projections: { values: { plan: { active, pending } } } };
    })();
    const actual = (await bridge.rpc('snapshot', { sessionId: 's' })).plan;
    assert.deepEqual(actual, { active, ...(pending ? { pending: !active } : {}) });
  }
});

test('catalog shows native and bridge-owned waiting kinds without opening history', async t => {
  const { bridge, ctx, calls } = fixture(); t.after(() => bridge.close());
  ctx.typertGateway.invoke = async input => { calls.push(input); return { items: [{ sessionId: 's', running: false }] }; };
  ctx.typertGateway.getPendingRemoteEvents = () => [{ event: 'user-questions/request', agentId: 's' }];
  assert.deepEqual((await bridge.rpc('sessions')).items[0].pendingInput, ['question']);
  ctx.typertGateway.getPendingRemoteEvents = () => [];
  bridge.watch('s'); await bridge.poll(null, 0);
  const pending = bridge.approval({ agent: { session: { id: 's' } }, toolName: 'bash' }, () => 'rejected');
  assert.deepEqual((await bridge.rpc('sessions')).items[0].pendingInput, ['approval']);
  bridge.close(); await pending;
  assert.ok(calls.every(call => call.method === 'list'));
});
