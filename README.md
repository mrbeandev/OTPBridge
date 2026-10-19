# OTPBridge

Phone-approved TOTP autofill for Chrome, with a self-hosted, content-blind Cloudflare relay.

**Early development — not independently security audited.** Do not migrate important authenticator accounts yet. Native crypto interoperability and data-preserving upgrades have been checked on a physical device; full biometric/lifecycle acceptance is still in progress. Use synthetic test accounts until the release acceptance checklist passes.

## How it works

1. Right-click a supported OTP input and choose **Fill from phone**, or focus it and press **Alt+Shift+O**.
2. Manually open the Android app. It fetches pending encrypted requests while foregrounded.
3. Review the exact website, browser and account, then approve with strong biometrics. Optional **Unlock once per visit** remembers your mode choice: one fingerprint on each app opening reveals live codes and lets you explicitly send during that foreground visit, without another prompt.
4. Your phone generates one TOTP, encrypts the response, and Chrome fills the original input without submitting. The extension toolbar badge and popup show seconds until that filled code expires.

Home combines accounts and approvals in one compact screen. Incoming requests animate into a section above the account list, pushing accounts down; no separate Requests tab is needed. Account codes reveal inline with live seconds/progress; session mode rotates them automatically. Backgrounding always clears session keys and visible codes, but keeps the mode toggle enabled for the next opening.

**No push service, Firebase, notification permission, Android background service or background phone polling.** TOTP secrets stay on Android. Cloudflare can see network/routing metadata but cannot decrypt application payloads. This does not make TOTP phishing-resistant.

## Request details and connection status

Extension settings let you disable collection of location, public IP, device hints and browser information independently. Requesting website/domain is mandatory. Location collection starts enabled; Chrome requires `geolocation` in the extension's required permissions (it cannot be optional). Turning location off prevents every geolocation call; coordinates include accuracy/time and are not guaranteed GPS. IP uses api.ipify.org only after its host permission is granted. Denied/unavailable details are omitted. Nothing tracks location in the background. Update both app and extension to v0.4 before using context (older strict clients reject new fields).

A bottom green icon dot means the configured relay passed a recent `/health` check, not that the phone is online. Checks occur about once per minute in the extension; the numeric OTP countdown remains independent. Restore relay permission appears only if that relay host permission is missing. Existing pairings retain their relay—changing Cloudflare deployments does not migrate them; unpair/re-pair to change relays.

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
pnpm --filter @otpbridge/worker run deploy
```

Choose a unique Worker name in `worker/wrangler.jsonc` if needed. The deployment uses a SQLite-backed Durable Object, supported on Cloudflare's free plan. No domain, Firebase project or external database is required. Free quotas still apply; this is not a promise of unlimited free hosting.

Visit `https://YOUR-WORKER.workers.dev/health`; it should return `{"ok":true,"protocolVersions":[1]}`. Disable request/body logging in any infrastructure you add. Wrangler CLI telemetry can be disabled using `WRANGLER_SEND_METRICS=false`.

### Install the development extension

1. Run `pnpm --filter @otpbridge/extension build`.
2. Open `chrome://extensions`, enable Developer mode, choose **Load unpacked**, and select `extension/dist`.
3. Open OTPBridge settings. Enter the HTTPS relay origin and a browser name; grant permission for that relay only.
4. Create a pairing QR. Scan it in the Android app and compare all eight digits on both screens. Confirm **on the phone only** if they match; the extension finishes automatically.

The QR contains one-time secrets. Never share it, capture it in bug reports, or paste it into a web-based QR reader.

With minimal `activeTab` permissions, the **first** context-menu use on a document installs the field-capture listener and asks you to right-click the same input again. This is intentional: guessing the focused field could fill the wrong destination. The keyboard shortcut works on the explicitly focused supported field. Navigating may require granting access again.

### Android

The native application lives under `app/`, with a single module under `app/android/`. See [Android build instructions](<app/README.md>) for SDK setup and deferred device-testing details.

```sh
cd app
./gradlew :android:testDebugUnitTest :android:lintDebug :android:assembleDebug
```

The debug APK is produced at `app/android/build/outputs/apk/debug/android-debug.apk`. Building does not install it. Do not use real important accounts until physical-device validation and independent security review are complete.

### Browser integration checks

```sh
pnpm exec playwright install chromium
pnpm test:browser
```

The browser suite loads the actual MV3 bundle and exercises encrypted pairing, SAS comparison and authenticated phone-only confirmation, lost-response retries, and guarded filling with a synthetic phone endpoint. Native permission prompts and OS shortcut delivery are simulated only in a disposable extension copy; actual user gestures and Android biometrics remain manual acceptance checks.

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

Implemented components and deferred checks are tracked in the [release checklist](<RELEASE-CHECKLIST.md>). Local validation currently passes 27 JavaScript tests, 2 Chromium integration tests and 9 Android unit tests, plus TypeScript builds, Android lint and both APK builds. CI is configured but has not been run remotely. A successful build is not proof of real-device biometric behavior or production security. No store release or public hosted relay is included.
