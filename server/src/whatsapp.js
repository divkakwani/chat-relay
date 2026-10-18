import whatsapp from 'whatsapp-web.js';
import qr from 'qrcode-terminal';
import { installBindingRecovery } from './startup.js';
import { installWhatsAppCompatibility } from './whatsapp-compat.js';
import { downloadWhatsAppMedia } from './whatsapp-media.js';
import { MAX_MEDIA_BYTES, MEDIA_TYPES, downloadAvatar, problem } from './media.js';
const { Client, LocalAuth, MessageMedia } = whatsapp;
class RelayWhatsAppClient extends Client {
  async inject() {
    installBindingRecovery(this.pupPage, error => this.emit('relay_startup_error', error));
    return super.inject();
  }
}
const serialize = m => ({
  id: m.id._serialized, text: m.body || '', fromMe: m.fromMe,
  timestamp: m.timestamp, author: m.author || '', type: m.type,
  hasMedia: m.hasMedia, ack: m.ack, mimetype: m._data?.mimetype || '',
  filename: m._data?.filename || '', filesize: m._data?.size || 0
});
export const contactName = (contact, fallback = '') => {
  const labels = [contact?.name, contact?.verifiedName, contact?.pushname, contact?.shortName, fallback, contact?.number]
    .filter(value => typeof value === 'string' && value.trim()).map(value => value.trim());
  // Some synced contacts use their number as `name` but have a real display
  // name in another field. Prefer that label over any formatted phone number.
  return labels.find(value => !/^[+\d\s().-]+$/.test(value)) || labels[0] || 'Unknown contact';
};
const businessContact = contact => !!(contact?.isBusiness || contact?.isEnterprise
  || (typeof contact?.verifiedName === 'string' && contact.verifiedName.trim()));
export function createWhatsApp({ client: suppliedClient } = {}) {
  let state = 'starting';
  const callEvents = new Map();
  const avatars = new Map();
  let avatarBytes = 0;
  const client = suppliedClient || new RelayWhatsAppClient({
    authStrategy: new LocalAuth({ dataPath: '.wwebjs_auth' }),
    puppeteer: { headless: true, ...(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {}) }
  });
  client.on('qr', code => {
    state = 'qr';
    console.log('Link WhatsApp: open Settings → Linked devices → Link a device, then scan:');
    qr.generate(code, { small: true });
  });
  client.on('authenticated', () => {
    if (state !== 'ready') state = 'syncing';
    console.log('WhatsApp authenticated. Preparing message and call handlers…');
  });
  client.on('loading_screen', percent => { console.log(`WhatsApp loading: ${percent}%`); });
  client.on('relay_startup_error', error => {
    state = 'initialization_error';
    // This callback receives no message data or credentials.
    console.error(`WhatsApp startup failed: ${String(error?.message || error).slice(0, 600)}`);
    console.error('Restart the server. Your linked-device session is preserved.');
  });
  client.on('ready', () => { state = 'ready'; console.log('WhatsApp is ready.'); });
  client.on('auth_failure', () => { state = 'auth_failure'; console.error('WhatsApp authentication failed. Restart to link again.'); });
  client.on('disconnected', () => { state = 'disconnected'; console.error('WhatsApp disconnected. Restart the server to reconnect.'); });
  client.on('incoming_call', call => {
    callEvents.set(String(call.id), { call, receivedAt: Date.now(), rejected: false });
    while (callEvents.size > 100) callEvents.delete(callEvents.keys().next().value);
  });
  // Recheck before operations so reinjected utilities after a page reload
  // also receive the compatibility fix. Never retry sends automatically.
  const compatible = async operation => {
    await client.pupPage.evaluate(installWhatsAppCompatibility);
    return operation();
  };
  const resolveContact = async id => {
    try { return await client.getContactById(id); } catch { return null; }
  };
  const namedMessage = async message => ({ ...serialize(message),
    authorName: message.author ? contactName(await resolveContact(message.author), message.author.split('@')[0]) : ''
  });
  return {
    status: () => ({ ready: state === 'ready', state, provider: 'whatsapp', account: state === 'ready' ? { id: client.info?.wid?._serialized || '', name: client.info?.pushname || 'You' } : null }),
    start: () => client.initialize(),
    stop: () => client.destroy(),
    contacts: () => compatible(async () => (await client.getContacts())
      .filter(c => /@(c\.us|lid)$/.test(c.id._serialized) && !c.isMe && (c.isMyContact || businessContact(c)))
      .map(c => ({ id: c.id._serialized, name: contactName(c), number: c.number || '', isBusiness: businessContact(c) }))
      .sort((a, b) => a.name.localeCompare(b.name))),
    chats: () => compatible(async () => {
      const contacts = new Map();
      for (const contact of await client.getContacts()) {
        contacts.set(contact.id._serialized, contact);
      }
      return (await Promise.all((await client.getChats()).filter(c => /@(c\.us|g\.us|lid)$/.test(c.id._serialized)).map(async c => {
        const contact = contacts.get(c.id._serialized) || (!c.isGroup ? await resolveContact(c.id._serialized) : null);
        const isBusiness = !c.isGroup && businessContact(contact);
        return { id: c.id._serialized,
          name: c.isGroup ? (c.name || c.groupMetadata?.subject || 'Unnamed group') : contactName(contact, c.name || c.id.user),
          isGroup: !!c.isGroup, isBusiness, category: c.isGroup ? 'Groups' : isBusiness ? 'Business' : 'DMs',
          unread: c.unreadCount, timestamp: c.timestamp || 0,
          lastMessage: c.lastMessage ? { ...serialize(c.lastMessage), authorName: c.lastMessage.author ? contactName(contacts.get(c.lastMessage.author), c.lastMessage.author.split('@')[0]) : '' } : null };
      }))).sort((a, b) => b.timestamp - a.timestamp);
    }),
    messages: id => compatible(async () => {
      const messages = await (await client.getChatById(id)).fetchMessages({ limit: 60 });
      return (await Promise.all(messages.map(namedMessage))).sort((a, b) => a.timestamp - b.timestamp);
    }),
    read: id => compatible(async () => (await client.getChatById(id)).sendSeen()),
    send: (id, text) => compatible(async () => serialize(await client.sendMessage(id, text))),
    sendMedia: (id, media) => compatible(async () => serialize(await client.sendMessage(id,
      new MessageMedia(media.mimetype, media.data, media.filename, media.size),
      { caption: media.caption }
    ))),
    media: (id, messageId) => compatible(async () => {
      const message = await client.getMessageById(messageId);
      if (!message || !message.hasMedia || (await message.getChat()).id._serialized !== id) throw problem(404, 'Attachment not found in this conversation.');
      if (message._data?.size > MAX_MEDIA_BYTES) throw problem(413, 'This attachment exceeds the 16 MiB download limit.');
      const attachment = await client.pupPage.evaluate(downloadWhatsAppMedia, { messageId, maxBytes: MAX_MEDIA_BYTES });
      if (attachment?.error === 'too_large') throw problem(413, 'This attachment exceeds the 16 MiB download limit.');
      if (!attachment?.data) throw problem(410, 'Attachment expired or is unavailable on this linked device.');
      if (!MEDIA_TYPES.has(attachment.mimetype)) throw problem(415, 'This attachment format is not supported yet.');
      const bytes = Buffer.from(attachment.data, 'base64');
      if (bytes.length > MAX_MEDIA_BYTES) throw problem(413, 'This attachment exceeds the 16 MiB download limit.');
      return { bytes, mimetype: attachment.mimetype };
    }),
    avatar: async id => {
      const cached = avatars.get(id);
      if (cached && cached.until > Date.now()) return cached.value;
      if (cached) { avatarBytes -= cached.value?.bytes.length || 0; avatars.delete(id); }
      const url = await client.getProfilePicUrl(id);
      const value = url ? await downloadAvatar(url) : null;
      avatars.set(id, { until: Date.now() + 10 * 60 * 1000, value });
      avatarBytes += value?.bytes.length || 0;
      while (avatars.size > 100 || avatarBytes > 16 * 1024 * 1024) {
        const first = avatars.keys().next().value;
        avatarBytes -= avatars.get(first).value?.bytes.length || 0; avatars.delete(first);
      }
      return value;
    },
    calls: () => [...callEvents.values()].reverse().map(({ call, receivedAt, rejected }) => ({
      id: String(call.id), from: call.from, fromMe: call.fromMe, isVideo: call.isVideo,
      isGroup: call.isGroup, timestamp: call.timestamp || Math.floor(receivedAt / 1000),
      status: rejected ? 'declined' : 'received', canReject: !rejected && !call.fromMe && Date.now() - receivedAt < 45000
    })),
    rejectCall: async id => {
      const event = callEvents.get(id);
      if (!event || event.rejected || event.call.fromMe || Date.now() - event.receivedAt > 45000) throw problem(409, 'This call can no longer be declined.');
      await event.call.reject(); event.rejected = true;
    },
    callLink: async type => {
      const url = await client.createCallLink(new Date(), type);
      if (!url || !url.startsWith('https://call.whatsapp.com/' + type + '/')) throw problem(502, 'WhatsApp could not create a call link. Open WhatsApp to call instead.');
      return url;
    }
  };
}
