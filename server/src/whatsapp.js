import whatsapp from 'whatsapp-web.js';
import qr from 'qrcode-terminal';
const { Client, LocalAuth } = whatsapp;
const serialize = m => ({
  id: m.id._serialized, text: m.body || '', fromMe: m.fromMe,
  timestamp: m.timestamp, author: m.author || '', type: m.type,
  hasMedia: m.hasMedia, ack: m.ack
});
export function createWhatsApp() {
  let state = 'starting';
  const client = new Client({
    authStrategy: new LocalAuth({ dataPath: '.wwebjs_auth' }),
    puppeteer: { headless: true, ...(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {}) }
  });
  client.on('qr', code => {
    state = 'qr';
    console.log('Link WhatsApp: open Settings → Linked devices → Link a device, then scan:');
    qr.generate(code, { small: true });
  });
  client.on('authenticated', () => { state = 'syncing'; });
  client.on('ready', () => { state = 'ready'; console.log('WhatsApp is ready.'); });
  client.on('auth_failure', () => { state = 'auth_failure'; console.error('WhatsApp authentication failed. Restart to link again.'); });
  client.on('disconnected', () => { state = 'disconnected'; console.error('WhatsApp disconnected. Restart the server to reconnect.'); });
  return {
    status: () => ({ ready: state === 'ready', state, provider: 'whatsapp' }),
    start: () => client.initialize(),
    stop: () => client.destroy(),
    chats: async () => (await client.getChats()).filter(c => /@(c\.us|g\.us|lid)$/.test(c.id._serialized)).map(c => ({
      id: c.id._serialized, name: c.name || c.id.user, isGroup: c.isGroup,
      unread: c.unreadCount, timestamp: c.timestamp || 0,
      lastMessage: c.lastMessage ? serialize(c.lastMessage) : null
    })).sort((a, b) => b.timestamp - a.timestamp),
    messages: async id => (await (await client.getChatById(id)).fetchMessages({ limit: 60 })).map(serialize).sort((a, b) => a.timestamp - b.timestamp),
    read: async id => (await client.getChatById(id)).sendSeen(),
    send: async (id, text) => serialize(await client.sendMessage(id, text))
  };
}
