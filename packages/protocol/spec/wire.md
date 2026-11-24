# OTPBridge wire protocol v1 (implementation contract)

All binary fields use URL-safe base64 without padding. UTF-8 JSON plaintext. Integers are epoch milliseconds. HTTPS relay URLs only (loopback HTTP development allowed). IDs and capabilities are 32 random bytes encoded base64url. No secret in logs.

## Transport

POST /v1/channels with JSON `{}` => `{channelId,browserToken,phoneToken,expiresAt}` (authoritative server pairing deadline; use this in QR). Channel creation rate limited; role credentials 32 random bytes. Server stores hashes. Initially channel expires in 5 minutes; authenticated POST /v1/channels/:id/activate makes it a device channel (90 day inactivity expiry).
Bearer browserToken or phoneToken on all channel routes. POST /v1/channels/:id/messages `{id,expiresAt,payload}` -> 201. payload is opaque string <= 16384 bytes. GET messages?after=N -> `{messages:[{sequence,id,expiresAt,payload}],cursor}`. Only peer role messages returned. Server expiry max 120 seconds for normal messages, 300 seconds pairing; bounded queue. No ack deletion needed; DELETE channel revokes. POST /ack may be added later; cursor is local not shared. IDs unique within channel; duplicate POST 409. Each role can read peer only. POST /activate browser role only; idempotent. Browser persists a single pair.ready envelope with <=90-second TTL, activates the channel after authenticated phone SAS confirmation, then sends readiness; phone enables requests only after decrypting readiness. A 409 response to the exact previously persisted envelope is treated as already delivered. Expiration always checked locally too.

## QR

JSON `{type:"otpbridge.pair",v:1,relay,channelId,browserToken omitted,phoneToken,bootstrap,browserPublicKey,browserName,browserInfo?,deviceInfo?,expiresAt}`. browserToken MUST NOT be in QR. Phone sees only phone role credential. browserPublicKey is libsodium crypto_kx public key (32B), bootstrap random32B. QR only displayed by extension, bootstrap never sent plaintext through relay. User approves a detailed mobile sheet after scanning their own browser QR; no separate matching-code step. QR secrecy/authenticity is the out-of-band trust anchor.

## Pair response

Phone generates per-pair crypto_kx keypair. Pair response is bootstrap-encrypted using XChaCha20Poly1305 IETF (32B key,24B random nonce): payload string JSON `{kind:"pair",v:1,nonce,ciphertext}`. AAD UTF8 `otpbridge:pair:1:<channelId>`. Plaintext `{type:"pair.response",v:1,channelId,browserPublicKey,phonePublicKey,browserName,phoneName,expiresAt}`. Check all values and deadline; reject > QR deadline. Transcript canonical UTF8 JSON array `[1,channelId,browserPublicKey,phonePublicKey,browserName,phoneName,expiresAt]`. SAS = first 3 bytes SHA256(transcript), decimal zero-padded 8 digits (value <=16777215), displayed xxxx xxxx. SAS derivation remains for compatibility/test vectors but is not presented for comparison. The user instead reviews browser name/details/relay and explicitly approves the scanned QR on the phone; the response and pair.confirm are queued durably in order. Phone posts pair.confirm encrypted with session tx key after confirmation; browser authenticates this message and completes pairing automatically, with no second browser click. Confirmation plaintext includes type pair.confirm, requestId channelId, createdAt, expiresAt. Browser replies session pair.ready after authenticated phone confirmation; Android must not enable approvals until valid pair.ready received. Session derivation: browser crypto_kx_client_session_keys; phone crypto_kx_server_session_keys. rx/tx directions as sodium API. Bootstrap discarded after successful handshake; pending state times out after 5m.

An explicit rejection before pairing sends the same bootstrap-encrypted `kind:"pair"` box/AAD with plaintext `{type:"pair.denied",v:1,channelId,browserPublicKey,browserName,expiresAt,reason:"denied"}`. Browser validates all QR bindings and expiry before showing denial. No trust/device keys are established on rejection. Back/dismissal is not a denial. Denial transport failure is reported locally; browser may still show pending until QR expiry rather than a fabricated rejection.

## Session envelope

payload JSON `{kind:"session",v:1,channelId,id,expiresAt,nonce,ciphertext}`. AAD UTF8 `otpbridge:session:1:<channelId>:<id>:<expiresAt>`. ciphertext from sodium crypto_aead_xchacha20poly1305_ietf_encrypt, key tx. Receiver rx decrypt; validate all header fields, schema and payload expiry. Session envelope id equals transport id; transport expiresAt equals envelope expiresAt. Request/response app id may differ; requestId binds operation. Reject replayed envelope IDs persistently; sender retries same transport message never re-encrypt same id.

## Application messages

Common `{type,requestId,createdAt,expiresAt}`. createdAt <= now+30000; expiresAt > now; expiresAt-createdAt <=90000 (pair messages <=300000). No processing stale requests.

- fill.request adds `{browserName,origin,frameOrigin,accountRef?,context?}`. Optional context is a strict object with `device?:{name,os?,hardwareConcurrency?,memoryGb?}`, `browser?:{name,version,language?}`, `publicIp?:string`, `location?:{latitude,longitude,accuracyMeters,capturedAt}`. It is encrypted, bounded, browser-claimed context, never attestation. Coordinates require explicit browser permission; collection toggles live only in extension settings, domain cannot be omitted. Older apps reject unknown context fields and must update before enabling this feature. Actual HTTPS origin, not page label; top origin must equal frameOrigin in v1. Phone displays authenticated configured browserName, rejects message browserName mismatch. User picks account; explicit origin binding saved on approval. Existing opaque ref must match locally stored origin mapping. Request lifetime 90s.
- fill.approved adds `{origin,frameOrigin,accountRef,otp,validFrom,validUntil}`. Response expiresAt <= request expiresAt AND validUntil. Validate six/eight digits and interval; no fill if <2s code life. Original requestId, origin and frameOrigin must match pending request.
- fill.denied adds `{reason}` bounded enum denied/account-mismatch/unavailable.
- request.cancelled, device.revoked no additional fields. Revocation is authoritative locally even if relay blocks.

Android accountRef random ID; no account name to browser. Browser learns approved accountRef and saves exact-origin mapping. Never trusts accountRef to bypass user approval. Android stores exact origin mapping to account, rejects unknown refs.

## Security boundaries

Relay knows channel relationships, IP, time, sizes; contents encrypted. No forward secrecy. Endpoints retain local replay IDs until deadline+5min. Browser identity keys scoped to pairing. No remote code/CORS-based authentication assumptions. QR secrecy and physical comparison authenticate pairing. This draft needs independent review before production use.
