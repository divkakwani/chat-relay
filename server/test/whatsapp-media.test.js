import { test } from 'node:test';
import assert from 'node:assert/strict';
import { downloadWhatsAppMedia } from '../src/whatsapp-media.js';
import vm from 'node:vm';
function fixture(overrides = {}) {
  const requests = [];
  const message = { size: 3, directPath: '/attachment', mimetype: 'image/jpeg', type: 'image', mediaData: { mediaStage: 'INIT' }, ...overrides };
  const window = {
    require: name => name === 'WAWebCollections' ? { Msg: { get: () => message } } : { downloadManager: { downloadAndMaybeDecrypt: async options => { requests.push(options); return new Uint8Array([1,2,3]).buffer; } } },
    WWebJS: { arrayBufferToBase64Async: async data => Buffer.from(data).toString('base64') }
  };
  const context = vm.createContext({ window, AbortController, setTimeout, clearTimeout });
  return { requests, run: () => vm.runInContext(`(${downloadWhatsAppMedia.toString()})({messageId:'known-message',maxBytes:100})`,context) };
}
test('forwards the declared MIME type when decrypting uncached images', async () => {
  const { run, requests } = fixture(); const media = await run();
  assert.equal(media.mimetype,'image/jpeg');assert.equal(media.data,'AQID');assert.equal(requests[0].mimetype,'image/jpeg');assert.equal(requests[0].type,'image');
});
test('forwards video MIME types without coercing them to octet-stream', async () => {
  const { run, requests } = fixture({mimetype:'video/mp4',type:'video'});await run();assert.equal(requests[0].mimetype,'video/mp4');
});
test('does not download view-once or expired attachments', async () => {
  for(const overrides of [{isViewOnce:true},{mediaData:{isViewOnce:true}},{mediaData:{mediaStage:'REUPLOADING'}}]) {const {run,requests}=fixture(overrides);assert.equal(await run(),null);assert.equal(requests.length,0);}
});
test('enforces declared file size before contacting the download manager', async () => {
  const {run,requests}=fixture({size:101});assert.equal((await run()).error,'too_large');assert.equal(requests.length,0);
});
