import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createApi } from '../src/api.js';
const token = 'a'.repeat(64);
async function fixture(t, overrides = {}) {
  const calls = [];
  const relay = {
    status: () => ({ ready: true, state: 'ready' }),
    chats: async () => [{ id: '123@c.us', name: 'Test' }],
    messages: async id => [{ id: 'm1', text: 'Hello', fromMe: false, timestamp: 1 }],
    send: async (id, text) => { calls.push([id, text]); return { id: 'm2', text, fromMe: true }; },
    read: async id => { calls.push(['read', id]); },
    ...overrides
  };
  const server = createApi({ token, relay });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => { server.close(resolve); server.closeAllConnections(); }));
  return {
    calls,
    request: (path, options = {}) => fetch(`http://127.0.0.1:${server.address().port}${path}`, {
      ...options, headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', ...options.headers }
    })
  };
}
test('rejects missing or placeholder secrets at startup', () => {
  for (const value of ['', 'short', 'replace-with-a-random-token-at-least-32-characters']) assert.throws(() => createApi({ token: value, relay: {} }));
});
test('requires authentication even for status', async t => {
  const { request } = await fixture(t);
  for (const authorization of ['', 'Bearer bad']) assert.equal((await request('/api/status', { headers: { Authorization: authorization } })).status, 401);
});
test('returns status, chat list, history and read results', async t => {
  const { request, calls } = await fixture(t);
  assert.equal((await (await request('/api/status')).json()).state, 'ready');
  assert.equal((await (await request('/api/chats')).json()).chats[0].name, 'Test');
  assert.equal((await (await request('/api/chats/123%40c.us/messages')).json()).messages[0].text, 'Hello');
  assert.equal((await request('/api/chats/123%40c.us/read', { method: 'POST' })).status, 200);
  assert.deepEqual(calls, [['read', '123@c.us']]);
});
test('validates and sends text exactly once', async t => {
  const { request, calls } = await fixture(t);
  const response = await request('/api/chats/123%40c.us/messages', { method: 'POST', body: JSON.stringify({ text: ' Hello ' }) });
  assert.equal(response.status, 201);
  assert.equal((await response.json()).message.text, 'Hello');
  assert.deepEqual(calls, [['123@c.us', 'Hello']]);
});
test('rejects bad bodies, long text, oversized input and invalid IDs without sending', async t => {
  const { request, calls } = await fixture(t);
  for (const body of ['{', '{}', 'null', JSON.stringify({ text: '' }), JSON.stringify({ text: 'a'.repeat(4097) })]) {
    assert.equal((await request('/api/chats/123%40c.us/messages', { method: 'POST', body })).status, 400);
  }
  assert.equal((await request('/api/chats/123%40c.us/messages', { method: 'POST', body: JSON.stringify({ text: 'a'.repeat(21000) }) })).status, 413);
  assert.equal((await request('/api/chats/evil/messages')).status, 400);
  assert.deepEqual(calls, []);
});
test('reports pairing and disconnection without calling WhatsApp', async t => {
  const { request } = await fixture(t, { status: () => ({ ready: false, state: 'qr' }) });
  assert.equal((await request('/api/status')).status, 200);
  assert.equal((await request('/api/chats')).status, 503);
});
test('hides adapter failures', async t => {
  const { request } = await fixture(t, { chats: async () => { throw new Error('private data'); } });
  const response = await request('/api/chats');
  assert.equal(response.status, 502);
  assert.ok(!JSON.stringify(await response.json()).includes('private data'));
});
test('preserves Unicode split across transport chunks', async t => {
  const { request, calls } = await fixture(t);
  const encoded = Buffer.from(JSON.stringify({ text: 'Hello 👋 नमस्ते' }));
  const split = encoded.indexOf(Buffer.from('👋')) + 1;
  const stream = new ReadableStream({
    start(controller) {
      controller.enqueue(encoded.subarray(0, split));
      setTimeout(() => { controller.enqueue(encoded.subarray(split)); controller.close(); }, 5);
    }
  });
  assert.equal((await request('/api/chats/123%40c.us/messages', { method: 'POST', body: stream, duplex: 'half' })).status, 201);
  assert.deepEqual(calls, [['123@c.us', 'Hello 👋 नमस्ते']]);
});
