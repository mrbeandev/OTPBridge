import {
  ready,
  randomId,
  keyPair,
  normalizeRelay,
  RelayClient,
  PAIR_TTL,
  REQUEST_TTL,
  decryptPair,
  pairingSas,
  sessionKeys,
  encryptMessage,
  decryptMessage,
  assertApproval,
  validOrigin,
  type Message,
  type FillRequest,
} from "@otpbridge/protocol";
import {
  load,
  save,
  restrictStorage,
  type State,
  type Target,
} from "../storage";
import { captureTarget, fillTarget } from "../content/target";
let chain: Promise<unknown> = Promise.resolve();
function exclusive<T>(fn: () => Promise<T>): Promise<T> {
  const task = chain.then(fn, fn);
  chain = task.catch(() => undefined);
  return task;
}
const client = (d: { relay: string; channelId: string; token: string }) =>
  new RelayClient(d.relay, d.channelId, d.token);
function clean(s: State) {
  const now = Date.now();
  if (s.device)
    for (const [id, until] of Object.entries(s.device.seen))
      if (until < now) delete s.device.seen[id];
}
async function initialize() {
  await ready;
  await restrictStorage();
  await chrome.contextMenus.removeAll();
  chrome.contextMenus.create({
    id: "otpbridge-fill",
    title: "Fill from phone",
    contexts: ["editable"],
  });
}
chrome.runtime.onInstalled.addListener(() => {
  void initialize();
});
chrome.runtime.onStartup.addListener(() => {
  void initialize();
});
async function startPair(relayValue: string, browserName: string) {
  const relay = normalizeRelay(relayValue);
  if (!browserName.trim() || browserName.length > 80)
    throw new Error("Choose a browser name (1–80 characters)");
  const allowed = await chrome.permissions.contains({
    origins: [`${relay}/*`],
  });
  if (!allowed) throw new Error("Allow access to your relay first");
  const s = await load();
  if (s.device) throw new Error("Unpair the current phone first");
  const c = await RelayClient.create(relay),
    own = keyPair();
  s.pairing = {
    qr: {
      type: "otpbridge.pair",
      v: 1,
      relay,
      channelId: c.channelId,
      phoneToken: c.phoneToken,
      bootstrap: randomId(),
      browserPublicKey: own.publicKey,
      browserName: browserName.trim(),
      expiresAt: Math.min(c.expiresAt, Date.now() + PAIR_TTL),
    },
    own,
    browserToken: c.browserToken,
    cursor: 0,
    phoneConfirmed: false,
    seen: {},
  };
  s.status = "Scan this QR with OTPBridge on your phone.";
  delete s.error;
  await save(s);
}
async function pollPair(s: State) {
  const p = s.pairing;
  if (!p) return;
  if (p.qr.expiresAt <= Date.now()) {
    delete s.pairing;
    s.status = "Pairing expired. Start again.";
    return;
  }
  const relay = new RelayClient(p.qr.relay, p.qr.channelId, p.browserToken);
  const batch = await relay.poll(p.cursor);
  for (const t of batch.messages) {
    if (
      p.seen[t.id] ||
      t.expiresAt <= Date.now() ||
      t.expiresAt > p.qr.expiresAt
    )
      continue;
    try {
      const kind = JSON.parse(t.payload).kind;
      if (kind === "pair" && !p.response) {
        const response = decryptPair(p.qr, t.payload);
        p.response = response;
        p.keys = sessionKeys("browser", p.own, response.phonePublicKey);
        s.status = "Compare the eight digits on both screens.";
      } else if (kind === "session" && p.keys) {
        const m = decryptMessage(p.qr.channelId, p.keys.rx, t);
        if (m.type === "pair.confirm" && m.requestId === p.qr.channelId)
          p.phoneConfirmed = true;
      }
      p.seen[t.id] = t.expiresAt + PAIR_TTL;
    } catch {
      /* Ignore unauthenticated envelopes; never advance security state. */
    }
  }
  p.cursor = batch.cursor;
  if (p.phoneConfirmed && p.keys && p.response) {
    const now = Date.now();
    if (!p.readyEnvelope) {
      const m: Message = {
        type: "pair.ready",
        requestId: p.qr.channelId,
        createdAt: now,
        expiresAt: Math.min(p.qr.expiresAt, now + REQUEST_TTL),
      };
      p.readyEnvelope = encryptMessage(p.qr.channelId, p.keys.tx, m);
      await save(s);
    }
    // Phone confirmation authenticates the QR/SAS ceremony; no second browser click.
    // Activate idempotently before sending readiness; the phone must never process
    // requests against an unactivated channel. Retry exactly the persisted envelope.
    await relay.activate();
    try {
      await relay.send(p.readyEnvelope);
    } catch (e) {
      if (!(e instanceof Error) || !e.message.includes("409")) throw e;
    }
    s.device = {
      relay: p.qr.relay,
      channelId: p.qr.channelId,
      token: p.browserToken,
      keys: p.keys,
      browserName: p.qr.browserName,
      phoneName: p.response.phoneName,
      cursor: p.cursor,
      accounts: {},
      seen: p.seen,
    };
    delete s.pairing;
    s.status = "Phone paired. Right-click an OTP input to begin.";
  }
}
async function capture(
  tabId: number,
  frameId: number,
  useContext: boolean,
): Promise<Target> {
  const tab = await chrome.tabs.get(tabId);
  if (!tab.url) throw new Error("Cannot access this page");
  const origin = new URL(tab.url).origin;
  if (!validOrigin(origin))
    throw new Error("Only HTTPS websites are supported");
  const results = await chrome.scripting.executeScript({
    target: { tabId, frameIds: [frameId] },
    func: captureTarget,
    args: [useContext],
  });
  const hit = results[0];
  if (!hit?.result || !hit.documentId || hit.result.origin !== origin)
    throw new Error(
      useContext
        ? "Right-click the same input once more (first use grants page access), or focus it and press Alt+Shift+O. Unsupported/cross-origin fields are rejected."
        : "Focus a supported OTP input first. Cross-origin frames are not supported.",
    );
  return {
    tabId,
    frameId,
    documentId: hit.documentId,
    fieldToken: hit.result.fieldToken,
    origin,
  };
}
async function requestFill(
  tabId: number,
  frameId: number,
  useContext: boolean,
) {
  const s = await load();
  if (!s.device) throw new Error("Pair your phone in OTPBridge settings first");
  if (s.pending && s.pending.request.expiresAt <= Date.now()) {
    delete s.pending;
    await save(s);
  }
  if (s.pending)
    throw new Error("Cancel the current request before starting another");
  const target = await capture(tabId, frameId, useContext),
    now = Date.now(),
    d = s.device;
  const request: FillRequest = {
    type: "fill.request",
    requestId: randomId(),
    createdAt: now,
    expiresAt: now + REQUEST_TTL,
    browserName: d.browserName,
    origin: target.origin,
    frameOrigin: target.origin,
    ...(d.accounts[target.origin]
      ? { accountRef: d.accounts[target.origin] }
      : {}),
  };
  s.pending = {
    request,
    target,
    outbound: encryptMessage(d.channelId, d.keys.tx, request),
    sent: false,
  };
  s.status = "Open OTPBridge on your phone to approve.";
  delete s.error;
  await save(s);
  schedule();
  await poll(s);
  await save(s);
}
async function poll(s: State) {
  clean(s);
  if (s.pairing) {
    await pollPair(s);
    return;
  }
  const p = s.pending,
    d = s.device;
  if (!p || !d) return;
  if (p.request.expiresAt <= Date.now()) {
    delete s.pending;
    s.status = "Request expired. Start a new request.";
    return;
  }
  const c = client(d);
  if (!p.sent) {
    try {
      await c.send(p.outbound);
    } catch (e) {
      if (!(e instanceof Error) || !e.message.includes("409")) throw e;
    }
    p.sent = true;
    await save(s);
  }
  const batch = await c.poll(d.cursor);
  for (const t of batch.messages) {
    if (d.seen[t.id]) continue;
    let m: Message;
    try {
      m = decryptMessage(d.channelId, d.keys.rx, t);
    } catch {
      continue;
    }
    d.seen[t.id] = t.expiresAt + PAIR_TTL;
    if (m.type === "device.revoked") {
      delete s.pending;
      delete s.device;
      s.status = "Phone revoked this browser. Pair again.";
      break;
    }
    if (m.requestId !== p.request.requestId) continue;
    if (m.type === "fill.denied") {
      delete s.pending;
      s.status = "Request denied on phone.";
      break;
    }
    if (m.type === "fill.approved") {
      try {
        assertApproval(p.request, m);
      } catch {
        s.error = "Rejected a mismatched or stale approval.";
        continue;
      }
      // Consume before scripting: a service-worker restart cannot insert the same response twice.
      delete s.pending;
      s.status = "Approval received; validating destination.";
      await save(s);
      const tab = await chrome.tabs.get(p.target.tabId);
      if (!tab.url || new URL(tab.url).origin !== p.target.origin)
        throw new Error("Page changed; approval discarded");
      const results = await chrome.scripting.executeScript({
        target: { tabId: p.target.tabId, documentIds: [p.target.documentId] },
        func: fillTarget,
        args: [
          p.target.fieldToken,
          p.target.origin,
          m.otp,
          p.request.expiresAt,
          m.validUntil,
        ],
      });
      if (!results[0]?.result)
        throw new Error(
          "Original input changed or lost focus; approval discarded",
        );
      d.accounts[p.request.origin] = m.accountRef;
      s.status = "Code filled. The form was not submitted.";
      break;
    }
  }
  d.cursor = batch.cursor;
}
let timer: ReturnType<typeof setTimeout> | undefined;
let failures = 0;
function schedule() {
  if (timer) return;
  timer = setTimeout(
    () => {
      timer = undefined;
      void exclusive(async () => {
        await ready;
        const s = await load();
        if (!s.pending && !s.pairing) return;
        try {
          delete s.error;
          await poll(s);
          failures = 0;
        } catch (e) {
          failures = Math.min(failures + 1, 4);
          s.error = e instanceof Error ? e.message : "Relay unavailable";
        } finally {
          await save(s);
          await chrome.action.setBadgeText({
            text: s.pending ? "…" : s.error ? "!" : "",
          });
        }
        if (s.pending || s.pairing) schedule();
      });
    },
    Math.min(15000, 2000 * 2 ** failures) + Math.random() * 300,
  );
}
async function status() {
  const s = await load();
  return {
    status: s.status,
    error: s.error,
    device: s.device
      ? {
          phoneName: s.device.phoneName,
          browserName: s.device.browserName,
          relay: s.device.relay,
        }
      : null,
    pending: s.pending
      ? {
          origin: s.pending.request.origin,
          expiresAt: s.pending.request.expiresAt,
        }
      : null,
    pairing: s.pairing
      ? {
          qr: JSON.stringify(s.pairing.qr),
          sas: s.pairing.response ? pairingSas(s.pairing.response) : null,
          phoneConfirmed: s.pairing.phoneConfirmed,
          expiresAt: s.pairing.qr.expiresAt,
        }
      : null,
  };
}
chrome.runtime.onMessage.addListener((msg: unknown, sender, respond) => {
  if (
    sender.id !== chrome.runtime.id ||
    ![
      chrome.runtime.getURL("options.html"),
      chrome.runtime.getURL("popup.html"),
    ].includes(sender.url ?? "")
  )
    return false;
  void exclusive(async () => {
    await ready;
    await restrictStorage();
    if (typeof msg !== "object" || !msg) return { error: "Invalid command" };
    const m = msg as Record<string, unknown>;
    try {
      if (
        m.type === "startPair" &&
        typeof m.relay === "string" &&
        typeof m.browserName === "string"
      ) {
        await startPair(m.relay, m.browserName);
        schedule();
      } else if (m.type === "cancel") {
        const s = await load(),
          pending = s.pending,
          device = s.device,
          pairing = s.pairing;
        delete s.pending;
        delete s.pairing;
        s.status = "Cancelled locally.";
        delete s.error;
        await save(s);
        await chrome.action.setBadgeText({ text: "" });
        // Local cancellation is authoritative and persisted before best-effort I/O.
        if (pending && device && pending.request.expiresAt > Date.now()) {
          const now = Date.now();
          void client(device)
            .send(
              encryptMessage(device.channelId, device.keys.tx, {
                type: "request.cancelled",
                requestId: pending.request.requestId,
                createdAt: now,
                expiresAt: Math.min(
                  now + REQUEST_TTL,
                  pending.request.expiresAt,
                ),
              }),
            )
            .catch(() => undefined);
        }
        if (pairing)
          void new RelayClient(
            pairing.qr.relay,
            pairing.qr.channelId,
            pairing.browserToken,
          )
            .revoke()
            .catch(() => undefined);
      } else if (m.type === "unpair") {
        const s = await load();
        await save({
          status: "Unpaired locally. Remove this browser on your phone too.",
        });
        await chrome.action.setBadgeText({ text: "" });
        if (s.device)
          void client(s.device)
            .revoke()
            .catch(() => undefined);
      } else if (m.type === "refresh") {
        schedule();
      } else if (m.type === "status") {
        schedule();
      } else return { error: "Unknown command" };
      return await status();
    } catch (e) {
      return {
        ...(await status()),
        error: e instanceof Error ? e.message : "Operation failed",
      };
    }
  }).then(respond);
  return true;
});
function launch(tabId: number, frameId: number, useContext: boolean) {
  void exclusive(async () => {
    await ready;
    try {
      await requestFill(tabId, frameId, useContext);
    } catch (e) {
      const s = await load();
      s.error = e instanceof Error ? e.message : "Request failed";
      await save(s);
    }
    await chrome.action.setBadgeText({
      text: (await load()).pending ? "…" : "!",
    });
  });
}
chrome.contextMenus.onClicked.addListener((info, tab) => {
  if (info.menuItemId === "otpbridge-fill" && tab?.id !== undefined)
    launch(tab.id, info.frameId ?? 0, true);
});
chrome.commands.onCommand.addListener((command, tab) => {
  if (command === "fill-from-phone" && tab?.id !== undefined)
    launch(tab.id, 0, false);
});
chrome.tabs.onRemoved.addListener((id) => {
  void exclusive(async () => {
    const s = await load();
    if (s.pending?.target.tabId === id) {
      delete s.pending;
      s.status = "Tab closed; request cancelled locally.";
      await save(s);
    }
  });
});
// Restart pending polling after MV3 suspension; never generate a replacement request.
void ready.then(() =>
  exclusive(async () => {
    await restrictStorage();
    const s = await load();
    if (s.pending || s.pairing) schedule();
  }),
);
