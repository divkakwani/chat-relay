// Runs inside WhatsApp Web via Puppeteer. Keep this function self-contained.
// WA Web renamed MsgKey._serialized to $1; whatsapp-web.js 1.34.7 still
// reads _serialized when loading a chat's last message (upstream #201845).
export function installWhatsAppCompatibility() {
  const utils = window.WWebJS;
  if (!utils || typeof utils.getMessageModel !== 'function') {
    throw new Error('WhatsApp Web is still loading. Retry after synchronization.');
  }
  const collections = window.require('WAWebCollections');
  const sample = collections.Chat.getModelsArray().find(chat => chat.lastReceivedKey?.$1)?.lastReceivedKey
    || collections.Msg.getModelsArray().find(message => message.id?.$1)?.id;
  if (sample && sample._serialized === undefined && typeof sample.$1 === 'string') {
    const prototype = Object.getPrototypeOf(sample);
    if (prototype && prototype !== Object.prototype && !Object.getOwnPropertyDescriptor(prototype, '_serialized')) {
      Object.defineProperty(prototype, '_serialized', {
        configurable: true,
        get() { return this.$1; },
        // Preserve assignment compatibility with older key constructors.
        set(value) { Object.defineProperty(this, '_serialized', { value, writable: true, configurable: true, enumerable: true }); }
      });
    }
  }
  if (!utils.__chatRelayMessageKeys) {
    const original = utils.getMessageModel;
    utils.getMessageModel = function (...args) {
      const model = original.apply(this, args);
      // Puppeteer serializes own properties, so the prototype getter alone
      // does not provide _serialized on messages returned to Node.js.
      if (model?.id && !model.id._serialized && typeof model.id.$1 === 'string') {
        model.id = { ...model.id, _serialized: model.id.$1 };
      }
      return model;
    };
    utils.__chatRelayMessageKeys = true;
  }
}
