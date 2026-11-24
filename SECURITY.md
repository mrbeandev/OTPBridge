# Security model and reporting

## Development status

OTPBridge is an early, unaudited release. Keep your services' recovery codes or another authenticator as a backup. Automated tests do not certify hardware-backed key behavior, browser integration, phishing resistance or absence of vulnerabilities.

**Reporting a vulnerability:** use GitHub's private vulnerability reporting ("Report a vulnerability" on the Security tab). Do not open a public issue for security problems. There is no response-time commitment.

Never include real TOTP seeds, codes, pairing/setup QR codes or screenshots, device tokens, extension storage dumps or decrypted payloads in issues. The diagnostics reports exported by the app and extension are redacted for this purpose.

## Protected data

- Android stores encrypted TOTP account records. A separate local display catalog contains only account IDs, issuer/label, linked website domain and TOTP settings, never secrets or codes; it is intentionally readable without biometrics so choosing an account does not cause a second prompt. Existing vaults need one authorized migration unlock. Deletion requires confirmation plus biometric vault access and updates catalog/encrypted records together.
- The vault key is wrapped by an Android Keystore key requiring strong biometric-authorized key use.
- **Unlock once per visit** is on by default (users can turn it off for a fresh biometric `CryptoObject` unwrap per reveal/approval). When on: one CryptoObject unwrap retains a copied data key only in foreground process memory, reveals live codes and authorizes explicit sends during that visit. Backgrounding/activity destruction locks and wipes session keys/codes; only the mode preference persists. Cancelled startup unlock leaves the session locked. This deliberately increases the foreground exposure window for convenience; users who prefer per-action biometrics should turn it off. Deletion still needs a fresh biometric. A UI-only authenticated flag never replaces actual key authorization.
- Android generates codes locally after approval. The extension receives a single current code, not the seed.
- Pairing pins per-browser public keys. The one-time QR bootstrap authenticates the pairing transcript; the user scans the secret QR from a browser they control, reviews its chosen name/browser/OS/relay details, then approves once on the phone. No SAS comparison is required by this UI. The secret QR is now the sole out-of-band identity channel: copied/substituted QR or compromised screen/device is outside that assurance. The browser completes only after decrypting phone key confirmation.
- Directional libsodium `crypto_kx` session keys encrypt messages with XChaCha20-Poly1305. Random nonces and authenticated routing headers prevent silent envelope substitution.
- Deadlines, request IDs, origin/account bindings and replay records are validated by endpoints, independently of relay behavior.

## Untrusted relay

A malicious Cloudflare account/operator can observe IP addresses, ciphertext size/timing, channel association, role and delivery attempts. It can drop, delay, replay or reorder traffic, exhaust quotas and deny service. It should not be able to read or modify authenticated request/response contents or substitute a paired endpoint key.

There is **no forward secrecy**: compromising retained pairing private/session keys can expose previously recorded session ciphertext. Keys are not rotated by a ratchet. A relay compromise does not require abandoning the encrypted Android vault, but operators should replace transport credentials and investigate affected pairings.

Transport bearer capabilities are separate from E2EE identities. Possessing a role token does not authorize an OTP approval but can permit mailbox access/denial of service. HTTPS is still mandatory. The QR includes the phone role token and must be kept secret.

## Endpoint boundaries

- A compromised/rooted phone or malicious application build can expose seeds and codes. Biometric/key protection is not a defense against all rooted-device attacks.
- A compromised browser, extension update or browser profile can expose session keys and delivered codes. Browser keys are local extension storage, not hardware-protected secrets.
- The destination website can read an OTP after insertion. TOTP can be phished and relayed in real time. Exact-origin mapping is a useful safeguard, not cryptographic origin-bound authentication. Prefer passkeys when available.
- Page context-menu events must be trusted browser events; the extension must not expose a page-callable request API.
- Destination identity is tab + frame + document + original field; navigation, replacement, user edits or lost focus cause fail-closed insertion.

## Push notifications

Push is optional (Firebase Cloud Messaging). When a browser posts a request, the relay sends a data-only message `{t:"wake", c:<channel id prefix>}`: no site, account, code or ciphertext. Google can observe that a wake-up happened and its timing. The app only shows a local notification; it never contacts the relay, unlocks the vault or reads requests in the background. Fetching, review and biometric approval happen after you open the app. The relay stores each phone's FCM token in that channel's mailbox and throttles wake-ups to one per 5 seconds. Firebase analytics and advertising-ID collection are disabled. The relay's service-account key is a deployment secret with only the "Firebase Cloud Messaging API Admin" role.

## Site lookup for new accounts

When adding an account, the phone may send an encrypted `site.query` to paired browsers. A browser answers only if the user enabled "Link new accounts to this website" (Chrome's optional `tabs` permission), and only with the active tab's HTTPS origin (scheme + host), never the path or query. Non-HTTPS and internal pages are reported as "no site". The phone accepts answers only to queries it sent, and the result is only a prefill the user can change.

## Remembered accounts

The browser remembers which account was last used per site and sends it as a hint. It is a preselection only: the phone user may approve a different account, which then becomes the remembered one. Origin, request, frame and expiry bindings are unaffected.

## Diagnostics

App and extension keep a bounded local event log for bug reports. Sensitive field names (codes, secrets, keys, tokens, payloads, coordinates, IPs, emails, account names) are dropped and free text is pattern-scrubbed both when an event is recorded and again when a report is exported. Only 8-character prefixes of random request IDs are kept for correlation. Nothing is uploaded automatically.

## Recovery and revocation

Phone loss/uninstall/Keystore invalidation can destroy vault access. V1 deliberately has no cloud backup or export. Keep service recovery codes and/or another authenticator. Never encourage users to remove existing recovery methods.

Revocation is authoritative on the phone: remove local trust before attempting network cleanup. A best-effort relay deletion does not replace local revocation. Re-pairing requires new explicit confirmation; never accept silently replaced keys.

## Optional request context

Extension settings control both collection and transmission of each optional detail. Domain/origin remains mandatory. Location is enabled as a preference by default but uses Chrome's required extension geolocation permission (Chrome rejects this permission as optional); the application collection toggle still gates every lookup; one-shot lookup reports browser coordinates, accuracy and captured time, not guaranteed GPS precision. No continuous watch/background tracking. IP lookup uses a narrowly scoped required api.ipify.org host permission, gated by the opt-out toggle rather than a separate grant button; that external service necessarily sees the requesting IP. The returned public IP may represent a VPN/proxy. Device name is user-chosen; OS/CPU/memory/browser hints are limited browser-exposed context, not hardware identifiers or attestation. Disabled fields are not collected; preferences are rechecked before sending if changed during lookup. Denied/unavailable fields are omitted and do not block a request. All included context stays inside authenticated E2EE envelopes and is subject to strict schemas; it is not trustworthy evidence against a compromised paired browser. A green toolbar dot means a recent compatible relay health check, not phone presence or identity verification.

Map previews load actual OpenStreetMap tiles only after tapping Load map preview; this shares the viewport with OSM. The maps-app deep link also hands coordinates to the selected provider. No automatic map network request occurs merely from receiving an OTP request. Debug builds intentionally permit screenshots/recordings during development, including visible codes; release builds retain FLAG_SECURE on app and scanner windows.

## Development controls

Do not log payloads, OTPs, seeds, bearer tokens or QR contents. Disable platform backup for key/secret-bearing Android data. Do not sync extension keys with Chrome sync. Bundle dependencies; no remotely hosted code. Keep credentials out of URLs and version control. Any new push payload field, export, caching or background feature needs a revised threat model and tests.

## Release checks

Run the full test/build suite, inspect dependencies/licenses and permissions, verify protocol vectors in both languages, test lifecycle/navigation/replay rejection, and perform real-device biometric/key-invalidation checks. An independent security review is required before claiming production-grade security.
