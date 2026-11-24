import sodium from "libsodium-wrappers-sumo";
import { z } from "zod";
export const ready = sodium.ready;
export const VERSION = 1;
export const REQUEST_TTL = 90_000;
export const PAIR_TTL = 300_000;
export const MAX_PAYLOAD = 16_384;
// Canonical 32-byte base64url: final symbol has two zero padding bits.
export const idSchema = z
  .string()
  .regex(/^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$/);
function validUnicode(value: string): boolean {
  for (let i = 0; i < value.length; i++) {
    const c = value.charCodeAt(i);
    if (c >= 0xd800 && c <= 0xdbff) {
      const next = value.charCodeAt(++i);
      if (!(next >= 0xdc00 && next <= 0xdfff)) return false;
    } else if (c >= 0xdc00 && c <= 0xdfff) return false;
  }
  return true;
}
const name = z
  .string()
  .min(1)
  .max(80)
  .refine(validUnicode, "Invalid Unicode name");
const timestamp = z.number().int().nonnegative().safe();
export function encode(bytes: Uint8Array): string {
  return sodium.to_base64(bytes, sodium.base64_variants.URLSAFE_NO_PADDING);
}
export function decode(value: string, length?: number): Uint8Array {
  if (!/^[A-Za-z0-9_-]+$/.test(value)) throw new Error("Invalid base64url");
  const bytes = sodium.from_base64(
    value,
    sodium.base64_variants.URLSAFE_NO_PADDING,
  );
  if (
    encode(bytes) !== value ||
    (length !== undefined && bytes.length !== length)
  )
    throw new Error("Invalid binary length/encoding");
  return bytes;
}
export function randomId(): string {
  return encode(sodium.randombytes_buf(32));
}
export const keyPair = () => {
  const p = sodium.crypto_kx_keypair();
  return { publicKey: encode(p.publicKey), privateKey: encode(p.privateKey) };
};
export interface Keys {
  rx: string;
  tx: string;
}
export function sessionKeys(
  role: "browser" | "phone",
  own: ReturnType<typeof keyPair>,
  peer: string,
): Keys {
  const derive =
    role === "browser"
      ? sodium.crypto_kx_client_session_keys
      : sodium.crypto_kx_server_session_keys;
  const k = derive(
    decode(own.publicKey, 32),
    decode(own.privateKey, 32),
    decode(peer, 32),
  );
  return { rx: encode(k.sharedRx), tx: encode(k.sharedTx) };
}
export function normalizeRelay(value: string): string {
  const u = new URL(value);
  if (
    u.username ||
    u.password ||
    u.search ||
    u.hash ||
    !["https:", "http:"].includes(u.protocol)
  )
    throw new Error("Invalid relay URL");
  if (
    u.protocol !== "https:" &&
    !["localhost", "127.0.0.1", "[::1]"].includes(u.hostname)
  )
    throw new Error("Relay must use HTTPS");
  if (u.pathname !== "/")
    throw new Error("Relay must be an origin, without path");
  return u.origin;
}
export function validOrigin(value: string): boolean {
  try {
    const u = new URL(value);
    return (
      u.protocol === "https:" &&
      u.origin === value &&
      !u.username &&
      !u.password
    );
  } catch {
    return false;
  }
}
const origin = z
  .string()
  .max(2048)
  .refine(validOrigin, "Expected exact HTTPS origin");
export const pairQrSchema = z
  .object({
    type: z.literal("otpbridge.pair"),
    v: z.literal(1),
    relay: z.string().max(2048).transform(normalizeRelay),
    channelId: idSchema,
    phoneToken: idSchema,
    bootstrap: idSchema,
    browserPublicKey: idSchema,
    browserName: name,
    browserInfo: z.string().max(240).optional(),
    deviceInfo: z.string().max(240).optional(),
    expiresAt: timestamp,
  })
  .strict();
export type PairQr = z.infer<typeof pairQrSchema>;
export const pairResponseSchema = z
  .object({
    type: z.literal("pair.response"),
    v: z.literal(1),
    channelId: idSchema,
    browserPublicKey: idSchema,
    phonePublicKey: idSchema,
    browserName: name,
    phoneName: name,
    expiresAt: timestamp,
  })
  .strict();
export type PairResponse = z.infer<typeof pairResponseSchema>;
export function pairingSas(p: PairResponse): string {
  const transcript = JSON.stringify([
    1,
    p.channelId,
    p.browserPublicKey,
    p.phonePublicKey,
    p.browserName,
    p.phoneName,
    p.expiresAt,
  ]);
  const digest = sodium.crypto_hash_sha256(sodium.from_string(transcript));
  return (((digest[0]! << 16) | (digest[1]! << 8) | digest[2]!) >>> 0)
    .toString()
    .padStart(8, "0");
}
const common = {
  requestId: idSchema,
  createdAt: timestamp,
  expiresAt: timestamp,
};
const contextText = z.string().min(1).max(240).refine(validUnicode);
export const requestContextSchema = z
  .object({
    device: z
      .object({
        name: contextText,
        os: contextText.optional(),
        hardwareConcurrency: z.number().int().min(1).max(1024).optional(),
        memoryGb: z.number().min(0.25).max(1024).optional(),
      })
      .strict()
      .optional(),
    browser: z
      .object({
        name: contextText,
        version: contextText,
        language: contextText.optional(),
      })
      .strict()
      .optional(),
    publicIp: z
      .string()
      .max(45)
      .regex(/^(?:[0-9]{1,3}(?:\.[0-9]{1,3}){3}|[0-9a-fA-F:]+)$/)
      .optional(),
    location: z
      .object({
        latitude: z.number().min(-90).max(90),
        longitude: z.number().min(-180).max(180),
        accuracyMeters: z.number().min(0).max(10000000),
        capturedAt: timestamp,
      })
      .strict()
      .optional(),
  })
  .strict();
export type RequestContext = z.infer<typeof requestContextSchema>;
export const messageSchema = z.discriminatedUnion("type", [
  z
    .object({
      ...common,
      type: z.literal("fill.request"),
      browserName: name,
      origin,
      frameOrigin: origin,
      accountRef: idSchema.optional(),
      context: requestContextSchema.optional(),
    })
    .strict(),
  z
    .object({
      ...common,
      type: z.literal("fill.approved"),
      origin,
      frameOrigin: origin,
      accountRef: idSchema,
      otp: z.string().regex(/^(?:[0-9]{6}|[0-9]{8})$/),
      validFrom: timestamp,
      validUntil: timestamp,
    })
    .strict(),
  z
    .object({
      ...common,
      type: z.literal("fill.denied"),
      reason: z.enum(["denied", "account-mismatch", "unavailable"]),
    })
    .strict(),
  z.object({ ...common, type: z.literal("request.cancelled") }).strict(),
  z.object({ ...common, type: z.literal("device.revoked") }).strict(),
  z.object({ ...common, type: z.literal("pair.confirm") }).strict(),
  z.object({ ...common, type: z.literal("pair.ready") }).strict(),
  // Phone asks a paired browser which site is open, to prefill a new account's domain.
  z.object({ ...common, type: z.literal("site.query") }).strict(),
  z
    .object({
      ...common,
      type: z.literal("site.info"),
      status: z.enum(["ok", "no-permission", "no-site"]),
      origin: origin.optional(),
    })
    .strict(),
]);
export type Message = z.infer<typeof messageSchema>;
export type FillRequest = Extract<Message, { type: "fill.request" }>;
export type FillApproved = Extract<Message, { type: "fill.approved" }>;
export function validateMessage(value: unknown, now = Date.now()): Message {
  const m = messageSchema.parse(value);
  const ttl = m.type.startsWith("pair.") ? PAIR_TTL : REQUEST_TTL;
  if (
    m.createdAt > now + 30_000 ||
    m.expiresAt <= now ||
    m.expiresAt <= m.createdAt ||
    m.expiresAt - m.createdAt > ttl
  )
    throw new Error("Expired or invalid message lifetime");
  if ("frameOrigin" in m && m.origin !== m.frameOrigin)
    throw new Error("Cross-origin frames unsupported");
  if (
    m.type === "site.info" &&
    (m.status === "ok") !== (m.origin !== undefined)
  )
    throw new Error(
      "Site answer must carry an origin exactly when status is ok",
    );
  if (
    m.type === "fill.approved" &&
    (m.validUntil <= m.validFrom ||
      m.expiresAt > m.validUntil ||
      m.validFrom > now + 30_000)
  )
    throw new Error("Invalid OTP lifetime");
  return m;
}
const boxSchema = z
  .object({
    kind: z.literal("pair"),
    v: z.literal(1),
    nonce: z.string().max(40),
    ciphertext: z.string().max(MAX_PAYLOAD),
  })
  .strict();
export function encryptPair(qr: PairQr, p: PairResponse): string {
  pairResponseSchema.parse(p);
  const nonce = sodium.randombytes_buf(24);
  const c = sodium.crypto_aead_xchacha20poly1305_ietf_encrypt(
    JSON.stringify(p),
    `otpbridge:pair:1:${qr.channelId}`,
    null,
    nonce,
    decode(qr.bootstrap, 32),
  );
  return JSON.stringify({
    kind: "pair",
    v: 1,
    nonce: encode(nonce),
    ciphertext: encode(c),
  });
}
export const pairDenialSchema = z
  .object({
    type: z.literal("pair.denied"),
    v: z.literal(1),
    channelId: idSchema,
    browserPublicKey: idSchema,
    browserName: name,
    expiresAt: timestamp,
    reason: z.literal("denied"),
  })
  .strict();
export type PairDenial = z.infer<typeof pairDenialSchema>;
export function encryptPairDenial(qr: PairQr): string {
  const body: PairDenial = {
    type: "pair.denied",
    v: 1,
    channelId: qr.channelId,
    browserPublicKey: qr.browserPublicKey,
    browserName: qr.browserName,
    expiresAt: qr.expiresAt,
    reason: "denied",
  };
  const nonce = sodium.randombytes_buf(24);
  const c = sodium.crypto_aead_xchacha20poly1305_ietf_encrypt(
    JSON.stringify(body),
    `otpbridge:pair:1:${qr.channelId}`,
    null,
    nonce,
    decode(qr.bootstrap, 32),
  );
  return JSON.stringify({
    kind: "pair",
    v: 1,
    nonce: encode(nonce),
    ciphertext: encode(c),
  });
}
export function decryptPairOutcome(
  qr: PairQr,
  payload: string,
  now = Date.now(),
): PairResponse | PairDenial {
  if (payload.length > MAX_PAYLOAD) throw new Error("Oversized payload");
  const box = boxSchema.parse(JSON.parse(payload));
  const plain = sodium.crypto_aead_xchacha20poly1305_ietf_decrypt(
    null,
    decode(box.ciphertext),
    `otpbridge:pair:1:${qr.channelId}`,
    decode(box.nonce, 24),
    decode(qr.bootstrap, 32),
    "text",
  );
  const body = JSON.parse(plain);
  if (body.type !== "pair.denied") return decryptPair(qr, payload, now);
  const p = pairDenialSchema.parse(body);
  if (
    p.channelId !== qr.channelId ||
    p.browserPublicKey !== qr.browserPublicKey ||
    p.browserName !== qr.browserName ||
    p.expiresAt !== qr.expiresAt ||
    p.expiresAt <= now ||
    p.expiresAt > now + PAIR_TTL
  )
    throw new Error("Pairing denial mismatch/expired");
  return p;
}
export function decryptPair(
  qr: PairQr,
  payload: string,
  now = Date.now(),
): PairResponse {
  if (payload.length > MAX_PAYLOAD) throw new Error("Oversized payload");
  const box = boxSchema.parse(JSON.parse(payload));
  const plain = sodium.crypto_aead_xchacha20poly1305_ietf_decrypt(
    null,
    decode(box.ciphertext),
    `otpbridge:pair:1:${qr.channelId}`,
    decode(box.nonce, 24),
    decode(qr.bootstrap, 32),
    "text",
  );
  const p = pairResponseSchema.parse(JSON.parse(plain));
  if (
    p.channelId !== qr.channelId ||
    p.browserPublicKey !== qr.browserPublicKey ||
    p.browserName !== qr.browserName ||
    p.expiresAt !== qr.expiresAt ||
    p.expiresAt <= now ||
    p.expiresAt > now + PAIR_TTL
  )
    throw new Error("Pairing transcript mismatch/expired");
  return p;
}
export const envelopeSchema = z
  .object({
    kind: z.literal("session"),
    v: z.literal(1),
    channelId: idSchema,
    id: idSchema,
    expiresAt: timestamp,
    nonce: z.string().max(40),
    ciphertext: z.string().max(MAX_PAYLOAD),
  })
  .strict();
export interface TransportMessage {
  sequence: number;
  id: string;
  expiresAt: number;
  payload: string;
}
const aad = (channelId: string, id: string, expiresAt: number) =>
  `otpbridge:session:1:${channelId}:${id}:${expiresAt}`;
export function encryptMessage(
  channelId: string,
  tx: string,
  message: Message,
): { id: string; expiresAt: number; payload: string } {
  const m = validateMessage(message);
  idSchema.parse(channelId);
  const id = randomId(),
    nonce = sodium.randombytes_buf(24);
  const ciphertext = sodium.crypto_aead_xchacha20poly1305_ietf_encrypt(
    JSON.stringify(m),
    aad(channelId, id, m.expiresAt),
    null,
    nonce,
    decode(tx, 32),
  );
  return {
    id,
    expiresAt: m.expiresAt,
    payload: JSON.stringify({
      kind: "session",
      v: 1,
      channelId,
      id,
      expiresAt: m.expiresAt,
      nonce: encode(nonce),
      ciphertext: encode(ciphertext),
    }),
  };
}
export function decryptMessage(
  channelId: string,
  rx: string,
  transport: Pick<TransportMessage, "id" | "expiresAt" | "payload">,
  now = Date.now(),
): Message {
  if (transport.payload.length > MAX_PAYLOAD)
    throw new Error("Oversized payload");
  const e = envelopeSchema.parse(JSON.parse(transport.payload));
  if (
    e.channelId !== channelId ||
    e.id !== transport.id ||
    e.expiresAt !== transport.expiresAt ||
    e.expiresAt <= now
  )
    throw new Error("Envelope mismatch/expired");
  const plaintext = sodium.crypto_aead_xchacha20poly1305_ietf_decrypt(
    null,
    decode(e.ciphertext),
    aad(channelId, e.id, e.expiresAt),
    decode(e.nonce, 24),
    decode(rx, 32),
    "text",
  );
  const m = validateMessage(JSON.parse(plaintext), now);
  if (m.expiresAt !== e.expiresAt) throw new Error("Expiry mismatch");
  return m;
}
export function assertApproval(
  request: FillRequest,
  response: FillApproved,
  now = Date.now(),
): void {
  validateMessage(response, now);
  if (
    response.requestId !== request.requestId ||
    response.origin !== request.origin ||
    response.frameOrigin !== request.frameOrigin ||
    response.expiresAt > request.expiresAt ||
    request.expiresAt <= now ||
    response.validUntil - now < 2000 ||
    response.validFrom > now + 1000
  )
    throw new Error("Approval does not match live request");
  // request.accountRef is only a preselection hint: the phone user may approve a
  // different account for this site, which then becomes the remembered choice.
}
async function boundedResponseJson(response: Response): Promise<unknown> {
  const reader = response.body?.getReader();
  if (!reader) throw new Error("Empty relay response");
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      size += value.length;
      // 64 envelopes of up to 16KiB plus JSON escaping and metadata.
      if (size > 2_200_000) {
        await reader.cancel();
        throw new Error("Relay response too large");
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.length;
  }
  return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
}
export class RelayClient {
  readonly relay: string;
  constructor(
    relay: string,
    readonly channelId: string,
    readonly token: string,
  ) {
    this.relay = normalizeRelay(relay);
    idSchema.parse(channelId);
    idSchema.parse(token);
  }
  private async call(path: string, init: RequestInit = {}): Promise<any> {
    const r = await fetch(
      `${this.relay}/v1/channels/${this.channelId}${path}`,
      {
        ...init,
        headers: {
          Authorization: `Bearer ${this.token}`,
          "Content-Type": "application/json",
          ...init.headers,
        },
        signal: AbortSignal.timeout(12_000),
        cache: "no-store",
        redirect: "error",
      },
    );
    if (!r.ok) throw new Error(`Relay error ${r.status}`);
    return r.status === 204 ? undefined : boundedResponseJson(r);
  }
  static async create(relay: string): Promise<{
    channelId: string;
    browserToken: string;
    phoneToken: string;
    expiresAt: number;
  }> {
    const r = await fetch(`${normalizeRelay(relay)}/v1/channels`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: "{}",
      signal: AbortSignal.timeout(12_000),
      redirect: "error",
    });
    if (!r.ok) throw new Error(`Relay error ${r.status}`);
    return z
      .object({
        channelId: idSchema,
        browserToken: idSchema,
        phoneToken: idSchema,
        expiresAt: timestamp,
      })
      .strict()
      .parse(await boundedResponseJson(r));
  }
  send(message: { id: string; expiresAt: number; payload: string }) {
    return this.call("/messages", {
      method: "POST",
      body: JSON.stringify(message),
    });
  }
  async poll(
    after: number,
  ): Promise<{ messages: TransportMessage[]; cursor: number }> {
    const result = z
      .object({
        messages: z
          .array(
            z
              .object({
                sequence: z.number().int().positive().safe(),
                id: idSchema,
                expiresAt: timestamp,
                payload: z.string().max(MAX_PAYLOAD),
              })
              .strict(),
          )
          .max(64),
        cursor: z.number().int().nonnegative().safe(),
      })
      .strict()
      .parse(await this.call(`/messages?after=${after}`));
    if (!Number.isSafeInteger(after) || after < 0 || result.cursor < after)
      throw new Error("Invalid cursor");
    let previous = after;
    const ids = new Set<string>();
    for (const m of result.messages) {
      if (m.sequence <= previous || m.sequence > result.cursor || ids.has(m.id))
        throw new Error("Invalid message ordering");
      previous = m.sequence;
      ids.add(m.id);
    }
    return result;
  }
  activate() {
    return this.call("/activate", { method: "POST" });
  }
  /** Relay-side metadata traces for this channel (no payloads). */
  diagnostics(): Promise<unknown> {
    return this.call("/diagnostics");
  }
  revoke() {
    return this.call("", { method: "DELETE" });
  }
}
