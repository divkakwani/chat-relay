import { createApi } from './api.js';
import { createWhatsApp } from './whatsapp.js';
const relay = createWhatsApp();
const server = createApi({ token: process.env.RELAY_TOKEN, relay });
const port = Number(process.env.PORT || 3000);
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Invalid PORT');
server.requestTimeout = 30000;
server.headersTimeout = 15000;
server.listen(port, '127.0.0.1', () => {
  console.log(`Chat Relay listening on http://127.0.0.1:${port}. Use Tailscale Serve for phone access.`);
});
server.on('error', error => { console.error(error.message); process.exit(1); });
relay.start().catch(() => { console.error('Could not start WhatsApp. Check Chrome installation and restart.'); server.close(); process.exitCode = 1; });
let stopping = false;
async function stop() {
  if (stopping) return;
  stopping = true;
  server.close();
  const timeout = setTimeout(() => process.exit(1), 10000).unref();
  try { await relay.stop(); } finally { clearTimeout(timeout); process.exit(0); }
}
process.on('SIGINT', stop);
process.on('SIGTERM', stop);
