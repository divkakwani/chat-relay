# Chat Relay

A personal WhatsApp relay: WhatsApp Web runs on your computer, and a native Android app reads and sends messages over your private Tailscale network.

The Android UI follows the supplied `chatrelay.` design: an off-white/sage palette, bundled Inter typography, profile photos, DMs/Groups/Calls tabs, and light/dark/system appearance. The launcher uses an original speech-bubble and relay-arrow adaptive icon, including an Android 13 themed variant. This is an independent project, unaffiliated with WhatsApp or Meta.

## How it connects

```text
Android app → HTTPS over Tailscale → Tailscale Serve on computer
                                     ↓ loopback HTTP
                               Node.js relay → WhatsApp Web
```

The server listens **only on `127.0.0.1`**, so it does not expose a port to your LAN. Tailscale Serve publishes it privately to your tailnet using a valid HTTPS certificate. Every API request also requires a bearer token. The Android app accepts only HTTPS `*.ts.net` server addresses, refuses cleartext HTTP and redirects, and encrypts the token with an Android Keystore key. It does not embed Tailscale: install and connect the Tailscale app separately.

## Requirements

- A computer with Node.js 22+ and a supported Chrome/Chromium environment. Puppeteer downloads its compatible browser during `npm ci` unless you opt out.
- An existing WhatsApp account, with access to its **Linked devices** screen.
- Tailscale on the computer and Android phone, both connected to the same tailnet.
- Android 8.0/API 26 or later.
- To build: Android Studio, JDK 17, Android SDK Platform 35 and Build Tools 35.0.0. The Gradle wrapper is included (Gradle 8.11.1, Android Gradle Plugin 8.9.2).

## 1. Start the local server

From this repository:

```sh
cd server
npm ci
cp .env.example .env
node -e "console.log(require('node:crypto').randomBytes(32).toString('hex'))"
```

Paste the generated value into `RELAY_TOKEN` in `server/.env`. Keep it private; you will enter the same value on the phone. Leave `PORT=8788` unless you need another local port.

```sh
npm start
```

Scan the QR code printed in the terminal using WhatsApp → **Settings → Linked devices → Link a device** (the menu placement depends on your phone). Wait for **WhatsApp is ready**. The QR is deliberately local; it is never sent through the relay API. On subsequent launches, `LocalAuth` restores the session from `server/.wwebjs_auth/`.

Keep this process running and the computer awake. Stop it with Ctrl+C. Restart after a WhatsApp disconnect or authentication failure. To link another account, stop the server, unlink the device in WhatsApp, remove `server/.wwebjs_auth/`, then start again.

On Linux, install the system libraries needed by Chrome/Puppeteer. For an existing browser, set `CHROME_PATH` in `.env`. If you skip the Puppeteer download (`PUPPETEER_SKIP_DOWNLOAD=true npm ci`), you must supply a working Chrome executable. Run as a normal user with the browser sandbox enabled.

## 2. Expose the server privately with Tailscale

Install and sign in to [Tailscale](https://tailscale.com/download) on both devices. On the computer, run:

```sh
tailscale serve --bg http://127.0.0.1:8788
tailscale serve status
```

Follow the command's prompt/link to enable HTTPS certificates if needed. Enable MagicDNS in the Tailscale admin console if it is disabled. Copy the HTTPS address printed by Serve, for example:

```text
https://my-computer.example-tailnet.ts.net
```

Use **Serve**, which is private to the tailnet. Do not enable Funnel, port forwarding, or a public reverse proxy. Your tailnet access policy must allow the phone/user to reach the computer on TCP port 443. Restrict that access to the intended user/device if other people share the tailnet; a bearer token is still required even when the network permits access.

`--bg` keeps Serve configured across Tailscale restarts; it does not keep the Node.js process alive. If you changed `PORT`, use that port in the Serve command too. To disable this Serve configuration later, run `tailscale serve --https=443 off`.

Optional authenticated connectivity check from another tailnet machine (substitute your real URL):

```sh
read -r -s RELAY_TOKEN
export RELAY_TOKEN
curl -H "Authorization: Bearer $RELAY_TOKEN" https://my-computer.example-tailnet.ts.net/api/status
unset RELAY_TOKEN
```

A linked server returns `{"ready":true,"state":"ready","provider":"whatsapp"}`. A 401 means the token is missing or incorrect. No API endpoint is public, including status.

## 3. Build and install the Android app

Open the **`android/` directory** in Android Studio, let Gradle sync, and run the `app` configuration on your phone. Android Studio's bundled JDK can be used for Gradle.

Or, with JDK 17 and the Android SDK configured:

```sh
cd android
# Set ANDROID_HOME to your SDK, or put sdk.dir=/absolute/path/to/sdk in local.properties.
./gradlew :app:assembleDebug :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows use `gradlew.bat`. The first build downloads Gradle and Android build dependencies. The APK above is a development build; configure your own signing key for release distribution.

Open **Chat Relay** on the phone:

1. Ensure Tailscale is connected.
2. Enter the exact HTTPS `.ts.net` address from Serve, without an API path.
3. Paste the server's access token and tap **Connect to relay**.
4. After the server reports WhatsApp ready, open a conversation and send a text message.

Use **Settings** in the inbox to change the connection. Leaving the token field blank keeps the existing token. To rotate it, change `RELAY_TOKEN` on the server, restart the server, and enter the new value in Android settings.

## Download the APK from GitHub Actions

Open [Build and test](https://github.com/divkakwani/chat-relay/actions/workflows/check.yml), select the latest successful run, and download **chat-relay-debug-apk** from **Artifacts**. Unzip the download and install `app-debug.apk` on your Android phone (allow installation from that source when prompted). You must be signed into GitHub to download Actions artifacts. Artifacts are retained for 14 days.

Every push runs the build. To create a fresh APK without changing code, select **Run workflow** on that Actions page. This produces a debug APK for personal testing. The computer relay and Tailscale setup above are still required.

## Updating from version 1.0

Install the latest `chat-relay-debug-apk` build and **restart the server with the updated code**. An in-place app update retains your server address and token. If Android refuses the update because the development signing certificate changed, uninstall the old debug app first and re-enter the address/token after installing. Uninstalling clears Android settings, but does not unlink the WhatsApp session on your computer. CI caches its debug signing key for subsequent builds; cache expiry can still change that development identity. The new Android app needs the new server endpoints for media, profile photos and calls.

```sh
# From the repository root
git pull
cd server
npm ci
# Stop the old server with Ctrl+C in its terminal, then:
npm start
```

If your Puppeteer browser cache is incomplete, you can use the Chrome already installed on your computer. For macOS, put this in `server/.env`:

```dotenv
CHROME_PATH="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
```

Then install dependencies using `PUPPETEER_SKIP_DOWNLOAD=true npm ci`. Do not use that option unless you have configured an existing browser.

## Photos, videos and appearance

- Profile photos are loaded on demand through the authenticated relay, respecting WhatsApp's profile-photo privacy settings. Initials appear when a photo is private or unavailable. Profile photos are cached on the server for ten minutes within a 16 MiB memory budget.
- Received photos appear inside message bubbles; tap to open a larger view. Tap a video tile to download it and play it with native playback controls.
- Tap the paperclip, choose a photo/video, review its preview and optional caption, then tap **Send**. File selection uses Android's document picker and does not require access to your whole photo library.
- Supported attachment formats: JPEG, PNG, WebP, GIF, MP4 and WebM, up to **16 MiB per file** in each direction. Upload MIME types and file signatures are checked. Videos require a codec supported by the server's Chrome and the phone. GIFs currently display as a still frame.
- View-once messages are not downloaded. Expired or unavailable attachments display an error when opened. Other documents/audio attachments still require WhatsApp.
- Tap your profile circle in the inbox, then select **Light**, **Dark**, or **System**. System mode follows Android's device setting. All themes use the bundled, openly licensed Inter font; the license is included in the app assets.
- The compose button starts a conversation with an international WhatsApp phone number. Existing chats can be searched by name or preview. Draft text is retained in memory when navigating between chats and across recreation of the current conversation.

Media requests require the bearer token. CDN profile-photo URLs are kept behind the server instead of exposing them to Android. The server sends media with `Cache-Control: no-store`. Android keeps bounded image thumbnails in memory; videos are stored in the app's private cache for playback and removed when the viewer closes. Android screenshots and backups are disabled in normal app builds.

## Voice and video calls

**Calls run in the official WhatsApp app, as selected for this project.** The upstream web library has no supported API to initiate/answer calls or transport call audio/video into this Android relay.

- Tap a voice/video icon in a conversation and choose **Open WhatsApp**. Open that conversation there and tap WhatsApp's call button. For phone-number chats, the relay opens the contact through WhatsApp's click-to-chat link. For groups or LID-only chats, it opens WhatsApp so you can select the conversation.
- You can also choose **Create link**, then open or share the voice/video call link. Creating a link does not place a call or automatically message anyone.
- Incoming voice/video call events appear in **Calls** and trigger a dialog while Chat Relay is open. You can open WhatsApp or explicitly decline a recent incoming call.
- The call list holds up to 100 events received since the server started. It is not your full WhatsApp call history, and does not claim a call is still ringing or connected. Decline requests may fail if the caller already hung up.
- Background ringing, answering inside Chat Relay, and relaying microphone/camera streams are **not supported**. Keep the official WhatsApp app installed for those features.

## Other behavior and limits

- Latest 60 available messages per conversation; older-history pagination, reactions and audio messaging are not implemented.
- Text/captions up to 4096 characters, day separators, group sender IDs, delivery/read indicators, and marking a conversation read when opened.
- Foreground polling every four seconds; polling stops when the activity is paused. There are no background message notifications yet.
- No persistent local message database. Other drafts may be lost if Android kills the process. Attachment selections must be chosen again after recreation.
- A timed-out send can still have reached WhatsApp. The client keeps the text and warns you to check the conversation before retrying; sends are never automatically retried.
- The relay terminates message access on your computer and phone. Tailscale encrypts the network leg; this is not a claim of additional end-to-end encryption between these two apps.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Cannot connect | Tailscale connected on both devices; computer awake; Node process running; correct Serve URL; tailnet policy allows 443. |
| HTTPS returns `invalid API key` but local status works | A different service (such as Grafana on port 3000) may be answering the Serve target. Use the dedicated relay port 8788 and rerun `tailscale serve --bg http://127.0.0.1:8788`. |
| Invalid access token | Same token in `.env` and Android settings; restart server after editing `.env`. |
| WhatsApp `qr` / `syncing` | Scan the terminal QR if shown. If stuck after authentication, use the latest server and restart it: a startup compatibility fix retries event bindings interrupted by transient browser frames. Look for **WhatsApp is ready** in the terminal. |
| WhatsApp `initialization_error` | The terminal now logs the startup failure. Restart the server; do not delete your linked-device session as a first step. |
| Chat list fails although WhatsApp is ready | Restart the server with the latest source. A compatibility shim handles WhatsApp Web’s message-key field rename (`_serialized` → `$1`), which breaks upstream `getChats()` in 1.34.7. No new APK or re-link is needed. |
| WhatsApp disconnected | Restart server; check WhatsApp Linked devices and re-link if needed. |
| Browser fails to launch | Puppeteer browser downloaded, required Linux libraries installed, or correct `CHROME_PATH`. |
| Empty/limited history | Wait for WhatsApp Web synchronization; only history available to the linked session is returned. |
| Gradle fails before building | Use JDK 17, install SDK 35, and configure `ANDROID_HOME` or `android/local.properties`. |

## Tests and verification

```sh
cd server
npm test
```

The server integration suite uses a fake WhatsApp adapter and real loopback HTTP. It covers authentication, readiness, chat/history reads, read receipts, sending, payload limits, invalid IDs, Unicode transport handling and sanitized upstream errors. It needs no WhatsApp account or Chrome.

GitHub Actions also runs these tests, builds/lints the Android app and uploads a debug APK. A separate Android emulator job checks appearance modes, recreation and draft navigation using synthetic contacts, then uploads UI screenshots and test reports as `chat-relay-ui-checks`. No real account or token is used in emulator tests. A live end-to-end check requires your own WhatsApp linking and tailnet: send from the app, confirm in WhatsApp, reply from another account, and confirm it appears after the next poll. Disconnect Tailscale and verify the app reports a connection failure.

The server suite includes API integration, message-key serialization, uncached-media MIME handling, upload limits/type validation, profile-proxy restrictions, conversation ownership and call-event regression tests. GitHub Actions builds and lints Android; see the [latest workflow results](https://github.com/divkakwani/chat-relay/actions/workflows/check.yml). Profile-photo, received-photo and received-video downloads were also checked against a linked WhatsApp session, reporting only content types and byte counts. Outgoing media and live call handoffs still need a check on your phone.

### Dependency status

`whatsapp-web.js` 1.34.7 pins Puppeteer 24.38.0. At implementation time, `npm audit` reported high-severity transitive findings in its browser download/extraction and proxy dependency chain. A patched `basic-ftp` override is included, leaving five high-severity findings in the extraction dependency chain; remaining upstream findings are not silently suppressed or fixed by forcing an untested Puppeteer major version. Review `npm audit` before deployment and update the upstream library when a compatible fix is available. This implementation uses only `LocalAuth` and does not accept browser archives or proxy configuration through its API.

## API

All requests require `Authorization: Bearer <RELAY_TOKEN>`; errors are JSON `{ "error": "…" }`.

| Method | Path | Result |
| --- | --- | --- |
| GET | `/api/status` | WhatsApp ready flag and connection state |
| GET | `/api/chats` | `{ chats: [...] }`, newest first |
| GET | `/api/chats/:id/avatar` | Authenticated profile-photo bytes, or 404 when unavailable/private |
| GET | `/api/chats/:id/media/:messageId` | Authenticated attachment bytes, up to 16 MiB |
| POST | `/api/chats/:id/media` | Raw media bytes with the media `Content-Type` and URL-encoded `X-Relay-Filename` / `X-Relay-Caption` headers (used by Android); base64 JSON is also accepted |
| GET | `/api/calls` | Incoming/outgoing call events received during the current server session |
| POST | `/api/calls/:id/reject` | Explicitly decline a recent incoming call |
| POST | `/api/call-links` | Body `{ "type": "voice" }` or `{ "type": "video" }`; returns `{ "url": "https://call.whatsapp.com/…" }` |
| GET | `/api/chats/:id/messages` | `{ messages: [...] }`, oldest first, latest 60 |
| POST | `/api/chats/:id/messages` | Body `{ "text": "Hello" }`; 201 with `{ message: {...} }` |
| POST | `/api/chats/:id/read` | Mark conversation seen |

URL-encode the serialized WhatsApp chat ID, such as `123456789%40c.us`. Non-status endpoints return 503 until WhatsApp is ready. The provider adapter is isolated in `server/src/whatsapp.js` for future messaging integrations.

## References

The server forwards declared MIME types to the WhatsApp download manager to handle [uncached-media issue #201908](https://github.com/wwebjs/whatsapp-web.js/issues/201908). It also includes a targeted compatibility shim for [upstream message-key rename issue #201845](https://github.com/wwebjs/whatsapp-web.js/issues/201845). It restores the legacy field and message serialization without changing `node_modules` or dropping chats.

- [whatsapp-web.js Client API](https://docs.wwebjs.dev/Client.html), [Chat API](https://docs.wwebjs.dev/Chat.html) and [LocalAuth](https://docs.wwebjs.dev/LocalAuth.html).
- [Tailscale Serve](https://tailscale.com/docs/features/tailscale-serve) and [Serve CLI](https://tailscale.com/docs/reference/tailscale-cli/serve).
- [Android Gradle Plugin 8.9 compatibility](https://developer.android.com/build/releases/agp-8-9-0-release-notes) and [Android Keystore](https://developer.android.com/privacy-and-security/keystore).

WhatsApp Web automation uses an unofficial library and can stop working when WhatsApp changes; review the upstream project's account-use guidance before relying on it.
