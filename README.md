# Chat Relay

A personal WhatsApp relay: WhatsApp Web runs on your computer, and a native Android app reads and sends messages over your private Tailscale network.

The Android UI uses an original cream-and-teal palette, rounded initial avatars, a searchable conversation list, unread counts and simple message bubbles. There are no “All / Unread / Groups” filter tabs, WhatsApp logos, or copied artwork. This is an independent project, unaffiliated with WhatsApp or Meta; visual differences are not a legal clearance.

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

Paste the generated value into `RELAY_TOKEN` in `server/.env`. Keep it private; you will enter the same value on the phone. Leave `PORT=3000` unless you need another local port.

```sh
npm start
```

Scan the QR code printed in the terminal using WhatsApp → **Settings → Linked devices → Link a device** (the menu placement depends on your phone). Wait for **WhatsApp is ready**. The QR is deliberately local; it is never sent through the relay API. On subsequent launches, `LocalAuth` restores the session from `server/.wwebjs_auth/`.

Keep this process running and the computer awake. Stop it with Ctrl+C. Restart after a WhatsApp disconnect or authentication failure. To link another account, stop the server, unlink the device in WhatsApp, remove `server/.wwebjs_auth/`, then start again.

On Linux, install the system libraries needed by Chrome/Puppeteer. For an existing browser, set `CHROME_PATH` in `.env`. If you skip the Puppeteer download (`PUPPETEER_SKIP_DOWNLOAD=true npm ci`), you must supply a working Chrome executable. Run as a normal user with the browser sandbox enabled.

## 2. Expose the server privately with Tailscale

Install and sign in to [Tailscale](https://tailscale.com/download) on both devices. On the computer, run:

```sh
tailscale serve --bg http://127.0.0.1:3000
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

## Included behavior and limits

- Personal and group conversations, search by name or last-message text, timestamps and unread counts.
- Latest 60 available messages per conversation, incoming/outgoing bubbles, group sender IDs, text sends up to 4096 characters, and marking a conversation read when opened.
- Foreground polling every four seconds; polling stops when the activity is paused. There are no background notifications yet.
- Media captions can be read, but attachments are shown as placeholders. Media upload/download, calls, reactions, starting chats with new contacts, and older-history pagination are not implemented.
- Message history is fetched from WhatsApp through the server; the Android app does not persist a local message database. Unsaved drafts are lost on navigation/recreation. Screenshots and Android backups are disabled.
- A timed-out send can still have reached WhatsApp. The client keeps the text and warns you to check the conversation before retrying; it never automatically retries sends.
- The relay terminates message access on your computer and phone. Tailscale encrypts the network leg; this is not a claim of additional end-to-end encryption between these two apps.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Cannot connect | Tailscale connected on both devices; computer awake; Node process running; correct Serve URL; tailnet policy allows 443. |
| Invalid access token | Same token in `.env` and Android settings; restart server after editing `.env`. |
| WhatsApp `qr` / `syncing` | Scan the latest terminal QR and wait for session synchronization. |
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

GitHub Actions also installs server dependencies, runs these tests, builds/lints the Android app and uploads a debug APK. A live end-to-end check requires your own WhatsApp linking and tailnet: send from the app, confirm in WhatsApp, reply from another account, and confirm it appears after the next poll. Disconnect Tailscale and verify the app reports a connection failure.

Local verification during implementation: eight API integration tests passed. An Android build and a live WhatsApp/Tailscale session were not run because the authoring environment had no JDK/Android SDK or linked account.

### Dependency status

`whatsapp-web.js` 1.34.7 pins Puppeteer 24.38.0. At implementation time, `npm audit` reported high-severity transitive findings in its browser download/extraction and proxy dependency chain. A patched `basic-ftp` override is included, leaving five high-severity findings in the extraction dependency chain; remaining upstream findings are not silently suppressed or fixed by forcing an untested Puppeteer major version. Review `npm audit` before deployment and update the upstream library when a compatible fix is available. This implementation uses only `LocalAuth` and does not accept browser archives or proxy configuration through its API.

## API

All requests require `Authorization: Bearer <RELAY_TOKEN>`; errors are JSON `{ "error": "…" }`.

| Method | Path | Result |
| --- | --- | --- |
| GET | `/api/status` | WhatsApp ready flag and connection state |
| GET | `/api/chats` | `{ chats: [...] }`, newest first |
| GET | `/api/chats/:id/messages` | `{ messages: [...] }`, oldest first, latest 60 |
| POST | `/api/chats/:id/messages` | Body `{ "text": "Hello" }`; 201 with `{ message: {...} }` |
| POST | `/api/chats/:id/read` | Mark conversation seen |

URL-encode the serialized WhatsApp chat ID, such as `123456789%40c.us`. Non-status endpoints return 503 until WhatsApp is ready. The provider adapter is isolated in `server/src/whatsapp.js` for future messaging integrations.

## References

- [whatsapp-web.js Client API](https://docs.wwebjs.dev/Client.html), [Chat API](https://docs.wwebjs.dev/Chat.html) and [LocalAuth](https://docs.wwebjs.dev/LocalAuth.html).
- [Tailscale Serve](https://tailscale.com/docs/features/tailscale-serve) and [Serve CLI](https://tailscale.com/docs/reference/tailscale-cli/serve).
- [Android Gradle Plugin 8.9 compatibility](https://developer.android.com/build/releases/agp-8-9-0-release-notes) and [Android Keystore](https://developer.android.com/privacy-and-security/keystore).

WhatsApp Web automation uses an unofficial library and can stop working when WhatsApp changes; review the upstream project's account-use guidance before relying on it.
