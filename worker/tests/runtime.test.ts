import { afterAll, beforeAll, expect, it } from "vitest";
import { Miniflare } from "miniflare";
import { execFileSync } from "node:child_process";
import { resolve } from "node:path";
let mf: Miniflare;
beforeAll(async () => {
  execFileSync(
    "pnpm",
    ["exec", "wrangler", "deploy", "--dry-run", "--outdir", "dist"],
    {
      cwd: resolve("."),
      env: { ...process.env, WRANGLER_SEND_METRICS: "false" },
      stdio: "pipe",
    },
  );
  mf = new Miniflare({
    modules: true,
    scriptPath: resolve("dist/index.js"),
    compatibilityDate: "2026-07-30",
    durableObjects: { MAILBOX: { className: "Mailbox", useSQLite: true } },
  });
  await mf.ready;
}, 60000);
afterAll(async () => {
  await mf?.dispose();
});
it("runs real workerd create, role-isolated concurrent sends, activation and revocation", async () => {
  const create = await mf.dispatchFetch("https://relay/v1/channels", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: "{}",
  });
  expect(create.status).toBe(201);
  const c = (await create.json()) as any;
  expect(c.expiresAt).toBeGreaterThan(Date.now());
  const base = `https://relay/v1/channels/${c.channelId}`;
  const headers = {
    Authorization: `Bearer ${c.browserToken}`,
    "Content-Type": "application/json",
  };
  const ids = Array.from({ length: 8 }, (_, i) => {
    const x = new Uint8Array(32);
    x.fill(i + 1);
    return Buffer.from(x).toString("base64url");
  });
  const results = await Promise.all(
    ids.map((id) =>
      mf.dispatchFetch(`${base}/messages`, {
        method: "POST",
        headers,
        body: JSON.stringify({
          id,
          expiresAt: Date.now() + 60000,
          payload: "opaque-test-ciphertext",
        }),
      }),
    ),
  );
  expect(results.map((r) => r.status)).toEqual(Array(8).fill(201));
  const browser = (await (
    await mf.dispatchFetch(`${base}/messages`, { headers })
  ).json()) as any;
  expect(browser.messages).toEqual([]);
  const phone = (await (
    await mf.dispatchFetch(`${base}/messages`, {
      headers: { Authorization: `Bearer ${c.phoneToken}` },
    })
  ).json()) as any;
  expect(phone.messages).toHaveLength(8);
  expect(phone.messages.map((m: any) => m.sequence)).toEqual([
    1, 2, 3, 4, 5, 6, 7, 8,
  ]);
  expect(phone.cursor).toBe(8);
  expect(
    (await mf.dispatchFetch(`${base}/activate`, { method: "POST", headers }))
      .status,
  ).toBe(200);
  expect(
    (await mf.dispatchFetch(`${base}/activate`, { method: "POST", headers }))
      .status,
  ).toBe(200);
  expect(
    (await mf.dispatchFetch(base, { method: "DELETE", headers })).status,
  ).toBe(204);
  expect((await mf.dispatchFetch(`${base}/messages`, { headers })).status).toBe(
    404,
  );
}, 30000);
