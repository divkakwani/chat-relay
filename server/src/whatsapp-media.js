// Self-contained browser function. whatsapp-web.js 1.34.7 omits mimetype
// from DownloadManager arguments, breaking uncached photos/videos (#201908).
export async function downloadWhatsAppMedia({ messageId, maxBytes }) {
  const store = window.require('WAWebCollections').Msg;
  const message = store.get(messageId) || (await store.getMessagesById([messageId]))?.messages?.[0];
  if (!message || message.isViewOnce || message.mediaData?.isViewOnce || !message.directPath || message.mediaData?.mediaStage === 'REUPLOADING') return null;
  if (message.size > maxBytes) return { error: 'too_large' };
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 60000);
  try {
    const data = await window.require('WAWebDownloadManager').downloadManager.downloadAndMaybeDecrypt({
      directPath: message.directPath, encFilehash: message.encFilehash,
      filehash: message.filehash, mediaKey: message.mediaKey,
      mediaKeyTimestamp: message.mediaKeyTimestamp, type: message.type,
      mimetype: message.mimetype, // Required by the current WhatsApp MIME allowlist.
      signal: controller.signal,
      downloadQpl: { addAnnotations() { return this; }, addPoint() { return this; } }
    });
    if (data.byteLength > maxBytes) return { error: 'too_large' };
    return { data: await window.WWebJS.arrayBufferToBase64Async(data), mimetype: message.mimetype, filename: message.filename };
  } catch (error) {
    if (error.status === 404 || error.status === 410) return null;
    throw error;
  } finally { clearTimeout(timeout); }
}
