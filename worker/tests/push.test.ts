import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { Mailbox } from "../src/mailbox";
import worker from "../src/index";
import { hash, randomId } from "../src/limits";

// Fresh RSA key per test run; no key material is stored in the repository.
const { privateKey } = await crypto.subtle.generateKey(
  {
    name: "RSASSA-PKCS1-v1_5",
    modulusLength: 2048,
    publicExponent: new Uint8Array([1, 0, 1]),
    hash: "SHA-256",
  },
  true,
  ["sign", "verify"],
);
const pkcs8 = new Uint8Array(
  await crypto.subtle.exportKey("pkcs8", privateKey as CryptoKey),
);
const TEST_KEY = `-----BEGIN PRIVATE KEY-----\n${btoa(String.fromCharCode(...pkcs8)).replace(/(.{64})/g, "$1\n")}\n-----END PRIVATE KEY-----\n`;
const SA = JSON.stringify({
  client_email: "push@test.iam.gserviceaccount.com",
  private_key: TEST_KEY,
  project_id: "test-project",
  token_uri: "https://oauth2.googleapis.com/token",
});
const FCM_TOKEN = "f".repeat(20) + ":" + "A".repeat(140);

class Mem {
  data = new Map<string, any>();
  async get(k: string) {
    return this.data.get(k);
  }
  async put(k: any, v?: any) {
    if (typeof k === "string") this.data.set(k, structuredClone(v));
    else
      for (const [a, b] of Object.entries(k))
        this.data.set(a, structuredClone(b));
  }
  async delete(k: string) {
    return this.data.delete(k);
  }
  async deleteAll() {
    this.data.clear();
  }
  async list({ prefix = "" } = {}) {
    return new Map(
      [...this.data.entries()].filter(([k]) => k.startsWith(prefix)).sort(),
    );
  }
  async setAlarm() {}
  async deleteAlarm() {}
}
let b: string,
  p: string,
  m: Mailbox,
  s: Mem,
  fetchMock: ReturnType<typeof vi.fn>;
const call = (path: string, token: string, method = "GET", body?: unknown) =>
  m.fetch(
    new Request(`https://internal${path}`, {
      method,
      headers: {
        Authorization: `Bearer ${token}`,
        "Content-Type": "application/json",
      },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    }),
  );
const msg = () => ({
  id: randomId(),
  expiresAt: Date.now() + 60_000,
  payload: "opaque ciphertext",
});

async function setup(env: Record<string, string>) {
  b = randomId();
  p = randomId();
  s = new Mem();
  m = new Mailbox(
    {
      storage: s,
      id: { toString: () => "abcdef0123456789abcdef" },
      blockConcurrencyWhile: (fn: any) => fn(),
    } as any,
    env,
  );
  await call("/init", b, "POST", {
    browserHash: await hash(b),
    phoneHash: await hash(p),
    expiresAt: Date.now() + 300_000,
  });
  await call("/activate", b, "POST");
}
beforeEach(() => {
  fetchMock = vi.fn(async (url: string, init: RequestInit) => {
    if (url.includes("oauth2"))
      return new Response(
        JSON.stringify({ access_token: "at", expires_in: 3600 }),
      );
    return new Response("{}", { status: 200 });
  });
  vi.stubGlobal("fetch", fetchMock);
});
afterEach(() => vi.unstubAllGlobals());

describe("push wake-ups", () => {
  it("only the phone can register; token format is validated", async () => {
    await setup({ FCM_SERVICE_ACCOUNT: SA });
    expect((await call("/push", b, "PUT", { token: FCM_TOKEN })).status).toBe(
      403,
    );
    expect((await call("/push", p, "PUT", { token: "short" })).status).toBe(
      400,
    );
    expect(
      (await call("/push", p, "PUT", { token: FCM_TOKEN, extra: 1 })).status,
    ).toBe(400);
    const ok = await call("/push", p, "PUT", { token: FCM_TOKEN });
    expect(ok.status).toBe(200);
    expect(await ok.json()).toEqual({ ok: true, enabled: true });
  });

  it("a browser request sends one content-free high-priority push", async () => {
    await setup({ FCM_SERVICE_ACCOUNT: SA });
    await call("/push", p, "PUT", { token: FCM_TOKEN });
    expect((await call("/messages", b, "POST", msg())).status).toBe(201);
    const send = fetchMock.mock.calls.find(([u]) =>
      String(u).includes("fcm.googleapis.com"),
    )!;
    expect(String(send[0])).toBe(
      "https://fcm.googleapis.com/v1/projects/test-project/messages:send",
    );
    const body = JSON.parse(send[1].body);
    expect(body.message.token).toBe(FCM_TOKEN);
    expect(body.message.android.priority).toBe("HIGH");
    expect(body.message.notification).toBeUndefined();
    expect(Object.keys(body.message.data).sort()).toEqual(["c", "t"]);
    expect(JSON.stringify(body)).not.toContain("opaque ciphertext");
  });

  it("phone messages never trigger push and bursts are throttled", async () => {
    await setup({ FCM_SERVICE_ACCOUNT: SA });
    await call("/push", p, "PUT", { token: FCM_TOKEN });
    await call("/messages", p, "POST", msg());
    expect(
      fetchMock.mock.calls.filter(([u]) => String(u).includes("fcm")).length,
    ).toBe(0);
    await call("/messages", b, "POST", msg());
    await call("/messages", b, "POST", msg());
    expect(
      fetchMock.mock.calls.filter(([u]) => String(u).includes("fcm")).length,
    ).toBe(1);
  });

  it("an unregistered token is dropped; FCM errors never block delivery", async () => {
    await setup({ FCM_SERVICE_ACCOUNT: SA });
    await call("/push", p, "PUT", { token: FCM_TOKEN });
    fetchMock.mockImplementation(async (url: string) =>
      url.includes("oauth2")
        ? new Response(JSON.stringify({ access_token: "at", expires_in: 3600 }))
        : new Response(
            JSON.stringify({
              error: {
                status: "NOT_FOUND",
                details: [{ errorCode: "UNREGISTERED" }],
              },
            }),
            { status: 404 },
          ),
    );
    expect((await call("/messages", b, "POST", msg())).status).toBe(201);
    expect(s.data.has("push")).toBe(false);
    expect(
      ((await (await call("/messages", p)).json()) as any).messages.length,
    ).toBe(1);
  });

  it("without the secret, push is disabled and nothing is sent", async () => {
    await setup({});
    const r = await call("/push", p, "PUT", { token: FCM_TOKEN });
    expect(await r.json()).toEqual({ ok: true, enabled: false });
    expect((await call("/messages", b, "POST", msg())).status).toBe(201);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("health reports push availability; worker routes /push", async () => {
    const h = await worker.fetch(new Request("https://relay/health"), {
      FCM_SERVICE_ACCOUNT: SA,
    } as any);
    expect(await h.json()).toEqual({
      ok: true,
      protocolVersions: [1],
      push: true,
    });
    const r = await worker.fetch(
      new Request(`https://relay/v1/channels/${randomId()}/push`, {
        method: "PUT",
      }),
      {} as any,
    );
    expect(r.status).toBe(401);
  });
});
