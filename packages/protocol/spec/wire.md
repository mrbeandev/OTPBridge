# OTPBridge wire protocol v1 (implementation contract)

All binary fields use URL-safe base64 without padding. UTF-8 JSON plaintext. Integers are epoch milliseconds. HTTPS relay URLs only (loopback HTTP development allowed). IDs and capabilities are 32 random bytes encoded base64url. No secret in logs.

## Transport
POST /v1/channels with JSON `{}` => `{channelId,browserToken,phoneToken,expiresAt}` (authoritative server pairing deadline; use this in QR). Channel creation rate limited; role credentials 32 random bytes. Server stores hashes. Initially channel expires in 5 minutes; authenticated POST /v1/channels/:id/activate makes it a device channel (90 day inactivity expiry).
Bearer browserToken or phoneToken on all channel routes. POST /v1/channels/:id/messages `{id,expiresAt,payload}` -> 201. payload is opaque string <= 16384 bytes. GET messages?after=N -> `{messages:[{sequence,id,expiresAt,payload}],cursor}`. Only peer role messages returned. Server expiry max 120 seconds for normal messages, 300 seconds pairing; bounded queue. No ack deletion needed; DELETE channel revokes. POST /ack may be added later; cursor is local not shared. IDs unique within channel; duplicate POST 409. Each role can read peer only. POST /activate browser role only; idempotent. Browser persists a single pair.ready envelope with <=90-second TTL, activates the channel after both SAS confirmations, then sends readiness; phone enables requests only after decrypting readiness. A 409 response to the exact previously persisted envelope is treated as already delivered. Expiration always checked locally too.

## QR
JSON `{type:"otpbridge.pair",v:1,relay,channelId,browserToken omitted,phoneToken,bootstrap,browserPublicKey,browserName,expiresAt}`. browserToken MUST NOT be in QR. Phone sees only phone role credential. browserPublicKey is libsodium crypto_kx public key (32B), bootstrap random32B. QR only displayed by extension, bootstrap never sent plaintext through relay. Confirmation UI on both endpoints required.

## Pair response
Phone generates per-pair crypto_kx keypair. Pair response is bootstrap-encrypted using XChaCha20Poly1305 IETF (32B key,24B random nonce): payload string JSON `{kind:"pair",v:1,nonce,ciphertext}`. AAD UTF8 `otpbridge:pair:1:<channelId>`. Plaintext `{type:"pair.response",v:1,channelId,browserPublicKey,phonePublicKey,browserName,phoneName,expiresAt}`. Check all values and deadline; reject > QR deadline. Transcript canonical UTF8 JSON array `[1,channelId,browserPublicKey,phonePublicKey,browserName,phoneName,expiresAt]`. SAS = first 3 bytes SHA256(transcript), decimal zero-padded 8 digits (value <=16777215), displayed xxxx xxxx. Both endpoints explicitly confirm same SAS. Phone posts pair.confirm encrypted with session tx key only after user confirms SAS; browser also requires local SAS confirmation before marking active. Confirmation plaintext includes type pair.confirm, requestId channelId, createdAt, expiresAt. Browser replies session pair.ready on local confirmation only; Android must not enable approvals until valid pair.ready received. Session derivation: browser crypto_kx_client_session_keys; phone crypto_kx_server_session_keys. rx/tx directions as sodium API. Bootstrap discarded after successful handshake; pending state times out after 5m.

## Session envelope
payload JSON `{kind:"session",v:1,channelId,id,expiresAt,nonce,ciphertext}`. AAD UTF8 `otpbridge:session:1:<channelId>:<id>:<expiresAt>`. ciphertext from sodium crypto_aead_xchacha20poly1305_ietf_encrypt, key tx. Receiver rx decrypt; validate all header fields, schema and payload expiry. Session envelope id equals transport id; transport expiresAt equals envelope expiresAt. Request/response app id may differ; requestId binds operation. Reject replayed envelope IDs persistently; sender retries same transport message never re-encrypt same id.

## Application messages
Common `{type,requestId,createdAt,expiresAt}`. createdAt <= now+30000; expiresAt > now; expiresAt-createdAt <=90000 (pair messages <=300000). No processing stale requests.
- fill.request adds `{browserName,origin,frameOrigin,accountRef?}`. Actual HTTPS origin, not page label; top origin must equal frameOrigin in v1. Phone displays authenticated configured browserName, rejects message browserName mismatch. User picks account; explicit origin binding saved on approval. Existing opaque ref must match locally stored origin mapping. Request lifetime 90s.
- fill.approved adds `{origin,frameOrigin,accountRef,otp,validFrom,validUntil}`. Response expiresAt <= request expiresAt AND validUntil. Validate six/eight digits and interval; no fill if <2s code life. Original requestId, origin and frameOrigin must match pending request.
- fill.denied adds `{reason}` bounded enum denied/account-mismatch/unavailable.
- request.cancelled, device.revoked no additional fields. Revocation is authoritative locally even if relay blocks.

Android accountRef random ID; no account name to browser. Browser learns approved accountRef and saves exact-origin mapping. Never trusts accountRef to bypass user approval. Android stores exact origin mapping to account, rejects unknown refs.

## Security boundaries
Relay knows channel relationships, IP, time, sizes; contents encrypted. No forward secrecy. Endpoints retain local replay IDs until deadline+5min. Browser identity keys scoped to pairing. No remote code/CORS-based authentication assumptions. QR secrecy and physical comparison authenticate pairing. This draft needs independent review before production use.
