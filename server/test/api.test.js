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

const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/l9sAAAAASUVORK5CYII=', 'base64');
test('authenticates binary media and profile photos; keeps them out of caches', async t => {
  const { request } = await fixture(t, { avatar: async () => ({ bytes: png, mimetype: 'image/png' }), media: async () => ({ bytes: png, mimetype: 'image/png' }) });
  for (const path of ['/api/chats/123%40c.us/avatar', '/api/chats/123%40c.us/media/false_123%40c.us_key']) {
    assert.equal((await request(path, { headers: { Authorization: '' } })).status, 401);
    const response = await request(path);
    assert.equal(response.status, 200); assert.equal(response.headers.get('content-type'), 'image/png');
    assert.equal(response.headers.get('cache-control'), 'no-store');
    assert.deepEqual(Buffer.from(await response.arrayBuffer()), png);
  }
});
test('sends validated image and video attachments with captions', async t => {
  const sent = [];
  const { request } = await fixture(t, { sendMedia: async (id, media) => { sent.push({ id, media }); return { id: 'sent', text: media.caption }; } });
  for (const [mimetype, bytes] of [['image/png', png], ['video/mp4', Buffer.from('000000186674797069736f6d', 'hex')]]) {
    const r = await request('/api/chats/123%40c.us/media', { method: 'POST', body: JSON.stringify({ mimetype, data: bytes.toString('base64'), filename: '../clip', caption: 'hello' }) });
    assert.equal(r.status, 201); assert.equal((await r.json()).message.text, 'hello');
  }
  assert.equal(sent.length, 2); assert.equal(sent[0].media.size, png.length); assert.ok(!sent[0].media.filename.includes('/'));
});
test('rejects unsupported, mismatched and malformed attachment payloads before sending', async t => {
  let sends = 0;
  const { request } = await fixture(t, { sendMedia: async () => { sends++; } });
  const valid = { mimetype: 'image/png', data: png.toString('base64') };
  for (const [body, code] of [[null,415], [{ ...valid, mimetype:'image/svg+xml' },415], [{ ...valid, mimetype:'image/jpeg' },415], [{ ...valid, data:'AB==' },400], [{ ...valid, caption:{} },400], [{ ...valid, caption:'x'.repeat(4097) },400]]) {
    const response = await request('/api/chats/123%40c.us/media', { method:'POST', body:JSON.stringify(body) }); assert.equal(response.status,code);
  }
  assert.equal(sends,0);
});
test('returns profile privacy and attachment expiry errors without crashing', async t => {
  const { request } = await fixture(t, { avatar: async () => null, media: async () => { throw Object.assign(new Error('Attachment expired'), { status:410 }); } });
  assert.equal((await request('/api/chats/123%40c.us/avatar')).status,404);
  assert.equal((await request('/api/chats/123%40c.us/media/false_123%40c.us_key')).status,410);
});
test('exposes call capabilities and events, validates call links, and declines only by explicit POST', async t => {
  const declined = [];
  const { request } = await fixture(t, { calls: () => [{ id:'call1', isVideo:true }], callLink: async type => 'https://call.whatsapp.com/'+type+'/test', rejectCall: async id => declined.push(id) });
  const status = await (await request('/api/status')).json(); assert.equal(status.capabilities.nativeCalling,false);
  assert.equal((await (await request('/api/calls')).json()).calls[0].isVideo,true);
  assert.equal((await request('/api/call-links',{method:'POST',body:JSON.stringify({type:'invalid'})})).status,400);
  const link = await request('/api/call-links',{method:'POST',body:JSON.stringify({type:'video'})}); assert.equal(link.status,201);
  assert.equal((await link.json()).url,'https://call.whatsapp.com/video/test');
  assert.equal((await request('/api/calls/call1/reject',{method:'POST'})).status,200); assert.deepEqual(declined,['call1']);
});
test('streams binary uploads and preserves international filenames/captions', async t => {
  let sent;
  const { request } = await fixture(t, { sendMedia: async (id, media) => { sent=media;return {id:'sent'}; } });
  const response = await request('/api/chats/123%40c.us/media', {
    method:'POST', body:png,
    headers:{'Content-Type':'image/png','X-Relay-Caption':encodeURIComponent('Café ☕ नमस्ते + photo'),'X-Relay-Filename':encodeURIComponent('नमस्ते.png')}
  });
  assert.equal(response.status,201);assert.equal(sent.caption,'Café ☕ नमस्ते + photo');assert.equal(sent.size,png.length);assert.equal(sent.data,png.toString('base64'));
});
test('decodes call identifiers and rejects malformed path encodings safely', async t => {
  const ids=[];
  const {request}=await fixture(t,{rejectCall:async id=>ids.push(id)});
  assert.equal((await request('/api/calls/'+encodeURIComponent('call:1@device')+'/reject',{method:'POST'})).status,200);
  assert.deepEqual(ids,['call:1@device']);
  assert.equal((await request('/api/chats/%invalid/messages')).status,400);
  assert.equal((await request('/api/calls/%invalid/reject',{method:'POST'})).status,400);
});

test('contact list requires authentication and returns relay contact names', async t => {
  const contacts = [{ id: '123@c.us', name: 'Saved contact', isBusiness: false }];
  const { request } = await fixture(t, { contacts: async () => contacts });
  assert.equal((await request('/api/contacts', { headers: { Authorization: '' } })).status, 401);
  assert.deepEqual(await (await request('/api/contacts')).json(), { contacts });
});
