import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { Mailbox } from "../src/mailbox";
import worker from "../src/index";
import { hash, randomId } from "../src/limits";

// Throwaway RSA key generated for tests only; never a real credential.
const TEST_KEY =
  "-----BEGIN PRIVATE KEY-----\nMIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQCMFFp6YV4CAZrv\nhNmZlZLCssD3JPsRg/To8u/DtbF0ySNVtEB06YIpbXtxPrRahwb8p4qasqZYyDff\nG7yiVwUg7P5J0jMlE6CbYIRc01TUWFSyaqw1Wd4pukWM3bYb37BavSpf07iRMT6Y\n7YAbRvOFoxF4w3JMaSs2bJbfx7Sn7k72HbQT4kQPWIZy+Sh24yYx3/bOULfpOnwE\ncQTNFq2gA3Nlavu1cn1B3Sj21VbdwjUSVrREfN+H+0YJptxCMzrYDqvpZjjRelo0\nNvmu6YFAm1W0byoyw3XXSqUqRoHrtDn8VeXhVFEnZwT+Fk2p1vX37tQ1WiISd0V5\nXkFMQsFhAgMBAAECggEAPj3M9hPNcZbFAShGh7euPlSu6SHFbUL0z40ifBN/hiTZ\nTnuJKGmZLE85SlhSSuc0c47+Rz6aK1jLepcYegmwfbDQTHzZvN+7vU5lgdllF/Un\nrR845XvI+0GJJc5d3ITIF4l2Cp5IpDr9VXYyNeyQyBBs8vvlDY6tZMFbSJOz8emJ\nW146NjDzZCHxJCR+HdxkcFccW+tFcJw5CyIYIE+i4QyzRhPe5eZl2HiYEneoa6jH\npIHhgz2Vdten4Q09ZoLTxRtZ5wMyD0oEMfJiZhf0xJ+Hq83VEk4v4eGbTfe/rCo2\nyPkYwP0mk84xPY/exBl6y47hDPwB44/hTBd6HV5HAQKBgQDA+W+XKhAxamUwV+7Z\nGHorhIdmL0HPkH5ZW0cO7Rk/lxPv/2mauWuXctxPTy5A2W51PP4SZyAvyEmCPNpC\nIVFPsKW5cXkGOUquWTOk5IYsHPiE1dBProBnAkkMtZFFJye48vtRr8ed4XpEyGmC\nhygSlmnw/jf//D3EMTotAH60pwKBgQC51GKHooyAvGTNVM0+eA7v4AW+3yG+d2JP\nU2eKY9w0x9BjucaKHJ0Ew3ukG2rtwljsukGC3+bgzM0QYf5ouVrvldXTgKxGH9D6\nGPxmlGypSJd0f1iEJo3eldZMFORhBgJZw7p+TX0pRyYYcL38eNW4S32+ZiOwuBuk\ne2eKjEEytwKBgDEpWrxwAuAT8uN2A2tbSQR9JuFpeKKgdQ/eqSOsU5pXqsShlQ3N\n3xMeN/IcgfQTcIh9mVym2RRTOibryc6/Hi/nGNE9j09+mQAByZ5gea4TU1zD++XQ\n1vVnJ6UNPI0ZhI0hmdUdqwUVxEhxpi+SryrNFwBGQ437fODWibOdRKpRAoGAPlL4\n6Lyfmt2UgTkbzQO/HrxnsOhtDNBFbI1wgH2VcS5Cue+ootGN5/cYFKYNashvwpWF\nOzuU13hYnlH/XWgtE4WRPWo+XRZ2pCqVHGJTP0NVaID9UvULR8fBI6suHUxCpZB7\nj7i1bY6rPC8zyOVN6I2JEr524eIdg0DQLSt1AmkCgYBm1PEUr2ZRjAcWmw4rwGh3\noNl1Q/9DFMdTM5bYYbKWFvLzrbEfKll7nVvXDvUtmhIzBUiknGyx7uYzT4snuftm\nc76zwcxYt/2AbP/SCq9jBHtS8qbOX3s0kNNgLj5jrPshbVb9unnxGwKvM/1MNKJc\nczskm+qgkh+q2fHb2Ebxrw==\n-----END PRIVATE KEY-----\n";
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
