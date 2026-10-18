# OTPBridge

Phone-approved TOTP autofill for Chrome, with a self-hosted, content-blind Cloudflare relay.

**Early development — not independently security audited.** Do not migrate important authenticator accounts yet. Physical-device testing is deliberately deferred. Use synthetic test accounts until the release acceptance checklist passes.

## How it works

1. Right-click a supported OTP input and choose **Fill from phone**, or focus it and press **Alt+Shift+O**.
2. Manually open the Android app. It fetches pending encrypted requests while foregrounded.
3. Review the exact website, browser and account, then approve with strong biometrics.
4. Your phone generates one TOTP, encrypts the response, and Chrome fills the original input without submitting.

**No push service, Firebase, notification permission, Android background service or background phone polling.** TOTP secrets stay on Android. Cloudflare can see network/routing metadata but cannot decrypt application payloads. This does not make TOTP phishing-resistant.

## Repository

```text
app/                 Native Kotlin / Jetpack Compose Android app (Gradle)
extension/           Chrome Manifest V3 extension (TypeScript / React)
worker/              Cloudflare Worker + SQLite Durable Object mailboxes
packages/protocol/   Versioned protocol, crypto implementation and public test vectors
tests/               Cross-component browser and relay acceptance checks
.github/workflows/   Automated build and test checks
```

The Android build is independent of pnpm. Protocol fixtures are language-neutral JSON; implementation code is intentionally not shared between Kotlin and TypeScript.

## Development

Requires Node 24, pnpm 10, Java 21 and an Android SDK for the native app.

```sh
corepack enable
pnpm install --frozen-lockfile
pnpm check
```

`pnpm build` produces `extension/dist` and performs a Worker dry-run. It does **not** deploy anything or test a phone.

### Deploy your relay

```sh
pnpm --filter @otpbridge/worker exec wrangler login
pnpm --filter @otpbridge/worker deploy
```

Choose a unique Worker name in `worker/wrangler.jsonc` if needed. The deployment uses a SQLite-backed Durable Object, supported on Cloudflare's free plan. No domain, Firebase project or external database is required. Free quotas still apply; this is not a promise of unlimited free hosting.

Visit `https://YOUR-WORKER.workers.dev/health`; it should return `{"ok":true,"protocolVersions":[1]}`. Disable request/body logging in any infrastructure you add. Wrangler CLI telemetry can be disabled using `WRANGLER_SEND_METRICS=false`.

### Install the development extension

1. Run `pnpm --filter @otpbridge/extension build`.
2. Open `chrome://extensions`, enable Developer mode, choose **Load unpacked**, and select `extension/dist`.
3. Open OTPBridge settings. Enter the HTTPS relay origin and a browser name; grant permission for that relay only.
4. Create a pairing QR. Scan it in the Android app and compare all eight digits on both screens. Confirm only if they match.

The QR contains one-time secrets. Never share it, capture it in bug reports, or paste it into a web-based QR reader.

With minimal `activeTab` permissions, the **first** context-menu use on a document installs the field-capture listener and asks you to right-click the same input again. This is intentional: guessing the focused field could fill the wrong destination. The keyboard shortcut works on the explicitly focused supported field. Navigating may require granting access again.

### Android

The native application lives under `app/`, with a single module under `app/android/`. See `app/README.md` for SDK/build instructions and device-testing limitations once the native implementation is integrated.

## Supported v1 behavior

- Exact HTTPS origins and ordinary editable OTP inputs.
- Split-digit widgets are currently rejected: safe support requires binding every original field before approval. This is deferred rather than guessing destinations.
- Same-origin frames when Chrome grants access; cross-origin frames are rejected.
- One paired phone per extension profile; the phone can have multiple paired browsers.
- Explicit biometric approval for each request; 90-second request expiration.
- Standard TOTP enrollment QR/manual secrets; not OAuth, SMS, HOTP or Google Authenticator migration QR imports.

Restricted browser pages, closed shadow roots and arbitrary custom/contenteditable widgets are not supported. There is no auto-submit or clipboard fallback.

## Security and recovery

Read [SECURITY.md](SECURITY.md) and the [wire protocol](packages/protocol/spec/wire.md) before making changes. The relay is replaceable; it is still trusted for availability, not message authenticity or confidentiality. Endpoint compromise is not solved by relay encryption.

**Keep each service's recovery codes or another authenticator.** Losing the phone, uninstalling the app, or invalidating its biometric-backed key can make the local vault unrecoverable. V1 has no secret cloud sync or portable encrypted vault export. Adding a test account does not replace your existing recovery arrangements.

## Project status

Implemented components and exact validation results are tracked in release notes and CI. A successful build is not proof of real-device biometric behavior or production security. No store release or public hosted relay is included.
