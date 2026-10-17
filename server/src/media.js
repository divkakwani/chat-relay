export const MAX_MEDIA_BYTES = 16 * 1024 * 1024;
export const MEDIA_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp', 'image/gif', 'video/mp4', 'video/webm']);
export const problem = (status, message) => Object.assign(new Error(message), { status });

export function validateMedia(body) {
  if (!body || !MEDIA_TYPES.has(body.mimetype)) throw problem(415, 'Choose a JPEG, PNG, WebP, GIF, MP4 or WebM file.');
  if (typeof body.data === 'string' && body.data.length > Math.ceil(MAX_MEDIA_BYTES / 3) * 4) throw problem(413, 'Attachment exceeds 16 MiB.');
  if (typeof body.data !== 'string' || !body.data || !/^[A-Za-z0-9+/]*={0,2}$/.test(body.data)) {
    throw problem(400, 'Invalid attachment data (maximum 16 MiB).');
  }
  const bytes = Buffer.from(body.data, 'base64');
  if (bytes.toString('base64') !== body.data) throw problem(400, 'Invalid attachment encoding.');
  if (bytes.length > MAX_MEDIA_BYTES) throw problem(413, 'Attachment exceeds 16 MiB.');
  const hex = bytes.subarray(0, 12).toString('hex');
  const signatures = {
    'image/jpeg': hex.startsWith('ffd8ff'),
    'image/png': hex.startsWith('89504e470d0a1a0a'),
    'image/gif': /^GIF8[79]a/.test(bytes.subarray(0, 6).toString()),
    'image/webp': bytes.toString('ascii', 0, 4) === 'RIFF' && bytes.toString('ascii', 8, 12) === 'WEBP',
    'video/mp4': bytes.toString('ascii', 4, 8) === 'ftyp',
    'video/webm': hex.startsWith('1a45dfa3')
  };
  if (!signatures[body.mimetype]) throw problem(415, 'The file contents do not match its media type.');
  if (body.caption !== undefined && (typeof body.caption !== 'string' || body.caption.length > 4096)) throw problem(400, 'Caption must be at most 4096 characters.');
  const filename = (typeof body.filename === 'string' ? body.filename : 'attachment').replace(/[^a-zA-Z0-9._ -]/g, '_').slice(0, 120) || 'attachment';
  return { data: body.data, mimetype: body.mimetype, filename, caption: body.caption || '', size: bytes.length };
}

// Profile URLs come from WhatsApp, never from an API request. Still restrict
// hosts, redirects, time and byte count before proxying them to the phone.
export async function downloadAvatar(url) {
  const parsed = new URL(url);
  if (parsed.protocol !== 'https:' || !['whatsapp.net', 'fbcdn.net'].some(domain => parsed.hostname === domain || parsed.hostname.endsWith('.' + domain))) throw problem(502, 'Profile photo is unavailable.');
  const response = await fetch(url, { redirect: 'error', signal: AbortSignal.timeout(10000) });
  if (!response.ok) throw problem(404, 'Profile photo is unavailable.');
  const mimetype = response.headers.get('content-type')?.split(';')[0];
  if (!['image/jpeg', 'image/png', 'image/webp'].includes(mimetype)) throw problem(415, 'Unsupported profile photo.');
  const reader = response.body.getReader();
  const chunks = []; let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read(); if (done) break;
      size += value.length;
      if (size > 2 * 1024 * 1024) throw problem(413, 'Profile photo is too large.');
      chunks.push(value);
    }
  } finally { await reader.cancel(); }
  return { bytes: Buffer.concat(chunks), mimetype };
}
