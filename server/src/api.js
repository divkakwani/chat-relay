import http from 'node:http';
import { timingSafeEqual } from 'node:crypto';

const chatIdPattern = /^[0-9A-Za-z_.:-]+@(c\.us|g\.us|lid)$/;
const fail = (status, message) => Object.assign(new Error(message), { status });
export function createApi({ token, relay }) {
  if (!token || token.length < 32 || token.startsWith('replace-')) {
    throw new Error('Set RELAY_TOKEN to a random secret of at least 32 characters.');
  }
  return http.createServer(async (req, res) => {
    const reply = (status, value) => {
      res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
      res.end(JSON.stringify(value));
    };
    try {
      const given = Buffer.from(req.headers.authorization || '');
      const expected = Buffer.from(`Bearer ${token}`);
      if (given.length !== expected.length || !timingSafeEqual(given, expected)) return reply(401, { error: 'Invalid access token' });
      const url = new URL(req.url, 'http://localhost');
      if (req.method === 'GET' && url.pathname === '/api/status') return reply(200, relay.status());
      if (!relay.status().ready) throw fail(503, 'WhatsApp is not ready. Check the server terminal and linked devices.');
      if (req.method === 'GET' && url.pathname === '/api/chats') return reply(200, { chats: await relay.chats() });
      const match = /^\/api\/chats\/([^/]+)\/(messages|read)$/.exec(url.pathname);
      if (!match) throw fail(404, 'Not found');
      const id = decodeURIComponent(match[1]);
      if (!chatIdPattern.test(id)) throw fail(400, 'Invalid chat ID');
      if (req.method === 'GET' && match[2] === 'messages') {
        return reply(200, { messages: await relay.messages(id) });
      }
      if (req.method === 'POST' && match[2] === 'read') {
        await relay.read(id);
        return reply(200, { ok: true });
      }
      if (req.method !== 'POST' || match[2] !== 'messages') throw fail(405, 'Method not allowed');
      const chunks = [];
      let bytes = 0;
      for await (const chunk of req) {
        bytes += chunk.length;
        if (bytes > 20000) throw fail(413, 'Message too large');
        chunks.push(chunk);
      }
      let body;
      try { body = JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { throw fail(400, 'Invalid JSON'); }
      if (!body || typeof body.text !== 'string' || !body.text.trim() || body.text.length > 4096) throw fail(400, 'Text must contain 1–4096 characters');
      const message = await relay.send(id, body.text.trim());
      reply(201, { message });
    } catch (error) {
      // Do not expose WhatsApp internals, message contents or credentials in errors.
      reply(error.status || 502, { error: error.status ? error.message : 'WhatsApp request failed. Check the server connection.' });
    }
  });
}
