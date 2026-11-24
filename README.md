# OTPBridge

Fill two-factor codes into Chrome straight from your Android phone. Your authenticator secrets never leave the phone, and you approve every code with your fingerprint.

> **Early release, not independently security audited.** Keep your services' recovery codes or another authenticator as a backup. Losing the phone or uninstalling the app loses its vault: there is no cloud sync or backup yet.

## Download

Get the latest files from **[Releases](../../releases/latest)**:

| File                             | What it is                        |
| -------------------------------- | --------------------------------- |
| `otpbridge-vX.Y.Z.apk`           | Android app (Android 10+)         |
| `otpbridge-extension-vX.Y.Z.zip` | Chrome extension                  |
| `SHA256SUMS.txt`                 | Checksums to verify the downloads |

## Install

**Android app**

1. Download the APK on your phone and open it. Allow installing from your browser/files app when Android asks.
2. Open OTPBridge and allow notifications, so you're alerted when a browser asks for a code.
3. Add accounts: tap **+** to scan a site's setup QR, type a setup key, or import from Google Authenticator (its _Transfer accounts_ QR codes, including screenshots) or a `.txt` file of `otpauth://` links.

**Chrome extension**

1. Download and unzip the extension ZIP.
2. Open `chrome://extensions`, turn on **Developer mode**, click **Load unpacked**, and pick the unzipped folder.
3. Open the extension's settings. The relay is already filled in; enter a name for this browser and click **Create pairing QR**.
4. In the app, open **Browsers → Pair a browser**, scan the QR, check the browser details, and tap **Connect**.

## Use it

1. On a login page, right-click the code field and choose **Fill from phone** (or focus it and press **Alt+Shift+O**).
2. Your phone shows a notification. Tap it: the request opens directly.
3. The matching account is selected for you when there's only one; otherwise choose. Tap **Send code**.
4. The code is filled in. The form is never submitted for you, and the toolbar badge shows how many seconds the code stays valid.

## Features

- **Fingerprint-protected vault.** Secrets are encrypted with a key that needs strong biometrics. _Unlock once per visit_ (on by default) asks for one fingerprint per app opening; turn it off to confirm every code.
- **End-to-end encrypted relay.** The relay only forwards encrypted messages it can't read. Push notifications carry no site, account or code, only "a request is waiting".
- **Site-aware accounts.** Link an account to its website; requests from that site preselect it. When adding an account, the phone can ask your browser which site is open to fill this in.
- **Request details.** See the requesting site, browser, approximate location and IP before approving. Each detail can be turned off in the extension settings, except the site.
- **Imports.** Google Authenticator transfer QR codes (multi-part, from camera or screenshots) and plain-text `otpauth://` exports.
- **Privacy-safe diagnostics.** Both app and extension keep a local log you can export as a `.txt` file for bug reports. OTP codes, secrets, tokens, IPs, locations, emails and account names are removed before anything is written.

## Reporting bugs

Open an issue and attach the diagnostics reports:

- **App:** _Browsers_ tab → bug icon → **Share report file**.
- **Extension:** settings → _Diagnostics_ → **Download report (.txt)**.

Never post pairing QR codes, setup QR codes or screenshots showing codes.

## How it works

```text
Chrome extension ──encrypted──▶ Cloudflare relay ──push "request waiting"──▶ Android app
        ▲                                                                    │
        └────────────────encrypted one-time code (after fingerprint)◀────────┘
```

Pairing exchanges keys through the QR code (libsodium `crypto_kx`); every message is encrypted with XChaCha20-Poly1305. The relay sees only routing metadata such as timing and sizes. Details: [SECURITY.md](SECURITY.md) and the [wire protocol](packages/protocol/spec/wire.md).

OTPBridge makes typing codes easier. It does **not** make TOTP phishing-resistant: always check the site shown on your phone before sending.

## Self-hosting the relay

The extension defaults to the public relay `https://otpbridge.mrbean.dev`. To run your own on Cloudflare's free plan:

```sh
corepack enable && pnpm install --frozen-lockfile
# Edit worker/wrangler.jsonc: remove or replace the otpbridge.mrbean.dev route, set your own name.
pnpm --filter @otpbridge/worker exec wrangler login
pnpm --filter @otpbridge/worker run deploy
```

Then enter your Worker URL in the extension settings before pairing.

Push notifications are optional and need a Firebase project. The official app only receives pushes sent with the official project's key, so a self-hosted relay works without notifications unless you also build the app with your own `google-services.json` and set your relay's `FCM_SERVICE_ACCOUNT` secret. Without push, open the app to see waiting requests.

## Building from source

Requires Node 24, pnpm 10, Java 21 and the Android SDK.

```sh
pnpm install --frozen-lockfile
pnpm check                      # typecheck, unit tests, extension + worker builds
pnpm exec playwright install chromium && pnpm test:browser

cd app && ./gradlew :android:testDebugUnitTest :android:assembleDebug
```

- Extension output: `extension/dist`.
- Debug APK: `app/android/build/outputs/apk/debug/android-debug.apk`.
- Builds without `app/android/google-services.json` simply have no push.
- Release builds need your own signing key in `secrets/release-signing.properties`; see `app/android/build.gradle.kts`.

```text
app/                 Android app (Kotlin, Jetpack Compose)
extension/           Chrome extension (Manifest V3, TypeScript, React)
worker/              Cloudflare Worker + Durable Object mailboxes
packages/protocol/   Shared protocol, crypto and public test vectors
tests/               Browser integration tests
```

## Limitations

- Ordinary single code inputs on HTTPS sites. Split one-digit-per-box widgets and cross-origin frames are not supported.
- One phone per browser; a phone can pair several browsers.
- TOTP only (no HOTP/SMS). Codes are never auto-submitted or copied to the clipboard.

## License

[Apache 2.0](LICENSE)
