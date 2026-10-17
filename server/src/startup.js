import { setTimeout as delay } from 'node:timers/promises';

const recoveredPages = new WeakSet();
const transientFrameError = error => /target closed|session closed|frame (?:was )?detached|detached frame|execution context was destroyed|cannot find context/i.test(error?.message || '');

// Puppeteer exposes each function into all frames. A disappearing transient
// frame can reject the call after the main-frame binding is already installed.
// Remove that partial registration before retrying, rather than skipping the
// rest of whatsapp-web.js startup and incorrectly reporting the client ready.
export function installBindingRecovery(page, onStartupError, { pause = delay, attempts = 3 } = {}) {
  if (recoveredPages.has(page)) return;
  recoveredPages.add(page);
  const expose = page.exposeFunction.bind(page);
  page.exposeFunction = async (name, fn) => {
    const callback = name === 'onAppStateHasSyncedEvent' ? async (...args) => {
      try { return await fn(...args); }
      catch (error) { onStartupError(error); throw error; }
    } : fn;
    for (let attempt = 0; attempt < attempts; attempt++) {
      try { return await expose(name, callback); }
      catch (error) {
        if (!transientFrameError(error) || page.isClosed() || attempt === attempts - 1) throw error;
        try { await page.removeExposedFunction(name); } catch { /* The failed attempt may not have registered a binding. */ }
        await pause(200 * (attempt + 1));
      }
    }
  };
}
