import { test } from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { installWhatsAppCompatibility } from '../src/whatsapp-compat.js';

function fixture(renamed = true) {
  const context = vm.createContext({ renamed });
  vm.runInContext(`
    class MsgKey { constructor(value) { this[renamed ? '$1' : '_serialized'] = value; } }
    const key = new MsgKey('message-key');
    const collections = {
      Chat: { getModelsArray: () => [{ lastReceivedKey: key }] },
      Msg: { getModelsArray: () => [] }
    };
    const makeUtils = () => ({ getMessageModel: message => ({ id: { ...message.id }, body: message.body }) });
    const window = { WWebJS: makeUtils(), require: () => collections };
  `, context);
  const install = () => vm.runInContext(`(${installWhatsAppCompatibility.toString()})()`, context);
  const evaluate = code => vm.runInContext(code, context);
  return { install, evaluate };
}

test('restores lastReceivedKey lookup and serializable message IDs after WhatsApp rename', () => {
  const { install, evaluate } = fixture();
  assert.equal(evaluate('key._serialized'), undefined);
  install();
  assert.equal(evaluate('key._serialized'), 'message-key');
  assert.equal(evaluate(`JSON.parse(JSON.stringify(window.WWebJS.getMessageModel({ id: key, body: 'hello' }))).id._serialized`), 'message-key');
  assert.equal(evaluate(`new MsgKey('future-key')._serialized`), 'future-key');
});
test('preserves older message-key fields and leaves Object.prototype untouched', () => {
  const { install, evaluate } = fixture(false);
  install();
  assert.equal(evaluate('key._serialized'), 'message-key');
  assert.equal(evaluate(`Object.prototype.hasOwnProperty('_serialized')`), false);
  assert.equal(evaluate(`window.WWebJS.getMessageModel({ id: key }).id._serialized`), 'message-key');
});
test('installation is idempotent and handles reinjected WhatsApp utilities', () => {
  const { install, evaluate } = fixture();
  install();
  evaluate('const wrapped = window.WWebJS.getMessageModel');
  install();
  assert.equal(evaluate('wrapped === window.WWebJS.getMessageModel'), true);
  evaluate('window.WWebJS = makeUtils()');
  install();
  assert.equal(evaluate(`window.WWebJS.getMessageModel({id: new MsgKey('after-reload')}).id._serialized`), 'after-reload');
});
test('reports missing injected utilities instead of pretending WhatsApp is ready', () => {
  const { install, evaluate } = fixture();
  evaluate('window.WWebJS = undefined');
  assert.throws(install, /still loading/);
});
