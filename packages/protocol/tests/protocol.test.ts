import { beforeAll, describe, expect, it } from "vitest";
import {
  ready,
  keyPair,
  sessionKeys,
  randomId,
  encryptMessage,
  decryptMessage,
  encryptPair,
  decryptPair,
  pairingSas,
  validateMessage,
  assertApproval,
  normalizeRelay,
  decode,
  encode,
  idSchema,
  pairResponseSchema,
  type PairQr,
  type PairResponse,
  type FillRequest,
} from "../src/index";
beforeAll(() => ready);
function setup() {
  const b = keyPair(),
    p = keyPair();
  return {
    b,
    p,
    bk: sessionKeys("browser", b, p.publicKey),
    pk: sessionKeys("phone", p, b.publicKey),
    channel: randomId(),
  };
}
function req(): FillRequest {
  const now = Date.now();
  return {
    type: "fill.request",
    requestId: randomId(),
    createdAt: now,
    expiresAt: now + 90000,
    browserName: "Laptop",
    origin: "https://example.com",
    frameOrigin: "https://example.com",
  };
}
describe("authenticated encrypted protocol", () => {
  it("rejects noncanonical ID padding bits and invalid Unicode names", () => {
    expect(() => idSchema.parse("A".repeat(42) + "B")).toThrow();
    const s = setup();
    expect(() =>
      pairResponseSchema.parse({
        type: "pair.response",
        v: 1,
        channelId: s.channel,
        browserPublicKey: s.b.publicKey,
        phonePublicKey: s.p.publicKey,
        browserName: String.fromCharCode(0xd800),
        phoneName: "Phone",
        expiresAt: Date.now() + 1000,
      }),
    ).toThrow();
  });
  it("derives matching directional keys and roundtrips", () => {
    const s = setup();
    expect(s.bk.tx).toBe(s.pk.rx);
    expect(s.bk.rx).toBe(s.pk.tx);
    expect(s.bk.tx).not.toBe(s.bk.rx);
    const r = req();
    const e = encryptMessage(s.channel, s.bk.tx, r);
    expect(decryptMessage(s.channel, s.pk.rx, e)).toEqual(r);
    expect(e.payload).not.toContain("example.com");
  });
  it("rejects wrong key/channel/outer expiry and tampered ciphertext", () => {
    const s = setup(),
      e = encryptMessage(s.channel, s.bk.tx, req());
    expect(() => decryptMessage(s.channel, randomId(), e)).toThrow();
    expect(() => decryptMessage(randomId(), s.pk.rx, e)).toThrow();
    expect(() =>
      decryptMessage(s.channel, s.pk.rx, { ...e, expiresAt: e.expiresAt + 1 }),
    ).toThrow();
    const box = JSON.parse(e.payload);
    const bytes = decode(box.ciphertext);
    bytes[0] = bytes[0]! ^ 1;
    box.ciphertext = encode(bytes);
    expect(() =>
      decryptMessage(s.channel, s.pk.rx, {
        ...e,
        payload: JSON.stringify(box),
      }),
    ).toThrow();
  });
  it("rejects expired and future/overlong application messages", () => {
    const r = req();
    expect(() => validateMessage(r, r.expiresAt)).toThrow();
    expect(() =>
      validateMessage({
        ...r,
        createdAt: r.createdAt + 60000,
        expiresAt: r.expiresAt + 60000,
      }),
    ).toThrow();
    expect(() =>
      validateMessage({ ...r, expiresAt: r.expiresAt + 1 }),
    ).toThrow();
    expect(() =>
      validateMessage({ ...r, frameOrigin: "https://evil.com" }),
    ).toThrow();
  });
  it("authenticates pairing and SAS transcript", () => {
    const s = setup();
    const q: PairQr = {
      type: "otpbridge.pair",
      v: 1,
      relay: "https://relay.example",
      channelId: s.channel,
      phoneToken: randomId(),
      bootstrap: randomId(),
      browserPublicKey: s.b.publicKey,
      browserName: "Laptop",
      expiresAt: Date.now() + 300000,
    };
    const p: PairResponse = {
      type: "pair.response",
      v: 1,
      channelId: q.channelId,
      browserPublicKey: q.browserPublicKey,
      phonePublicKey: s.p.publicKey,
      browserName: q.browserName,
      phoneName: "Phone",
      expiresAt: q.expiresAt,
    };
    const c = encryptPair(q, p);
    expect(decryptPair(q, c)).toEqual(p);
    expect(pairingSas(p)).toMatch(/^\d{8}$/);
    expect(() => decryptPair({ ...q, bootstrap: randomId() }, c)).toThrow();
    expect(() => decryptPair({ ...q, browserName: "Other" }, c)).toThrow();
    expect(() => decryptPair(q, c, q.expiresAt)).toThrow();
    expect(pairingSas({ ...p, phoneName: "Other" })).not.toBe(pairingSas(p));
  });
  it("binds approval to live request, account and OTP interval", () => {
    const r = req(),
      now = Date.now();
    const a = {
      type: "fill.approved" as const,
      requestId: r.requestId,
      createdAt: now,
      expiresAt: now + 20000,
      origin: r.origin,
      frameOrigin: r.frameOrigin,
      accountRef: randomId(),
      otp: "012345",
      validFrom: now - 10000,
      validUntil: now + 20000,
    };
    expect(() => assertApproval(r, a, now)).not.toThrow();
    expect(() =>
      assertApproval(r, { ...a, requestId: randomId() }, now),
    ).toThrow();
    expect(() =>
      assertApproval(r, { ...a, origin: "https://evil.com" }, now),
    ).toThrow();
    expect(() => assertApproval(r, a, a.validUntil - 1000)).toThrow();
    expect(() =>
      assertApproval({ ...r, accountRef: randomId() }, a, now),
    ).toThrow();
  });
  it("requires safe relay origin and canonical encodings", () => {
    expect(normalizeRelay("https://relay.example/")).toBe(
      "https://relay.example",
    );
    for (const u of [
      "http://relay.example",
      "https://u:p@relay.example",
      "https://relay.example/path",
      "https://relay.example/?secret=x",
      "ftp://relay.example",
    ])
      expect(() => normalizeRelay(u)).toThrow();
    expect(() => decode("AA==")).toThrow();
    expect(() => decode("AA", 32)).toThrow();
  });
});
