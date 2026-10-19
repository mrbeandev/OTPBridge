# Android app

Native Kotlin / Jetpack Compose, Android 10+ (API 29), compile/target API 35. Independent Gradle project inside the monorepo.

## Build

Requires Java 21 (Java 17 bytecode), Android SDK platform/build-tools 35 and Internet access for Maven dependencies.

```sh
cd app
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew :android:testDebugUnitTest :android:lintDebug :android:assembleDebug :android:assembleDebugAndroidTest
```

The debug APK is `android/build/outputs/apk/debug/android-debug.apk`. Device installation is a separate explicit action; builds do not install anything. Instrumentation can be run with `./gradlew :android:connectedDebugAndroidTest` **only after coordinating device installation**.

For a sandbox with a read-only home, use a writable `GRADLE_USER_HOME` and `ANDROID_USER_HOME`, and `-Dorg.gradle.native=false -Pkotlin.compiler.execution.strategy=in-process`. The build may warn about Android metrics initialization; no application telemetry is included.

## Architecture

- `MainActivity.kt`: Compose screens, QR scanner, foreground lifecycle and explicit approval UI.
- `totp/Totp.kt`: strict standard TOTP enrollment parser and RFC 6238 generation; SHA1/256/512, six/eight digits, 15–120-second periods.
- `vault/Vault.kt`: strong-biometric `BiometricPrompt.CryptoObject` wraps/unwraps a random AES vault key with Android Keystore. Secrets remain in biometric-protected encrypted records. A separate seed-free catalog exposes labels/issuer/settings locally without unlocking. Existing accounts migrate after one authorized unlock. Default mode performs one fresh authorized unwrap per approval; choosing an account needs no biometric. Optional persistent **Unlock once per visit** mode requests one real biometric on each foreground visit, keeps an authorized data-key copy only in memory, reveals all live codes and allows explicit sends without another prompt. Leaving the app wipes session keys/codes while retaining only the enabled preference. Account deletion requires explicit confirmation plus biometric access and updates encrypted records/catalog together.
- `data/DeviceStore.kt`: independently device-encrypted browser pairing/transport state, usable for fetching before vault unlock.
- `protocol/SodiumCrypto.kt`: LazySodium 5.2.0 raw crypto_kx / XChaCha20-Poly1305 adapter.
- `protocol/PhoneBridge.kt`: shared v1 protocol, QR bootstrap, exact SAS transcript, confirmed browser gating, channel-scoped immutable reviewed requests and atomic durable terminal/outbox transitions.
- `protocol/StrictJson.kt`: exact fields, noncoercive types, safe integers and Unicode validation; crypto adapter checks canonical base64url.
- `SecureCaptureActivity.kt`: nonexported QR scanner with screenshot/recording protection.
- `relay/RelayClient.kt`: HTTPS-only authenticated, bounded HTTP transport. Redirects are rejected.

Account IDs are random 32-byte base64url references. The relay never receives account names or TOTP secrets. Pairing keys and role credentials remain separate from biometric vault access. Request browsing does not unlock the vault.

## Current behavior

Home combines accounts and incoming requests. Selecting a request opens a dedicated full-screen Review request page with Back navigation, one normally scrolling content area and a fixed Deny/Send footer. Scrolling has no dismissal gesture. Review account search appears only when more than four eligible accounts exist; it matches issuer/label/email and clears a selection hidden by a changed filter. Back returns to Home without denying the pending request; cancellation/revocation/expiry still invalidate the review through the existing request lifecycle. A foreground live clock updates request deadlines, inline code seconds and progress bars. Default Reveal replaces dots inside its account card until that code expires; it no longer holds the global loading indicator during a 15-second sleep. In unlocked session mode codes rotate automatically at each TOTP boundary. Open the app to fetch requests. While Home or a request review is visible, or a pairing is awaiting readiness, polling runs every 2.5 seconds only while the activity is STARTED. There is no notification permission, push provider, WorkManager task, background service or permanent connection. The app fetches once on each opening. On stop it cancels foreground jobs, disconnects active HTTP requests, closes the HTTP foreground gate and cancels biometric prompts. Already-committed encrypted responses can retry on the next foreground opening; cancellation cannot retract bytes already accepted by a remote relay.

Standard setup QR scanning uses bundled ZXing rather than CameraX/MLKit, avoiding Google Play Services and online model downloads. Camera access is used only when scanning. Manual enrollment supports algorithm, digits and period. Google Authenticator migration QR is not supported.

Scanning the QR opens a dedicated full-screen Connect browser page: chosen name, bundled browser icon and friendly browser/OS label, concise permission guidance and fixed connect/cancel actions. Raw user-agent/platform data lives under Technical details. User approval of their own scanned QR is sufficient; no matching-code step. The extension completes automatically after authenticated phone confirmation. The phone does not accept requests before authenticated `pair.ready`. Requests require exact HTTPS origins and same-origin frames; account references are checked against phone-local bindings. Enrollment and approval have no PIN-only fallback. Local revoke takes effect even if relay deletion fails.

Map preview uses a bundled raster-tile document (no external iframe/scripts) only after explicit Load map preview. It shows a loading indicator, waits for actual center-tile load, and exposes error/retry states for timeout, TLS/network failure or provider-denied placeholder responses. Requests are bounded and restricted to the OSM tile host; no coordinate URLs enter diagnostics. Coarse location accuracy zooms out rather than presenting a precise street-level marker. Open in maps app first launches an Android `geo:` intent and respects the system's chosen maps handler. Only missing geo handlers fall back to OpenStreetMap in a browser, with an explanatory message; security restrictions are reported instead of silently changing provider.

## Verification and limitations

- Unit tests cover all RFC 6238 SHA1/SHA256/SHA512 vectors, enrollment parsing/rejection and counter boundaries.
- `CryptoInteropTest` is an instrumentation test using public shared TypeScript fixtures. It checks native crypto_kx directional keys, bootstrap decryption, SAS, session decryption and tamper rejection. It passed on the connected RMX3998 physical device on 2026-10-19 (1 instrumentation test); real fingerprint and full lifecycle acceptance remain separate.
- Real biometric key properties, camera UX, lifecycle and full browser/relay end-to-end behavior require physical-device/manual validation. Do not claim those pass based on a build.
- No secret backup/export yet. The app warns about phone loss, uninstall, and biometric-key invalidation; retain service recovery codes. Platform backup is disabled.
- No Room database dependency yet: the small v1 vault/device state uses encrypted private SharedPreferences blobs. Revisit indexed Room storage if account/device/message volume warrants it.
- JVM strings cannot be reliably zeroized; byte arrays are cleared on a best-effort basis. TOTP secrets briefly exist in process memory after approval.
- No forward secrecy, phishing resistance or compromised-endpoint protection claim. Code intentionally needs further adversarial review before real important accounts.
- Before store publication: dependency/SBOM/license review, release signing, native ABI testing and independent security review. LazySodium is MPL-2.0; its arm64 binary was inspected with 16 KiB ELF LOAD alignment, but full APK/ABI/device compatibility still needs release verification.

App-private data is not reset automatically, and no device is installed/uninstalled by the build.
