import { test } from 'node:test';
import assert from 'node:assert/strict';
import { installBindingRecovery } from '../src/startup.js';
const options = { pause: async () => {} };
test('retries partial binding registration after a transient frame closes', async () => {
  const registrations = new Map(); let tries = 0, removals = 0;
  const page = {
    isClosed: () => false,
    exposeFunction: async (name, callback) => {
      assert.equal(registrations.has(name), false);
      registrations.set(name, callback);
      if (++tries === 1) throw new Error('Protocol error (Runtime.addBinding): Target closed');
    },
    removeExposedFunction: async name => { removals++; registrations.delete(name); }
  };
  installBindingRecovery(page, () => {}, options);
  await page.exposeFunction('onIncomingCall', () => 'installed');
  assert.equal(tries, 2); assert.equal(removals, 1);
  assert.equal(registrations.get('onIncomingCall')(), 'installed');
});
test('does not hide permanent errors or retry a closed page', async () => {
  for (const [message, closed] of [['Invalid binding name', false], ['Target closed', true]]) {
    let tries = 0;
    const page = { isClosed: () => closed, exposeFunction: async () => { tries++; throw new Error(message); } };
    installBindingRecovery(page, () => {}, options);
    await assert.rejects(page.exposeFunction('handler', () => {}), new RegExp(message)); assert.equal(tries, 1);
  }
});
test('bounds transient retries and still reports failure', async () => {
  let tries = 0;
  const page = { isClosed: () => false, exposeFunction: async () => { tries++; throw new Error('Target closed'); }, removeExposedFunction: async () => {} };
  installBindingRecovery(page, () => {}, options);
  await assert.rejects(page.exposeFunction('handler', () => {}), /Target closed/); assert.equal(tries, 3);
});
test('reports rejected synchronization callbacks without swallowing their errors', async () => {
  let callback; const failures = [];
  const page = { exposeFunction: async (name, fn) => { callback = fn; } };
  installBindingRecovery(page, error => failures.push(error), options);
  const error = new Error('Startup handler failed');
  await page.exposeFunction('onAppStateHasSyncedEvent', async () => { throw error; });
  await assert.rejects(callback(), /Startup handler failed/); assert.deepEqual(failures, [error]);
});
test('installing twice does not wrap exposure or callbacks twice', () => {
  const page = { exposeFunction: async () => {} };
  installBindingRecovery(page, () => {}, options); const wrapped = page.exposeFunction;
  installBindingRecovery(page, () => {}, options); assert.equal(page.exposeFunction, wrapped);
});
