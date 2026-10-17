import { test } from 'node:test';
import assert from 'node:assert/strict';
import { validateMedia, MAX_MEDIA_BYTES, downloadAvatar } from '../src/media.js';
import { createWhatsApp } from '../src/whatsapp.js';

test('bounds attachment sizes before decoding', () => {
  assert.throws(() => validateMedia({ mimetype:'image/png', data:'A'.repeat(Math.ceil(MAX_MEDIA_BYTES/3)*4+4) }), { status:413 });
});
test('avatar proxy refuses non-HTTPS, unrelated hosts and suffix lookalikes', async () => {
  for (const url of ['http://pps.whatsapp.net/a','https://localhost/a','https://whatsapp.net.evil.example/a','https://127.0.0.1/a']) await assert.rejects(downloadAvatar(url),{status:502});
});
function fakeClient(message) {
  const handlers = {};
  return { handlers, on:(event,fn) => { handlers[event]=fn; }, pupPage:{evaluate:async()=>{}}, getMessageById:async()=>message };
}
test('media downloads require the message to belong to the requested chat', async () => {
  let downloaded=false;
  const client=fakeClient({hasMedia:true, getChat:async()=>({id:{_serialized:'other@c.us'}}), downloadMedia:async()=>{downloaded=true;}});
  await assert.rejects(createWhatsApp({client}).media('123@c.us','message-id'),{status:404}); assert.equal(downloaded,false);
});
test('rejects known oversized media before requesting a download', async () => {
  let downloaded=false;
  const client=fakeClient({hasMedia:true,_data:{size:MAX_MEDIA_BYTES+1},getChat:async()=>({id:{_serialized:'123@c.us'}}),downloadMedia:async()=>{downloaded=true;}});
  await assert.rejects(createWhatsApp({client}).media('123@c.us','message-id'),{status:413});assert.equal(downloaded,false);
});
test('incoming call events can be declined once, with no automatic answering', async () => {
  const client=fakeClient();let rejects=0;const relay=createWhatsApp({client});
  client.handlers.incoming_call({id:'test-call',from:'123@c.us',isVideo:true,fromMe:false,reject:async()=>{rejects++;}});
  assert.equal(relay.calls()[0].canReject,true);assert.equal(relay.calls()[0].isVideo,true);
  await relay.rejectCall('test-call');assert.equal(rejects,1);assert.equal(relay.calls()[0].status,'declined');
  await assert.rejects(relay.rejectCall('test-call'),{status:409});assert.equal(rejects,1);
});
