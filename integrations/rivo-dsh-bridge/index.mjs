/** Local adapter for the existing DSH Host; never launches or owns an Agent runtime. */
import http from 'node:http';
import { mkdir, chmod, lstat, unlink } from 'node:fs/promises';
import { dirname, join, isAbsolute, basename } from 'node:path';
import { homedir } from 'node:os';
import { randomUUID, createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';

export const name = 'rivo-dsh-bridge';
export const inject = ['sessionController', 'typertGateway', 'connection', 'workspaceRegistry', 'agents', 'fileUploads', 'fs'];
const ALLOWED_OUTCOMES = new Set(['allowed-once', 'rejected', 'cancelled']);
const MAX_BODY = 64 * 1024 * 1024;
const MAX_FILE = 20 * 1024 * 1024;
const MAX_IMAGES = 40 * 1024 * 1024;
const IMAGE_TYPES = new Set(['image/png', 'image/jpeg', 'image/webp', 'image/gif']);
const MAX_EVENTS = 512;
const MAX_EVENT_BYTES = 4 * 1024 * 1024;
const LOCAL_REJECTIONS = new Set(['invalid_argument', 'unsupported', 'invalid_decision', 'stale_approval', 'body_too_large', 'turn_changed']);
function failure(code, message) {
  return Object.assign(new Error(message), { code,
    ...(LOCAL_REJECTIONS.has(code) ? { certainty: 'rejected' } : {}) });
}
function withCertainty(error, certainty) {
  return Object.assign(new Error(error?.message ?? String(error), { cause: error }), {
    code: error?.code ?? 'bridge_error', certainty,
  });
}
function nativePromptRejected(error) {
  // These exact rc.2 Remote preflight failures precede commands.prompt. Codes
  // alone do not establish a phase; unknown messages retain unknown acceptance.
  return error?.code === 'gateway/bad-request'
      && error.message === 'prompt content must include non-whitespace text or an attachment'
    || error?.code === 'session/invalid-time-zone'
      && error.message === 'clientTimeZone must be UTC or a valid IANA Area/Location name';
}
function nativeSelectionRejected(error) {
  // requireModel emits this exact refusal before model.select. The same code
  // also wraps arbitrary failures after selection, so other messages stay unknown.
  return error?.code === 'session/model-unavailable'
    && error.message === 'Select an available model before sending a message.';
}
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
function decodeBase64(value, limit = MAX_FILE, empty = false) {
  if (typeof value !== 'string' || value.length > Math.ceil(limit / 3) * 4
      || value.length % 4 !== 0 || /[^A-Za-z0-9+/=]/.test(value)) {
    throw failure('invalid_argument', 'Invalid or oversized base64 content');
  }
  const data = Buffer.from(value, 'base64');
  if ((!empty && data.length === 0) || data.length > limit || data.toString('base64') !== value) {
    throw failure('invalid_argument', 'Invalid or oversized attachment');
  }
  return data;
}
function displayName(value, fallback) {
  return typeof value === 'string' && value ? basename(value.replaceAll('\\', '/')).replace(/[\x00-\x1f\x7f]/g, '').slice(0,255) || fallback : fallback;
}
function blocksOf(event) {
  if (!['user/message', 'assistant/message', 'tool/result'].includes(event?.type)) return [];
  const message = event.type === 'user/message' ? event.data : event.data?.message;
  return Array.isArray(message?.content) ? message.content : [];
}
function attachmentRef(ref) {
  return record(ref) && /^sha256:[a-f0-9]{64}$/.test(ref.attachmentId)
    && IMAGE_TYPES.has(ref.mediaType) && Number.isSafeInteger(ref.bytes) && ref.bytes > 0 && ref.bytes <= MAX_FILE
    && Number.isSafeInteger(ref.width) && ref.width > 0 && ref.width <= 65535
    && Number.isSafeInteger(ref.height) && ref.height > 0 && ref.height <= 65535 && ref.width * ref.height <= 80_000_000;
}
// The Host has no Markdown parser service/dependency. Locate destinations without
// rendering HTML: first exclude literal blocks/spans, then scan balanced links
// and resolve reference definitions. Every shared definition is replaced once.
function markdownDestinations(text) {
  const hidden = new Uint8Array(text.length), definitions = new Map(), found = new Map();
  const lines = [...text.matchAll(/[^\n]*(?:\n|$)/g)].filter(line => line[0]);
  const normalize = label => label.trim().replace(/\s+/g, ' ').toUpperCase();
  const escaped = index => { let count = 0; while (index > 0 && text[--index] === '\\') count++; return count % 2 === 1; };
  const whitespace = index => {
    let newlines = 0;
    while (index < text.length && /[ \t\r\n]/.test(text[index])) {
      if (text[index] === '\n' && ++newlines > 1) break;
      index++;
    }
    return index;
  };
  const destination = index => {
    const angled = text[index] === '<', start = index + (angled ? 1 : 0);
    let p = start, depth = 0;
    for (; p < text.length; p++) {
      const char = text[p];
      if (char === '\\' && p + 1 < text.length && /[!"#$%&'()*+,\-./:;<=>?@[\]\\^_`{|}~]/.test(text[p + 1])) { p++; continue; }
      if (angled) {
        if (char === '>') return { start, end: p, after: p + 1 };
        if (char === '<' || char === '\n' || char === '\r') return null;
      } else {
        if (/\s/.test(char) || char.charCodeAt(0) < 32) break;
        if (char === '(' && ++depth > 32) return null;
        if (char === ')' && depth-- === 0) break;
      }
    }
    return !angled && depth <= 0 && p > start ? { start, end: p, after: p } : null;
  };
  const titleEnd = index => {
    const opener = text[index], closer = opener === '(' ? ')' : opener;
    if (!['"', "'", '('].includes(opener)) return null;
    for (let p = index + 1; p < text.length; p++) {
      if (text[p] === '\\') { p++; continue; }
      if (text[p] === closer) return p + 1;
      if (text[p] === '\n' && /^[ \t\r]*\n/.test(text.slice(p + 1))) return null;
      if (opener === '(' && text[p] === '(') return null;
    }
    return null;
  };
  let fence, html;
  for (const line of lines) {
    // Quoted/list fences are literal too; positions remain in the original text.
    const body = line[0].replace(/^(?: {0,3}>[ \t]?)+/, '');
    const fencedBody = body.replace(/^ {0,3}(?:[-+*]|\d+[.)])[ \t]+/, '');
    const marker = /^ {0,3}(`{3,}|~{3,})([^\n]*)/.exec(fencedBody);
    if (fence) {
      hidden.fill(1, line.index, line.index + line[0].length);
      if (marker && marker[1][0] === fence.char && marker[1].length >= fence.length && !marker[2].trim()) fence = undefined;
    } else if (html) {
      hidden.fill(1, line.index, line.index + line[0].length);
      if (html.test(body)) html = undefined;
    } else if (marker && (marker[1][0] !== '`' || !marker[2].includes('`'))) {
      fence = { char: marker[1][0], length: marker[1].length };
      hidden.fill(1, line.index, line.index + line[0].length);
    } else if (/^(?: {4}|\t)/.test(body)) {
      hidden.fill(1, line.index, line.index + line[0].length);
    } else {
      const tag = /^ {0,3}<(script|pre|style|textarea)(?:[\s>]|$)/i.exec(body);
      if (tag || /^ {0,3}<!--/.test(body)) {
        const end = tag ? new RegExp(`</${tag[1]}\\s*>`, 'i') : /-->/;
        hidden.fill(1, line.index, line.index + line[0].length);
        if (!end.test(body)) html = end;
      }
    }
  }
  for (const line of lines) {
    if (hidden[line.index]) continue;
    const prefix = /^(?: {0,3}>[ \t]?)* {0,3}\[((?:\\.|[^\[\]\\\n]){1,999})\]:[ \t]*/.exec(line[0]);
    if (!prefix) continue;
    const value = destination(whitespace(line.index + prefix[0].length));
    if (!value) continue;
    let end = value.after, next = whitespace(end);
    const title = next > end ? titleEnd(next) : null;
    if (title !== null) end = title;
    // A definition must occupy its own line(s); reject trailing prose.
    const tail = /^[ \t\r]*(?:\n|$)/.exec(text.slice(end));
    if (!tail) continue;
    end += tail[0].length;
    const key = normalize(prefix[1]);
    if (key && !definitions.has(key)) definitions.set(key, value);
    hidden.fill(1, line.index, end);
  }
  // CommonMark code spans use matching backtick runs, including multiline spans.
  // Index runs once so unmatched backticks cannot cause quadratic rescans.
  const ticks = [], byLength = new Map();
  for (const match of text.matchAll(/`+/g)) {
    if (hidden[match.index]) continue;
    const tick = { start: match.index, end: match.index + match[0].length };
    ticks.push(tick);
    const entries = byLength.get(match[0].length) ?? [];
    entries.push(tick); byLength.set(match[0].length, entries);
  }
  let covered = -1;
  const positions = new Map();
  for (const tick of ticks) {
    const length = tick.end - tick.start, entries = byLength.get(length);
    let position = positions.get(length) ?? 0;
    while (entries[position] && entries[position].start <= tick.start) position++;
    positions.set(length, position);
    if (tick.start < covered || escaped(tick.start) || !entries[position]) continue;
    const close = entries[position];
    // A code span cannot cross a block boundary.
    if (hidden.subarray(tick.end, close.start).some(Boolean) || /\n[ \t\r]*\n/.test(text.slice(tick.end, close.start))) continue;
    hidden.fill(2, tick.start, close.end); covered = close.end;
  }
  const add = (value, image, referenceUse) => {
    const key = `${value.start}:${value.end}`;
    const existing = found.get(key);
    const item = existing ?? { ...value, image };
    item.image ||= image;
    if (referenceUse) (item.referenceUses ??= []).push(referenceUse);
    found.set(key, item);
  };
  const brackets = [];
  for (let i = 0; i < text.length; i++) {
    if (hidden[i]) { if (hidden[i] === 1) brackets.length = 0; continue; }
    if (text[i] === '\\') { i++; continue; }
    if (text[i] === '[') {
      brackets.push({ start: i, image: text[i - 1] === '!' && !escaped(i - 1) });
      continue;
    }
    if (text[i] !== ']' || !brackets.length) continue;
    const opener = brackets.pop(), label = text.slice(opener.start + 1, i);
    if (text[i + 1] === '(') {
      const value = destination(whitespace(i + 2));
      if (value) {
        let end = whitespace(value.after);
        if (end > value.after && text[end] !== ')') {
          const title = titleEnd(end);
          if (title !== null) end = whitespace(title);
        }
        if (text[end] === ')') { add(value, opener.image); i = end; continue; }
      }
    }
    let reference = label, end = i;
    if (text[i + 1] === '[') {
      const suffix = /^\[((?:\\.|[^\[\]\\]){0,999})\]/.exec(text.slice(i + 1));
      if (suffix) { reference = suffix[1] || label; end = i + suffix[0].length; }
    }
    const definition = definitions.get(normalize(reference));
    if (definition) {
      add(definition, opener.image, { start: opener.start - (opener.image ? 1 : 0), end: end + 1, label, image: opener.image });
      i = end;
    }
  }
  return [...found.values()].sort((a, b) => a.start - b.start);
}
function mediaCandidates(event) {
  const result = [];
  blocksOf(event).forEach((block, blockIndex) => {
    if (block?.type === 'image' && attachmentRef(block.attachment)) {
      result.push({ eventSeq: event.seq, blockIndex, imageIndex: 0, kind: 'attachment',
        mediaType: block.attachment.mediaType, name: displayName(block.attachment.name, 'image'), attachment: block.attachment });
    } else if (block?.type === 'text' && typeof block.text === 'string') {
      let imageIndex = 0;
      for (const match of markdownDestinations(block.text)) {
        const destination = block.text.slice(match.start, match.end);
        let source = destination.replace(/\\([!"#$%&'()*+,\-./:;<=>?@[\]\\^_`{|}~])/g, '$1');
        if (!match.image && !/\.(png|jpe?g|webp|gif|bmp|ico|svg|avif)(?:[?#].*)?$/i.test(source)) continue;
        if (/^file:/i.test(source)) {
          try { source = fileURLToPath(source); } catch { continue; }
        } else {
          if (/^[a-z][a-z\d+.-]*:/i.test(source) && !/^[a-z]:[\\/]/i.test(source)) continue;
          try { source = decodeURIComponent(source.split(/[?#]/,1)[0]); } catch { continue; }
        }
        if (!source || source.length > 4096 || /[\x00-\x1f\x7f]/.test(source)) continue;
        result.push({ eventSeq: event.seq, blockIndex, imageIndex: imageIndex++, kind: 'markdown',
          start: [...block.text.slice(0,match.start)].length, end: [...block.text.slice(0,match.end)].length,
          ...(match.referenceUses === undefined ? {} : { referenceUses: match.referenceUses.map(use => ({ ...use,
            start: [...block.text.slice(0,use.start)].length, end: [...block.text.slice(0,use.end)].length })) }),
          name: displayName(source, 'image'), source });
      }
    }
  });
  return result;
}
function mediaMetadata(records) {
  return (records ?? []).flatMap(row => mediaCandidates(row.event).map(({ source, attachment, ...value }) => value));
}
function imageInfo(data, fileName) {
  // The Relay fully decodes these file formats, including bounded, non-executing
  // SVG validation and raster preview. Never parse SVG or invoke a renderer here.
  if (fileName !== undefined) {
    if (/\.svg$/i.test(fileName)) return { mediaType: 'image/svg+xml' };
    if (data.length >= 26 && data.toString('ascii',0,2) === 'BM') return { mediaType: 'image/bmp' };
    if (data.length >= 6 && data.readUInt32LE(0) === 65536 && data.readUInt16LE(4) > 0) return { mediaType: 'image/x-icon' };
    if (data.length >= 16 && data.toString('ascii',4,8) === 'ftyp') {
      const brands = [data.toString('ascii',8,12)];
      for (let i = 16; i + 4 <= Math.min(data.length,data.readUInt32BE(0),128); i += 4) brands.push(data.toString('ascii',i,i+4));
      if (brands.some(brand => brand === 'avif' || brand === 'avis')) return { mediaType: 'image/avif' };
    }
  }
  let mediaType, width, height;
  if (data.length >= 24 && data.subarray(0,8).equals(Buffer.from([137,80,78,71,13,10,26,10])) && data.toString('ascii',12,16) === 'IHDR') {
    mediaType = 'image/png'; width = data.readUInt32BE(16); height = data.readUInt32BE(20);
  } else if (data.length >= 10 && /^GIF8[79]a$/.test(data.toString('ascii',0,6))) {
    mediaType = 'image/gif'; width = data.readUInt16LE(6); height = data.readUInt16LE(8);
  } else if (data.length >= 20 && data.toString('ascii',0,4) === 'RIFF' && data.toString('ascii',8,12) === 'WEBP') {
    mediaType = 'image/webp';
    const kind = data.toString('ascii',12,16);
    if (kind === 'VP8X' && data.length >= 30) { width = 1 + data.readUIntLE(24,3); height = 1 + data.readUIntLE(27,3); }
    else if (kind === 'VP8 ' && data.length >= 30 && data.subarray(23,26).equals(Buffer.from([157,1,42]))) {
      width = data.readUInt16LE(26) & 0x3fff; height = data.readUInt16LE(28) & 0x3fff;
    } else if (kind === 'VP8L' && data.length >= 25 && data[20] === 47) {
      const bits = data.readUInt32LE(21); width = (bits & 0x3fff) + 1; height = ((bits >>> 14) & 0x3fff) + 1;
    }
  } else if (data.length >= 4 && data[0] === 255 && data[1] === 216) {
    mediaType = 'image/jpeg';
    for (let p = 2; p + 4 <= data.length;) {
      if (data[p++] !== 255) break;
      while (data[p] === 255) p++;
      const marker = data[p++];
      if (marker === 217 || marker === 218) break;
      if (marker === 1 || (marker >= 208 && marker <= 215)) continue;
      if (p + 2 > data.length) break;
      const length = data.readUInt16BE(p);
      if (length < 2 || p + length > data.length) break;
      if ([192,193,194,195,197,198,199,201,202,203,205,206,207].includes(marker) && length >= 7) {
        height = data.readUInt16BE(p+3); width = data.readUInt16BE(p+5); break;
      }
      p += length;
    }
  }
  if (!mediaType || !width || !height || width > 65535 || height > 65535 || width * height > 80_000_000) {
    throw failure('invalid_media', 'Source is not a supported bounded raster image');
  }
  return { mediaType, width, height };
}

export class Bridge {
  constructor(ctx, { leaseMs = 30000, clock = Date.now } = {}) {
    this.ctx = ctx; this.clock = clock; this.leaseMs = leaseMs;
    this.generation = randomUUID(); this.cursor = 0; this.events = []; this.eventBytes = 0;
    this.updateRevision = 0;
    this.pending = new Map(); this.watched = new Map(); this.waiters = new Set();
    this.operations = new Map();
    this.lastPoll = -Infinity; this.closed = false;
    this.lifetime = new AbortController();
    this.peer = ctx.connection.createPeer({ principal: { kind: 'owner' }, interactions: 'none' });
    this.disposers = [
      ctx.on('session/event', (session, event) => {
        // Unwatched inbox changes also invalidate an update-idle observation.
        this.updateRevision++;
        if (this.isWatched(session.id)) this.publish({ type: 'session', sessionId: session.id, event });
      }, { global: true }),
      ctx.on('agent/assistant-stream', ({ agent, frame }) => {
        if (this.isWatched(agent.session.id)) this.publish({ type: 'assistant-stream', sessionId: agent.session.id, frame });
      }, { global: true }),
      ctx.on('api-session/status', (sessionId, running) => this.publish({ type: 'status', sessionId, running }), { global: true }),
      ctx.on('api-session/added', () => this.publish({ type: 'catalog' }), { global: true }),
      ctx.on('api-session/removed', () => this.publish({ type: 'catalog' }), { global: true }),
      ctx.on('approval/request', (request, next) => this.approval(request, next), { global: true, prepend: true }),
      ctx.on('user-questions/request', (request, next) => this.questions(request, next), { global: true, prepend: true }),
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
  async serialize(sessionId, signal, operation) {
    const previous = this.operations.get(sessionId) ?? Promise.resolve();
    const task = previous.catch(() => {}).then(() => {
      try {
        signal.throwIfAborted();
        if (this.closed) throw failure('disconnected', 'Bridge is closed');
      } catch (error) { throw withCertainty(error, 'rejected'); }
      return operation();
    });
    this.operations.set(sessionId, task);
    try { return await task; }
    finally { if (this.operations.get(sessionId) === task) this.operations.delete(sessionId); }
  }
  async selectModel(sessionId, value, signal) {
    let dispatched = false;
    try {
      if (!record(value)) throw failure('invalid_argument', 'selection must be an object');
      let provider = value.provider, model = value.model;
      if (provider === undefined && model === undefined) {
        const resolved = await this.ctx.sessionController.resolveAgent(sessionId);
        if (resolved.error) throw failure(resolved.error.code ?? 'session_unavailable', resolved.error.message);
        const current = resolved.agent?.model?.current();
        if (!current) throw failure('model_unavailable', 'Session has no current model');
        provider = current.provider; model = current.model;
      }
      const selection = { provider: string(provider, 'provider'), model: string(model, 'model'),
        ...(value.reasoningEffort == null ? {} : { reasoningEffort: string(value.reasoningEffort, 'reasoningEffort') }) };
      signal.throwIfAborted();
      // current() may be an unrecorded default. Native selectModel owns durable
      // selection and deduplication even when the effective value is unchanged.
      dispatched = true;
      return await this.native('selectModel', { sessionId, ...selection }, signal);
    } catch (error) {
      throw withCertainty(error, !dispatched || nativeSelectionRejected(error) ? 'rejected' : 'unknown');
    }
  }
  promptContent(params) {
    const input = params.content === undefined ? [{ type: 'text', text: params.text }] : params.content;
    if (!Array.isArray(input) || input.length === 0 || input.length > 100) throw failure('invalid_argument', 'Invalid prompt content');
    let imageBytes = 0, images = 0, meaningful = false, textBytes = 0;
    const content = input.map(part => {
      if (!record(part)) throw failure('invalid_argument', 'Invalid prompt part');
      if (part.type === 'text') {
        if (typeof part.text !== 'string') throw failure('invalid_argument', 'Invalid prompt text');
        textBytes += Buffer.byteLength(part.text); meaningful ||= !!part.text.trim();
        if (textBytes > 512 * 1024) throw failure('invalid_argument', 'Prompt text exceeds limit');
        return { type: 'text', text: part.text };
      }
      meaningful = true;
      if (part.type === 'file') return { type: 'file', receiptId: string(part.receiptId, 'receiptId') };
      if (part.type !== 'image' || !IMAGE_TYPES.has(part.mediaType)) throw failure('invalid_argument', 'Unsupported prompt content');
      imageBytes += decodeBase64(part.data).length;
      if (++images > 20 || imageBytes > MAX_IMAGES) throw failure('invalid_argument', 'Prompt image limits exceeded');
      return { type: 'image', mediaType: part.mediaType, data: part.data,
        ...(part.name === undefined ? {} : { name: displayName(string(part.name, 'name'), 'image') }) };
    });
    if (!meaningful) throw failure('invalid_argument', 'Prompt requires text or an attachment');
    return content;
  }
  async media(params, signal) {
    const sessionId = string(params.sessionId, 'sessionId');
    const eventSeq = integer(params.eventSeq, 'eventSeq');
    const blockIndex = integer(params.blockIndex, 'blockIndex');
    const imageIndex = params.imageIndex === undefined ? 0 : integer(params.imageIndex, 'imageIndex');
    // Only an exact locator is accepted. Neither paths nor attachment references
    // supplied by the caller confer authority to read a native file.
    if (Object.keys(params).some(key => !['sessionId','eventSeq','blockIndex','imageIndex'].includes(key))) {
      throw failure('invalid_argument', 'Media accepts an event locator only');
    }
    const page = await this.native('events', { address: { kind: 'session', sessionId }, throughSeq: eventSeq, maxMessages: 1 }, signal);
    const event = page?.records?.find(row => row.event?.seq === eventSeq)?.event;
    const candidate = mediaCandidates(event).find(item => item.blockIndex === blockIndex && item.imageIndex === imageIndex);
    if (!candidate) throw failure('media_not_found', 'No supported image at this event locator');
    let data;
    if (candidate.kind === 'attachment') {
      const value = await this.native('attachment', { sessionId, attachment: candidate.attachment }, signal);
      data = decodeBase64(value?.data);
      const ref = candidate.attachment;
      if (data.length !== ref.bytes || createHash('sha256').update(data).digest('hex') !== ref.attachmentId.slice(7)) {
        throw failure('invalid_media', 'Attachment integrity check failed');
      }
    } else {
      let invoke = (method, extra = {}) => this.ctx.typertGateway.invoke({ namespace: 'workspaceFiles', method,
        args: { workspaceFileScopeId: sessionId, path: candidate.source, ...extra }, peer: this.peer, signal });
      const fs = this.ctx.fs;
      if (fs) {
        // The native chat /api/file route resolves through this same provider.
        // In particular it follows a referenced symlink, unlike workspaceFiles.
        let cwd;
        if (!isAbsolute(candidate.source) && !/^[a-z]:[\\/]/i.test(candidate.source)) {
          const catalog = await this.native('list', {}, signal);
          cwd = catalog?.items?.find(row => row.sessionId === sessionId)?.cwd;
          if (typeof cwd !== 'string' || !cwd) throw failure('media_not_found', 'Session workspace is unavailable');
        }
        const target = await fs.resolve(candidate.source, { ...(cwd === undefined ? {} : { cwd }), signal });
        const stat = async () => {
          const info = await fs.stat(target, signal);
          if (info?.type !== 'file') throw failure('media_not_found', 'Referenced image file is unavailable');
          return { absolutePath: fs.processPath(target), version: info.version, bytes: info.size };
        };
        invoke = async (method, extra = {}) => {
          const info = await stat();
          if (method === 'stat') return info;
          const range = extra.options.range;
          const data = await fs.readByteRange(target, range, signal);
          return { ...info, offset: range.offset, data };
        };
      }
      const before = await invoke('stat');
      if (!record(before) || typeof before.version !== 'string' || !before.version
          || !Number.isSafeInteger(before.bytes) || before.bytes < 1 || before.bytes > MAX_FILE) {
        throw failure('invalid_media', 'Image size or version is unavailable or exceeds limit');
      }
      const chunks = [];
      for (let offset = 0; offset < before.bytes;) {
        signal.throwIfAborted();
        const length = Math.min(1024 * 1024, before.bytes - offset);
        const part = await invoke('readBytes', { options: { range: { offset, length } } });
        if (!(part?.data instanceof Uint8Array) || part.offset !== offset || part.version !== before.version
            || part.bytes !== before.bytes || part.data.byteLength !== length) {
          throw failure('media_changed', 'Image changed while being read');
        }
        chunks.push(Buffer.from(part.data)); offset += length;
      }
      const after = await invoke('stat');
      if (after.version !== before.version || after.bytes !== before.bytes || after.absolutePath !== before.absolutePath) {
        throw failure('media_changed', 'Image changed while being read');
      }
      data = Buffer.concat(chunks);
    }
    signal.throwIfAborted();
    if (this.closed) throw failure('disconnected', 'Bridge is closed');
    const info = imageInfo(data, candidate.kind === 'markdown' ? candidate.name : undefined);
    if (candidate.attachment && (info.mediaType !== candidate.attachment.mediaType || info.width !== candidate.attachment.width || info.height !== candidate.attachment.height)) {
      throw failure('invalid_media', 'Image dimensions or format differ from its reference');
    }
    return { generation: this.generation, ...info, size: data.length,
      sha256: createHash('sha256').update(data).digest('hex'), name: candidate.name, data: data.toString('base64') };
  }
  async updateStatus(signal) {
    const cursor = this.cursor, revision = this.updateRevision;
    let idle = false;
    try {
      signal.throwIfAborted();
      if (!this.closed && this.pending.size === 0 && this.operations.size === 0) {
        const catalog = await this.native('list', {}, signal);
        if (!record(catalog) || !Array.isArray(catalog.items)) throw failure('invalid_state', 'Missing session catalog');
        const ids = catalog.items.map(row => {
          if (!record(row) || typeof row.agentAvailable !== 'boolean' || typeof row.running !== 'boolean') {
            throw failure('invalid_state', 'Invalid session catalog row');
          }
          if (row.running) throw failure('busy', 'A session is running');
          return string(row.sessionId, 'sessionId');
        });
        if (new Set(ids).size !== ids.length) throw failure('invalid_state', 'Duplicate session identity');
        // agentAvailable comes from the current Agent registry, not cached projections.
        // Cold history has no live work; reading it can require an unrelated migration.
        const liveIds = ids.filter((_, index) => catalog.items[index].agentAvailable);
        const states = await Promise.all(liveIds.map(sessionId => this.native('state', { sessionId }, signal)));
        // Pending Remote events need not emit a session event. Recheck them after
        // all samples, including a question that appeared after its state was read.
        const pending = this.ctx.typertGateway.getPendingRemoteEvents();
        idle = Array.isArray(pending) && pending.length === 0 && states.every(state => record(state)
          && Number.isSafeInteger(state.cursor) && state.cursor >= -1
          && typeof state.active === 'boolean' && state.running === false
          && Array.isArray(state.pendingInput) && state.pendingInput.length === 0
          && (state.active ? Array.isArray(state.queue) && state.queue.length === 0 : state.queue === null));
      }
    } catch {
      // Unknown, cancelled, unsupported, or unavailable state never permits an update.
    }
    return { generation: this.generation, cursor: this.cursor,
      idle: idle && !signal.aborted && !this.closed && this.pending.size === 0 && this.operations.size === 0
        && this.cursor === cursor && this.updateRevision === revision };
  }
  async rpc(method, params = {}, signal = this.lifetime.signal) {
    if (this.closed) throw failure('disconnected', 'Bridge is closed');
    if (!record(params)) throw failure('invalid_argument', 'params must be an object');
    if (method === 'status') return { connected: true, generation: this.generation, protocol: 1, supportedRuntimeVersion: '0.2.2-rc.5',
      capabilities: ['media.read', 'session.models', 'session.settings', 'attachments.images',
        ...(typeof this.ctx.get?.('planMode')?.set === 'function' ? ['session.plan'] : []),
        ...(typeof this.ctx.fileUploads?.uploadStream === 'function' ? ['files.upload'] : [])],
      limits: { maxAttachmentBytes: MAX_FILE, maxPromptImageBytes: MAX_IMAGES, maxImages: 20, maxMediaBytes: MAX_FILE } };
    if (method === 'updateStatus') return this.updateStatus(signal);
    if (method === 'projects') {
      const registry = this.ctx.workspaceRegistry;
      return { items: registry.list().map(w => ({ workspaceId: w.id, path: w.path, title: w.title, sessionIds: [...w.sessionIds], createdAt: w.createdAt, updatedAt: w.updatedAt })),
        archivedSessionIds: [...registry.archivedSessionIds], pinnedSessionIds: [...registry.pinnedSessionIds] };
    }
    if (method === 'sessions') {
      const catalog = await this.native('list', {}, signal), membership = new Map();
      for (const workspace of this.ctx.workspaceRegistry.list()) {
        for (const id of workspace.sessionIds) membership.set(id, membership.has(id) ? null : workspace.id);
      }
      const waiting = new Map();
      const add = (id, kind) => { if (!waiting.has(id)) waiting.set(id, new Set()); waiting.get(id).add(kind); };
      for (const { event, agentId } of this.ctx.typertGateway.getPendingRemoteEvents()) {
        add(String(agentId), event === 'approval/request' ? 'approval' : event === 'user-questions/request' ? 'question' : 'other');
      }
      for (const { value } of this.pending.values()) {
        add(value.sessionId, value.kind === 'questions' && !value.questions[0]?.intent ? 'question' : 'approval');
      }
      return { ...catalog, generation: this.generation,
        items: catalog.items.map(item => ({ ...item, projectId: membership.get(item.sessionId) ?? null,
          pendingInput: [...(waiting.get(item.sessionId) ?? [])] })) };
    }
    if (method === 'models') return this.native('modelCatalog', {}, signal);
    if (method === 'permissionCatalog') {
      const service = this.ctx.get('permissionPresets');
      if (!service) throw failure('unsupported', 'DSH permission presets unavailable');
      return service.catalog();
    }
    if (method === 'respond') return this.respond(params);
    if (method === 'media') return this.media(params, signal);
    if (method === 'create') {
      const request = {};
      for (const key of ['workspaceId', 'cwd', 'sessionId', 'agentPreset']) if (params[key] !== undefined) request[key] = string(params[key], key);
      const result = await this.native('create', request, signal); this.watch(result.sessionId); return result;
    }
    if (method === 'createReceipt') {
      const sessionId = string(params.sessionId, 'sessionId'), workspaceId = string(params.workspaceId, 'workspaceId');
      // list identity/cwd come from the native live or persisted Session header,
      // not cached projections. Require Workspace membership after that read too:
      // create may persist a Session and then fail to attach it to its Workspace.
      const catalog = await this.native('list', {}, signal);
      signal.throwIfAborted();
      if (!Array.isArray(catalog?.items)) throw failure('invalid_state', 'Missing session catalog');
      const workspace = this.ctx.workspaceRegistry.get(workspaceId);
      const rows = catalog.items.filter(row => row.sessionId === sessionId);
      const created = !this.closed && rows.length === 1 && typeof rows[0].cwd === 'string'
        && workspace?.path === rows[0].cwd && Array.isArray(workspace.sessionIds)
        && workspace.sessionIds.includes(sessionId) && !this.ctx.workspaceRegistry.archivedSessionIds.includes(sessionId);
      return { generation: this.generation, created,
        ...(created ? { sessionId, workspaceId } : {}) };
    }
    const sessionId = string(params.sessionId, 'sessionId');
    this.watch(sessionId);
    if (method === 'selectModel') return this.serialize(sessionId, signal, () => this.selectModel(sessionId, params.selection, signal));
    if (method === 'upload') {
      const data = decodeBase64(params.data, MAX_FILE, true);
      if (params.mediaType !== undefined && !IMAGE_TYPES.has(params.mediaType)) throw failure('invalid_argument', 'Unsupported image media type');
      const uploads = this.ctx.fileUploads;
      if (!uploads?.uploadStream || (params.mediaType !== undefined && !uploads.uploadImageStream)) throw failure('unsupported', 'Native upload service unavailable');
      const name = params.name === undefined ? undefined : displayName(string(params.name, 'name'), 'attachment');
      return this.serialize(sessionId, signal, () => uploads[params.mediaType === undefined ? 'uploadStream' : 'uploadImageStream']({
        sessionId, data: (async function* () { yield data; })(), signal,
        ...(name === undefined ? {} : { name }), ...(params.mediaType === undefined ? {} : { mediaType: params.mediaType }) }));
    }
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
      let dispatched = false;
      try {
        const requestId = string(params.requestId, 'requestId');
        const content = this.promptContent(params);
        return await this.serialize(sessionId, signal, async () => {
          if (params.selection !== undefined || params.collaborationMode !== undefined) {
            const receipt = await this.rpc('receipt', { sessionId, requestId }, signal);
            if (receipt.accepted) return { accepted: true, requestId };
            if (params.selection !== undefined) await this.selectModel(sessionId, params.selection, signal);
            if (params.collaborationMode !== undefined) {
              if (!['default', 'plan'].includes(params.collaborationMode)) throw failure('invalid_argument', 'Invalid collaboration mode');
              const plan = this.ctx.get?.('planMode');
              if (typeof plan?.set !== 'function') throw failure('unsupported', 'Native plan mode unavailable');
              const resolved = await this.ctx.sessionController.resolveAgent(sessionId);
              if (resolved.error) throw failure(resolved.error.code, resolved.error.message);
              signal.throwIfAborted();
              plan.set(resolved.agent, params.collaborationMode === 'plan');
            }
          }
          signal.throwIfAborted();
          dispatched = true;
          const result = await this.native('prompt', { sessionId, requestId, mode: 'queue', content }, signal);
          if (result?.accepted !== true) throw failure('invalid_receipt', 'DSH returned no acceptance');
          return { accepted: true, requestId };
        });
      } catch (error) {
        // This certainty concerns input admission only. A selection made before
        // prompt can remain changed; no rollback or native error-code guesswork.
        throw withCertainty(error, !dispatched || nativePromptRejected(error) ? 'rejected' : 'unknown');
      }
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
      const selected = this.ctx.agents?.get(sessionId)?.model?.current() ?? page.projections?.values?.modelSelection?.next ?? null;
      const live = this.ctx.agents?.get(sessionId), projectedPlan = page.projections?.values?.plan;
      // The wire projection's pending is a change flag; the live service returns
      // the desired boolean. Normalize before presenting the shared settings.
      const plan = live && this.ctx.get?.('planMode')?.get ? this.ctx.get('planMode').get(live)
        : typeof projectedPlan?.active === 'boolean' ? { active: projectedPlan.active,
          ...(projectedPlan.pending === true ? { pending: !projectedPlan.active } : {}) } : null;
      return { generation: this.generation, cursor: readCursor, page, state, selected, plan,
        media: mediaMetadata(page.records),
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
  questions(request, next) {
    const sessionId = request.agent?.session?.id, questions = request.questions;
    const valid = Array.isArray(questions) && questions.length > 0 && questions.length <= 8
      && new Set(questions.map(q => q?.id)).size === questions.length
      && questions.every(q => record(q) && typeof q.id === 'string' && q.id.length > 0 && q.id.length <= 256
        && typeof q.question === 'string' && q.question.length <= 8192 && q.multiSelect !== true
        && (q.header === undefined || typeof q.header === 'string')
        && (q.detail === undefined || typeof q.detail === 'string' && q.detail.length <= 65536)
        && (q.intent === undefined || questions.length === 1 && ['approval', 'plan-review'].includes(q.intent?.kind))
        && (q.options === undefined || Array.isArray(q.options) && q.options.length <= 20
          && q.options.every(o => record(o) && typeof o.label === 'string' && o.label.length > 0 && o.label.length <= 4096)
          && new Set(q.options.map(o => o.label)).size === q.options.length));
    const timed = request.wait?.timed === true, service = this.ctx.get?.('userQuestions');
    if (!valid || !sessionId || this.closed || !this.isWatched(sessionId)
        || this.clock() - this.lastPoll > this.leaseMs || this.pending.size >= 64
        || timed && (typeof service?.attachWait !== 'function' || typeof request.wait.callId !== 'string')) return next();
    const key = `${this.generation}:${randomUUID()}`;
    const value = { key, sessionId, kind: 'questions', questions: JSON.parse(JSON.stringify(questions)) };
    return new Promise((resolve, reject) => {
      const claim = new AbortController();
      const abort = () => settle('aborted');
      const settle = outcome => {
        if (!this.pending.delete(key)) return false;
        claim.abort(); request.signal?.removeEventListener('abort', abort);
        this.publish({ type: 'approval-resolved', sessionId, key });
        if (outcome === 'delegate') Promise.resolve().then(next).then(resolve, reject);
        else if (outcome === 'aborted') reject(Object.assign(new Error('DSH question was aborted'), { code: 'ASK_ABORTED' }));
        else resolve(outcome);
        return true;
      };
      this.pending.set(key, { value, settle });
      request.signal?.addEventListener('abort', abort, { once: true });
      this.publish({ type: 'approval', sessionId, request: value });
      if (request.signal?.aborted) abort();
      else if (timed) (async () => {
        try {
          for await (const _ of service.attachWait(request.agent, request.wait.callId, claim.signal)) { /* Hold only this live question's native wait. */ }
          if (!claim.signal.aborted) settle('delegate');
        }
        catch { if (!claim.signal.aborted) settle('delegate'); }
      })();
    });
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
    const pending = this.pending.get(key);
    if (!pending || !key.startsWith(this.generation + ':') || this.clock() - this.lastPoll > this.leaseMs) {
      pending?.settle('delegate'); throw failure('stale_approval', 'Approval is no longer owned by this connection');
    }
    if (pending.value.kind === 'questions') {
      const questions = pending.value.questions, answers = params.answers;
      if (!Array.isArray(answers) || answers.length !== questions.length
          || new Set(answers.map(a => a?.id)).size !== questions.length) throw failure('invalid_decision', 'Answer every current question once');
      for (const question of questions) {
        const answer = answers.find(a => a?.id === question.id), labels = (question.options ?? []).map(o => o.label);
        if (!record(answer) || !Array.isArray(answer.selected) || answer.selected.length > 1
            || answer.selected.some(label => !labels.includes(label))
            || Object.keys(answer).some(key => !['id', 'selected', 'custom'].includes(key))
            || (answer.custom !== undefined && (typeof answer.custom !== 'string' || !answer.custom.trim() || answer.custom.length > 8192 || answer.selected.length > 0))
            || (answer.selected.length === 0 && answer.custom === undefined)
            || (question.intent !== undefined && (answer.custom !== undefined || answer.selected.length !== 1)))
          throw failure('invalid_decision', 'Invalid answer for current question');
      }
      pending.settle({ answers });
    } else {
      if (!ALLOWED_OUTCOMES.has(params.outcome)) throw failure('invalid_decision', 'Unsupported approval outcome');
      pending.settle(params.outcome);
    }
    return { accepted: true };
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
    let invoked = false;
    try {
      let result;
      const url = new URL(req.url, 'http://localhost');
      if (req.method === 'GET' && url.pathname === '/events') {
        invoked = true;
        result = await bridge.poll(url.searchParams.get('generation'), Number(url.searchParams.get('cursor')), signal);
      } else if (req.method === 'POST' && url.pathname === '/rpc') {
        let bytes = 0; const chunks = [];
        for await (const chunk of req) { bytes += chunk.length; if (bytes > MAX_BODY) throw failure('body_too_large', 'Request exceeds 64 MiB'); chunks.push(chunk); }
        const body = JSON.parse(Buffer.concat(chunks).toString('utf8'));
        if (!record(body)) throw failure('invalid_argument', 'RPC body must be an object');
        const method = string(body.method, 'method');
        invoked = true;
        result = await bridge.rpc(method, body.params ?? {}, signal);
      } else { res.writeHead(404); res.end(); return; }
      res.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' });
      res.end(JSON.stringify(req.method === 'GET' ? result : { result }));
    } catch (error) {
      if (!res.headersSent) res.writeHead(400, { 'content-type': 'application/json', 'cache-control': 'no-store' });
      const certainty = !invoked || error.certainty === 'rejected' ? 'rejected' : 'unknown';
      res.end(JSON.stringify({ error: { code: error.code ?? 'bridge_error', message: error.message,
        certainty, uncertain: certainty !== 'rejected' } }));
    }
  });
  server.maxConnections = 16; server.requestTimeout = 30000; server.headersTimeout = 10000;
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(socketPath, resolve); });
  let identity;
  try {
    identity = await lstat(socketPath);
    await chmod(socketPath, 0o600);
  } catch (error) {
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
    throw error;
  }
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
