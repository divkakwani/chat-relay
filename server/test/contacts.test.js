import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createWhatsApp, contactName } from '../src/whatsapp.js';
const id = value => ({ _serialized: value, user: value.split('@')[0] });
function fixture() {
  const contacts = [
    { id: id('1@c.us'), name: 'Saved friend', isMyContact: true },
    { id: id('2@lid'), verifiedName: 'Acme Support', isBusiness: true },
    { id: id('3@lid'), pushname: 'Priya 🌻' },
    { id: id('4@c.us'), name: 'Me', isMe: true, isMyContact: true }
  ];
  const message = { id: id('message'), body: 'Hello', timestamp: 1, author: '3@lid' };
  const chats = [
    { id: id('1@c.us'), name: '1', timestamp: 1 },
    { id: id('2@lid'), name: '2', timestamp: 2 },
    { id: id('10@g.us'), name: 'Weekend friends', isGroup: true, timestamp: 3, lastMessage: message }
  ];
  return createWhatsApp({ client: {
    on() {}, pupPage: { evaluate: async () => {} },
    getContacts: async () => contacts, getChats: async () => chats,
    getContactById: async value => contacts.find(c => c.id._serialized === value),
    getChatById: async () => ({ fetchMessages: async () => [message] })
  } });
}
test('classifies businesses separately and resolves saved, verified and group names', async () => {
  const chats = await fixture().chats();
  assert.deepEqual(chats.map(c => [c.name, c.category]), [
    ['Weekend friends', 'Groups'], ['Acme Support', 'Business'], ['Saved friend', 'DMs']
  ]);
  assert.equal(chats[0].lastMessage.authorName, 'Priya 🌻');
});
test('resolves group authors without requiring a direct conversation', async () => {
  assert.equal((await fixture().messages('10@g.us'))[0].authorName, 'Priya 🌻');
});
test('contact picker includes saved contacts and businesses, excluding self and unsaved people', async () => {
  assert.deepEqual((await fixture().contacts()).map(c => c.name), ['Acme Support', 'Saved friend']);
});
test('name fallbacks preserve Unicode and ignore empty labels', () => {
  assert.equal(contactName({ name: ' ', verifiedName: '商店' }, '123'), '商店');
  assert.equal(contactName(null, '123'), '123');
});
test('joins LID chats to phone-serialized business contacts and keeps personal chats separate', async () => {
  const contacts = [
    { id: id('101@lid'), verifiedName: 'Airtel', isBusiness: true },
    { id: id('102@lid'), verifiedName: 'Decode Age', isEnterprise: true },
    { id: id('103@lid'), name: 'Friend', pushname: 'Airtel fan', verifiedName: ' ' },
    { id: id('104@lid'), verifiedName: 'Verified shop', isBusiness: false }
  ];
  const relay = createWhatsApp({ client: {
    on() {}, pupPage: { evaluate: async () => {} },
    getContacts: async () => contacts,
    getChats: async () => ['101@lid', '102@lid', '103@lid', '104@lid', '105@lid', '106@lid', '107@g.us'].map(value => ({ id: id(value), isGroup: value.endsWith('@g.us') })),
    getContactById: async value => {
      if (value === '105@lid') return { verifiedName: 'Uncached business', isBusiness: true };
      throw new Error('Unavailable contact');
    }
  } });
  assert.deepEqual((await relay.chats()).map(c => [c.name, c.category]), [
    ['Airtel', 'Business'], ['Decode Age', 'Business'], ['Friend', 'DMs'],
    ['Verified shop', 'Business'], ['Uncached business', 'Business'], ['106', 'DMs'], ['Unnamed group', 'Groups']
  ]);
  assert.deepEqual((await relay.contacts()).map(c => [c.name, c.isBusiness]), [
    ['Airtel', true], ['Decode Age', true], ['Verified shop', true]
  ]);
});
