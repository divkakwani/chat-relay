import http from 'node:http';
import { timingSafeEqual } from 'node:crypto';
import { MAX_MEDIA_BYTES, validateMedia, problem as fail } from './media.js';
const chatIdPattern = /^[0-9A-Za-z_.:-]+@(c\.us|g\.us|lid)$/;
const messageIdPattern = /^[0-9A-Za-z_@.:-]{1,300}$/;
async function readJson(req, limit = 20000) {
  const chunks = []; let bytes = 0;
  for await (const chunk of req) {
    bytes += chunk.length;
    if (bytes > limit) throw fail(413, 'Request too large.');
    chunks.push(chunk);
  }
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { throw fail(400, 'Invalid JSON'); }
}
export function createApi({ token, relay }) {
  if (!token || token.length < 32 || token.startsWith('replace-')) throw new Error('Set RELAY_TOKEN to a random secret of at least 32 characters.');
  return http.createServer(async (req, res) => {
    const reply = (status, value) => {
      res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
      res.end(JSON.stringify(value));
    };
    const binary = value => {
      res.writeHead(200, { 'Content-Type': value.mimetype, 'Content-Length': value.bytes.length, 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'Content-Disposition': 'inline' });
      res.end(value.bytes);
    };
    try {
      const given = Buffer.from(req.headers.authorization || ''); const expected = Buffer.from(`Bearer ${token}`);
      if (given.length !== expected.length || !timingSafeEqual(given, expected)) return reply(401, { error: 'Invalid access token' });
      const url = new URL(req.url, 'http://localhost');
      if (req.method === 'GET' && url.pathname === '/api/status') return reply(200, { ...relay.status(), capabilities: { media: true, avatars: true, incomingCallEvents: true, callLinks: true, nativeCalling: false }, maxMediaBytes: MAX_MEDIA_BYTES });
      if (!relay.status().ready) throw fail(503, 'WhatsApp is not ready. Check the server terminal and linked devices.');
      if (req.method === 'GET' && url.pathname === '/api/chats') return reply(200, { chats: await relay.chats() });
      if (req.method === 'GET' && url.pathname === '/api/calls') return reply(200, { calls: relay.calls() });
      if (req.method === 'POST' && url.pathname === '/api/call-links') {
        const body = await readJson(req);
        if (!['voice', 'video'].includes(body?.type)) throw fail(400, 'Call type must be voice or video.');
        return reply(201, { url: await relay.callLink(body.type) });
      }
      const callMatch = /^\/api\/calls\/([A-Za-z0-9_.:-]{1,200})\/reject$/.exec(url.pathname);
      if (req.method === 'POST' && callMatch) { await relay.rejectCall(callMatch[1]); return reply(200, { ok: true }); }
      const match = /^\/api\/chats\/([^/]+)\/(messages|read|avatar|media)(?:\/([^/]+))?$/.exec(url.pathname);
      if (!match) throw fail(404, 'Not found');
      const id = decodeURIComponent(match[1]);
      if (!chatIdPattern.test(id)) throw fail(400, 'Invalid chat ID');
      if (match[3] && (match[2] !== 'media' || req.method !== 'GET')) throw fail(404, 'Not found');
      if (req.method === 'GET' && match[2] === 'messages') return reply(200, { messages: await relay.messages(id) });
      if (req.method === 'GET' && match[2] === 'avatar') {
        const photo = await relay.avatar(id);
        if (!photo) throw fail(404, 'Profile photo is unavailable or private.');
        return binary(photo);
      }
      if (req.method === 'GET' && match[2] === 'media' && match[3]) {
        const messageId = decodeURIComponent(match[3]);
        if (!messageIdPattern.test(messageId)) throw fail(400, 'Invalid message ID');
        return binary(await relay.media(id, messageId));
      }
      if (req.method === 'POST' && match[2] === 'read') { await relay.read(id); return reply(200, { ok: true }); }
      if (req.method === 'POST' && match[2] === 'media') {
        const body = validateMedia(await readJson(req, Math.ceil(MAX_MEDIA_BYTES * 4 / 3) + 12000));
        return reply(201, { message: await relay.sendMedia(id, body) });
      }
      if (req.method !== 'POST' || match[2] !== 'messages') throw fail(405, 'Method not allowed');
      const body = await readJson(req);
      if (!body || typeof body.text !== 'string' || !body.text.trim() || body.text.length > 4096) throw fail(400, 'Text must contain 1–4096 characters');
      reply(201, { message: await relay.send(id, body.text.trim()) });
    } catch (error) {
      reply(error.status || 502, { error: error.status ? error.message : 'WhatsApp request failed. Check the server connection.' });
    }
  });
}
