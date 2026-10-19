import { test, expect, chromium } from "@playwright/test";
import { resolve } from "node:path";
import { mkdtemp, rm, cp, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { createServer } from "node:http";
import {
  ready,
  pairQrSchema,
  keyPair,
  sessionKeys,
  randomId,
  encryptPair,
  pairingSas,
  encryptMessage,
  decryptMessage,
  type PairResponse,
} from "@otpbridge/protocol";
test("real browser pairs automatically after authenticated phone SAS confirmation", async () => {
  await ready;
  let sequence = 0;
  const messages: Array<{
    sequence: number;
    id: string;
    expiresAt: number;
    payload: string;
    role: string;
  }> = [];
  const channelId = randomId(),
    browserToken = randomId(),
    phoneToken = randomId();
  let active = false,
    activationResponsesLost = 0,
    readyResponsesLost = 0;
  const readyAttempts: string[] = [];
  const server = createServer(async (req, res) => {
    res.setHeader("Content-Type", "application/json");
    res.setHeader("Cache-Control", "no-store");
    const url = new URL(req.url ?? "/", "http://localhost");
    const role =
      req.headers.authorization === `Bearer ${browserToken}`
        ? "browser"
        : req.headers.authorization === `Bearer ${phoneToken}`
          ? "phone"
          : null;
    if (req.method === "POST" && url.pathname === "/v1/channels") {
      res.statusCode = 201;
      res.end(
        JSON.stringify({
          channelId,
          browserToken,
          phoneToken,
          expiresAt: Date.now() + 300000,
        }),
      );
      return;
    }
    if (url.pathname === "/health") {
      res.end(JSON.stringify({ ok: true, protocolVersions: [1] }));
      return;
    }
    if (!role) {
      res.statusCode = 401;
      res.end("{}");
      return;
    }
    if (url.pathname.endsWith("/activate")) {
      active = true;
      if (activationResponsesLost++ === 0) {
        res.destroy();
        return;
      }
      res.end("{}");
      return;
    }
    if (url.pathname.endsWith("/messages") && req.method === "POST") {
      let raw = "";
      for await (const c of req) raw += c;
      const m = JSON.parse(raw);
      if (role === "browser") readyAttempts.push(m.id);
      if (messages.some((x) => x.id === m.id)) {
        res.statusCode = 409;
        res.end("{}");
        return;
      }
      messages.push({ ...m, role, sequence: ++sequence });
      if (role === "browser" && readyResponsesLost++ === 0) {
        res.destroy();
        return;
      }
      res.statusCode = 201;
      res.end("{}");
      return;
    }
    if (url.pathname.endsWith("/messages")) {
      const after = Number(url.searchParams.get("after"));
      res.end(
        JSON.stringify({
          messages: messages
            .filter((m) => m.role !== role && m.sequence > after)
            .map(({ role: _, ...m }) => m),
          cursor: sequence,
        }),
      );
      return;
    }
    res.statusCode = 204;
    res.end();
  });
  await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
  const address = server.address() as { port: number };
  const relay = `http://127.0.0.1:${address.port}`;
  const profile = await mkdtemp(resolve(tmpdir(), "otpbridge-pair-")),
    extension = resolve(profile, "extension");
  await cp(resolve("extension/dist"), extension, { recursive: true });
  const manifest = JSON.parse(
    await readFile(resolve(extension, "manifest.json"), "utf8"),
  );
  manifest.host_permissions = [`${relay}/*`, "https://otpbridge.test/*"];
  await writeFile(
    resolve(extension, "manifest.json"),
    JSON.stringify(manifest),
  );
  const backgroundPath = resolve(extension, "background.js");
  const originalBackground = await readFile(backgroundPath, "utf8");
  await writeFile(
    backgroundPath,
    `const originalAdd = chrome.commands.onCommand.addListener.bind(chrome.commands.onCommand); chrome.commands.onCommand.addListener = listener => { globalThis.__testCommandListener = listener; originalAdd(listener); };\n${originalBackground}`,
  );
  const context = await chromium.launchPersistentContext(profile, {
    channel: "chromium",
    headless: !process.env.OTPBRIDGE_HEADED,
    args: [
      `--disable-extensions-except=${extension}`,
      `--load-extension=${extension}`,
    ],
  });
  try {
    let [worker] = context.serviceWorkers();
    worker ??= await context.waitForEvent("serviceworker");
    const id = new URL(worker.url()).host;
    const page = await context.newPage();
    await page.goto(`chrome-extension://${id}/options.html`);
    await page.getByLabel("Relay URL").fill(relay);
    await page.getByRole("button", { name: "Create pairing QR" }).click();
    await expect(
      page.getByAltText("Secret one-time phone pairing QR"),
    ).toBeVisible({ timeout: 15000 });
    const raw = await worker.evaluate(async () => {
      const c = (globalThis as any).chrome;
      return (await c.storage.local.get("state")).state.pairing.qr;
    });
    const qr = pairQrSchema.parse(raw);
    const phone = keyPair(),
      keys = sessionKeys("phone", phone, qr.browserPublicKey);
    const response: PairResponse = {
      type: "pair.response",
      v: 1,
      channelId,
      browserPublicKey: qr.browserPublicKey,
      phonePublicKey: phone.publicKey,
      browserName: qr.browserName,
      phoneName: "Synthetic phone",
      expiresAt: qr.expiresAt,
    };
    messages.push({
      sequence: ++sequence,
      id: randomId(),
      expiresAt: qr.expiresAt,
      payload: encryptPair(qr, response),
      role: "phone",
    });
    await expect(
      page.getByText(
        `${pairingSas(response).slice(0, 4)} ${pairingSas(response).slice(4)}`,
      ),
    ).toBeVisible({ timeout: 10000 });
    await expect(
      page.getByRole("button", { name: "Digits match" }),
    ).toHaveCount(0);
    expect(active).toBe(false);
    const now = Date.now();
    messages.push({
      ...encryptMessage(channelId, keys.tx, {
        type: "pair.confirm",
        requestId: channelId,
        createdAt: now,
        expiresAt: now + 90000,
      }),
      sequence: ++sequence,
      role: "phone",
    });
    await expect(
      page.getByRole("heading", { name: "Connected phone" }),
    ).toBeVisible({ timeout: 30000 });
    expect(active).toBe(true);
    await expect(
      page.getByRole("button", { name: "Restore relay permission" }),
    ).toHaveCount(0);
    await expect
      .poll(() =>
        worker.evaluate(
          async () =>
            (
              await (globalThis as any).chrome.storage.session.get(
                "relayHealth",
              )
            ).relayHealth?.reachable,
        ),
      )
      .toBe(true);
    expect(readyAttempts.length).toBeGreaterThanOrEqual(2);
    expect(new Set(readyAttempts).size).toBe(1);
    const outgoing = messages.find(
      (m) => m.role === "browser" && JSON.parse(m.payload).kind === "session",
    )!;
    expect(decryptMessage(channelId, keys.rx, outgoing).type).toBe(
      "pair.ready",
    );
    expect(messages.every((m) => !m.payload.includes("Synthetic phone"))).toBe(
      true,
    );
    // Synthetic HTTPS destination. Disposable test bundle captures the exact
    // production command listener; no test hook exists in shipped extension code.
    await context.route("https://otpbridge.test/**", (route) =>
      route.fulfill({
        contentType: "text/html",
        body: '<!doctype html><title>OTP fixture</title><form><input aria-label="OTP" autocomplete="one-time-code"><button>Submit</button></form><script>window.submits=0;document.querySelector("form").addEventListener("submit",e=>{e.preventDefault();window.submits++})</script>',
      }),
    );
    const destination = await context.newPage();
    await destination.goto("https://otpbridge.test/login");
    await destination.getByLabel("OTP").focus();
    await worker.evaluate(async () => {
      const c = (globalThis as any).chrome;
      const tabs = await c.tabs.query({ url: "https://otpbridge.test/*" });
      (globalThis as any).__testCommandListener("fill-from-phone", tabs[0]);
    });
    await expect
      .poll(
        () =>
          messages
            .filter(
              (m) =>
                m.role === "browser" &&
                JSON.parse(m.payload).kind === "session",
            )
            .map((m) => {
              try {
                return decryptMessage(channelId, keys.rx, m);
              } catch {
                return null;
              }
            })
            .find((m) => m?.type === "fill.request"),
        { timeout: 10000 },
      )
      .toBeTruthy();
    const request = messages
      .filter((m) => m.role === "browser")
      .map((m) => {
        try {
          return decryptMessage(channelId, keys.rx, m);
        } catch {
          return null;
        }
      })
      .find((m) => m?.type === "fill.request")!;
    if (request.type !== "fill.request") throw new Error("Missing request");
    const approvedAt = Date.now();
    messages.push({
      ...encryptMessage(channelId, keys.tx, {
        type: "fill.approved",
        requestId: request.requestId,
        createdAt: approvedAt,
        expiresAt: approvedAt + 20000,
        origin: request.origin,
        frameOrigin: request.frameOrigin,
        accountRef: randomId(),
        otp: "012345",
        validFrom: approvedAt - 10000,
        validUntil: approvedAt + 20000,
      }),
      sequence: ++sequence,
      role: "phone",
    });
    await expect(destination.getByLabel("OTP")).toHaveValue("012345", {
      timeout: 10000,
    });
    expect(await destination.evaluate(() => (window as any).submits)).toBe(0);
    await expect
      .poll(() =>
        worker.evaluate(async () =>
          (globalThis as any).chrome.action.getBadgeText({}),
        ),
      )
      .toMatch(/^\d{1,3}$/);
    const badge = await worker.evaluate(async () =>
      Number(await (globalThis as any).chrome.action.getBadgeText({})),
    );
    expect(badge).toBeGreaterThan(0);
    expect(badge).toBeLessThanOrEqual(20);
    const popup = await context.newPage();
    await popup.goto(`chrome-extension://${id}/popup.html`);
    await expect(popup.getByText(/\d+s remaining/)).toBeVisible();
  } finally {
    await context.close();
    await new Promise<void>((r, j) => server.close((e) => (e ? j(e) : r())));
    const abs = resolve(profile);
    if (!abs.startsWith(resolve(tmpdir(), "otpbridge-pair-")))
      throw new Error("Unexpected test profile path");
    await rm(abs, { recursive: true, force: true });
  }
});
