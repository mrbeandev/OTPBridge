# v0.1 development acceptance checklist

This is a buildable development implementation, not an audited production release.

## Automated checks completed locally

- [x] TypeScript strict typecheck across protocol, extension and Worker.
- [x] Protocol crypto/lifetime/origin/tamper and deterministic public-vector tests.
- [x] Mailbox authorization, role isolation, expiry, duplicate, quota and cleanup tests.
- [x] Actual Miniflare/workerd concurrent mailbox/activation/revocation integration.
- [x] DOM one-shot target validation, edits/focus/removal, deadline and no-submit tests.
- [x] Chromium MV3 bundled crypto and setup smoke test.
- [x] Chromium encrypted pairing and synthetic phone approval/fill; both SAS gates; lost activation/ready response recovery and stable-ID retries.
- [x] Android RFC TOTP, canonical transcript and strict JSON/channel identity unit tests.
- [x] Android lint, debug APK and instrumentation APK compile.
- [x] Runtime npm dependency audit: no known vulnerabilities at check time.
- [x] Local conventional Git milestone commits; local agent instructions/project-memory/cache/artifacts ignored.

Browser tests use an isolated copied extension with preauthorized test hosts and a captured production command callback. The production bundle contains no test hooks or permanent all-site grants. These tests do not validate native permission dialogs or real keyboard/context-menu event delivery.

## Explicitly deferred: requires user/device or deployment

- [ ] Run compiled native crypto interoperability instrumentation on a device/emulator. TypeScript fixtures and Android transcript unit tests are NOT a substitute for native libsodium execution.
- [ ] Real strong biometric enrollment, per-approval CryptoObject authorization, cancellation and key invalidation.
- [ ] Scan account/pair QR with the camera; compare SAS on real phone/browser.
- [x] Deploy relay to the user-confirmed Cloudflare account on the free plan; live health, mailbox authentication/role isolation/delivery/duplicate/deletion smoke checks pass. Quota-exhaustion/load behavior remains untested.
- [ ] Complete Chrome → deployed relay → phone → original input acceptance.
- [ ] Real first/second context-menu action, shortcut delivery, activeTab grant and same-origin frame behavior.
- [ ] Android background/rotation/process-death races and queued-response recovery on real hardware.
- [ ] Revocation during pending biometric approval; origin binding changes; offline/quota/user-permission errors.
- [ ] Release signing, supported native ABIs/16KiB APK compatibility and store target-SDK requirements.
- [ ] Independent security review before important authenticator accounts.

No ADB installation or testing was performed after the user deferred device testing. Cloudflare deployment and live synthetic transport smoke checks completed on 2026-10-19. No remote CI run, store publication or Git push is claimed.

## Intentionally not implemented in v1

Notifications/push/background phone polling, secret sync, backup/export, Google Authenticator migration imports, split-digit widgets, cross-origin frames, automatic submission, clipboard filling and forward-secret ratcheting.

Keep service recovery codes/another authenticator. Phone loss, uninstall or biometric-key invalidation can lose the local vault.
