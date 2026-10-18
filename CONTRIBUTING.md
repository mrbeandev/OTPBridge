# Contributing

Use synthetic authenticator secrets only. Read the security model and wire specification before modifying pairing, crypto, approval or insertion behavior.

## Checks

```sh
pnpm install --frozen-lockfile
pnpm check
cd app
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Protocol changes require matching Kotlin and TypeScript behavior, cross-language fixtures, a versioning decision, and tampering/replay/lifetime regression tests. Fixtures contain clearly labeled public test keys; never copy them into runtime defaults. Generate them with `node --experimental-transform-types packages/protocol/scripts/generate-fixtures.ts` using Node 24.

Maintain separate Android and JavaScript build boundaries. Prefer small changes and constructor injection over new infrastructure. Never introduce analytics, push dependencies, cloud seed storage, auto-submit, broad permanent website permissions, or an insecure fallback without an explicit design/security review.

Commit messages use conventional commits. Do not commit generated build artifacts, local SDK paths, signing keys, `.env` files, agent instructions or local project-memory notes. Do not publish real pairing QR codes in screenshots.

CI passing is necessary, not sufficient. Record physical Android/Chrome verification separately, including OS/browser versions, biometric availability, navigation races, revocation, offline behavior and Keystore invalidation. No hardware verification is implied by emulator or unit-test success.
