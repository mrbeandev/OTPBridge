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
import { expiryBadge, remainingSeconds } from "./countdown";
import { probeRelay, type RelayHealth } from "./relay-status";
import { bestEffortContext } from "./request-context";
import { track, short, readTelemetry, clearTelemetry } from "../telemetry";
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
      browserInfo: navigator.userAgent.slice(0, 240),
      deviceInfo: navigator.platform.slice(0, 240),
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
        s.status = "Review browser details and approve on your phone.";
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
  editable = false,
): Promise<Target> {
  const tab = await chrome.tabs.get(tabId);
  track("capture.start", { tabId, frameId, useContext, hasUrl: !!tab.url });
  if (!tab.url) throw new Error("Cannot access this page");
  const origin = new URL(tab.url).origin;
  if (!validOrigin(origin))
    throw new Error("Only HTTPS websites are supported");
  const results = await chrome.scripting.executeScript({
    target: { tabId, frameIds: [frameId] },
    func: captureTarget,
    args: [useContext, editable],
  });
  const hit = results[0];
  track("capture.result", {
    found: !!hit?.result,
    how: hit?.result?.how ?? null,
    hasDocument: !!hit?.documentId,
    originMatch: hit?.result?.origin === origin,
    reason: hit?.result ? null : "no-target",
  });
  if (!hit?.result || !hit.documentId || hit.result.origin !== origin)
    throw new Error(
      useContext
        ? "Couldn't identify that field. Click into the OTP input, then choose Fill from phone again (or press Alt+Shift+O)."
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
  editable = false,
) {
  const s = await load();
  track("request.begin", {
    paired: !!s.device,
    pending: !!s.pending,
    pendingExpired: s.pending
      ? s.pending.request.expiresAt <= Date.now()
      : null,
  });
  if (!s.device) throw new Error("Pair your phone in OTPBridge settings first");
  if (s.pending && s.pending.request.expiresAt <= Date.now()) {
    delete s.pending;
    await save(s);
  }
  if (s.pending)
    throw new Error("Cancel the current request before starting another");
  const target = await capture(tabId, frameId, useContext, editable);
  // Context is best-effort: permission/provider failure cannot gate OTP delivery.
  const contextStarted = Date.now();
  const context = await bestEffortContext();
  track("context.done", {
    ms: Date.now() - contextStarted,
    fields: Object.keys(context).join(","),
  });
  const now = Date.now(),
    d = s.device;
  const request: FillRequest = {
    type: "fill.request",
    requestId: randomId(),
    createdAt: now,
    expiresAt: now + REQUEST_TTL,
    browserName: d.browserName,
    origin: target.origin,
    frameOrigin: target.origin,
    ...(Object.keys(context).length ? { context } : {}),
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
  track("request.created", {
    req: short(request.requestId),
    origin: target.origin,
    hasAccountRef: !!request.accountRef,
  });
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
    const sendStarted = Date.now();
    try {
      await c.send(p.outbound);
      track("relay.send.ok", {
        req: short(p.request.requestId),
        msg: short(p.outbound.id),
        ms: Date.now() - sendStarted,
      });
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e);
      track("relay.send.error", {
        req: short(p.request.requestId),
        msg: short(p.outbound.id),
        ms: Date.now() - sendStarted,
        error: message,
      });
      if (!(e instanceof Error) || !e.message.includes("409")) throw e;
    }
    p.sent = true;
    await save(s);
  }
  const batch = await c.poll(d.cursor);
  if (batch.messages.length)
    track("relay.poll", {
      req: short(p.request.requestId),
      count: batch.messages.length,
      cursor: batch.cursor,
    });
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
    track("phone.reply", { req: short(m.requestId), type: m.type });
    if (m.type === "fill.denied") {
      delete s.pending;
      s.status = "Request denied on phone.";
      break;
    }
    if (m.type === "fill.approved") {
      try {
        assertApproval(p.request, m);
      } catch (e) {
        track("approval.rejected", {
          req: short(m.requestId),
          error: e instanceof Error ? e.message : String(e),
        });
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
      track("fill.result", {
        req: short(p.request.requestId),
        filled: !!results[0]?.result,
      });
      if (!results[0]?.result)
        throw new Error(
          "Original input changed or lost focus; approval discarded",
        );
      d.accounts[p.request.origin] = m.accountRef;
      s.status = "Code filled. The form was not submitted.";
      s.lastFill = { origin: m.origin, validUntil: m.validUntil };
      startBadgeCountdown();
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
          track("poll.error", {
            failures,
            error: e instanceof Error ? e.message : String(e),
          });
          s.error = e instanceof Error ? e.message : "Relay unavailable";
        } finally {
          await save(s);
          await updateBadge();
          if (s.lastFill && s.lastFill.validUntil > Date.now())
            startBadgeCountdown();
        }
        if (s.pending || s.pairing) schedule();
      });
    },
    Math.min(15000, 2000 * 2 ** failures) + Math.random() * 300,
  );
}
let badgeTimer: ReturnType<typeof setTimeout> | undefined;
async function updateBadge() {
  const state = await load();
  const seconds = state.lastFill
    ? remainingSeconds(state.lastFill.validUntil)
    : 0;
  await chrome.action.setBadgeBackgroundColor({ color: "#303030" });
  await chrome.action.setBadgeText({
    text:
      seconds > 0
        ? expiryBadge(state.lastFill!.validUntil)
        : state.pending
          ? "…"
          : state.error
            ? "!"
            : "",
  });
  await chrome.action.setTitle({
    title:
      seconds > 0 ? `OTPBridge · code expires in ${seconds}s` : "OTPBridge",
  });
  return seconds;
}
function startBadgeCountdown() {
  if (badgeTimer !== undefined) return;
  badgeTimer = setTimeout(() => {
    badgeTimer = undefined;
    void exclusive(async () => {
      if ((await updateBadge()) > 0) startBadgeCountdown();
    });
  }, 1000);
}
let relayHealth: RelayHealth = {
  relay: null,
  reachable: false,
  permission: false,
  checkedAt: 0,
};
let healthInFlight: Promise<void> | undefined;
async function refreshRelayHealth() {
  if (healthInFlight) return healthInFlight;
  healthInFlight = (async () => {
    const state = await load();
    const relay = state.device?.relay ?? state.pairing?.qr.relay ?? null;
    const permission = relay
      ? await chrome.permissions.contains({ origins: [`${relay}/*`] })
      : false;
    const next = relay
      ? await probeRelay(relay, permission)
      : {
          relay: null,
          reachable: false,
          permission: false,
          checkedAt: Date.now(),
        };
    if (state.device && permission) {
      try {
        await client(state.device).poll(state.device.cursor);
      } catch (error) {
        if (
          error instanceof Error &&
          /Relay error (401|404|410)\b/.test(error.message)
        ) {
          await exclusive(async () => {
            const current = await load();
            if (current.device?.channelId === state.device?.channelId) {
              delete current.device;
              delete current.pending;
              delete current.lastFill;
              delete current.error;
              current.status =
                "Phone pairing was removed or expired. Pair your phone again.";
              await save(current);
              await updateBadge();
            }
          });
          next.reachable = false;
        }
      }
    }
    relayHealth = next;
    const paths = Object.fromEntries(
      [16, 32, 48, 128].map((size) => [
        size,
        `icons/icon-${size}${next.reachable ? "-connected" : ""}.png`,
      ]),
    );
    await chrome.action.setIcon({ path: paths });
    await chrome.storage.session.set({ relayHealth: next });
  })().finally(() => {
    healthInFlight = undefined;
  });
  return healthInFlight;
}
chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === "relay-health") void refreshRelayHealth();
});
chrome.permissions.onRemoved.addListener(() => {
  relayHealth.checkedAt = 0;
  void refreshRelayHealth();
});
chrome.permissions.onAdded.addListener(() => {
  relayHealth.checkedAt = 0;
  void refreshRelayHealth();
});
async function status() {
  const s = await load();
  if (
    relayHealth.relay !== (s.device?.relay ?? s.pairing?.qr.relay ?? null) ||
    Date.now() - relayHealth.checkedAt > 5000
  )
    void refreshRelayHealth();
  const relay = s.device?.relay ?? s.pairing?.qr.relay;
  const relayPermission = relay
    ? await chrome.permissions.contains({ origins: [`${relay}/*`] })
    : false;
  return {
    status: s.status,
    error: s.error,
    lastFill: s.lastFill ?? null,
    relayPermission,
    relayHealth:
      relayHealth.relay === relay && Date.now() - relayHealth.checkedAt < 90000
        ? relayHealth
        : null,
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
      } else if (m.type === "telemetry") {
        const s = await load();
        let relay: unknown = null;
        if (s.device) {
          try {
            relay = await client(s.device).diagnostics();
          } catch (e) {
            relay = { error: e instanceof Error ? e.message : String(e) };
          }
        }
        return { ...(await status()), telemetry: await readTelemetry(), relay };
      } else if (m.type === "clearTelemetry") {
        await clearTelemetry();
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
function launch(
  tabId: number,
  frameId: number,
  useContext: boolean,
  editable = true,
) {
  const clicked = Date.now();
  track("launch", {
    source: useContext ? "context-menu" : "shortcut",
    tabId,
    frameId,
    editable,
  });
  void exclusive(async () => {
    track("launch.dequeued", { waitedMs: Date.now() - clicked });
    await ready;
    try {
      await requestFill(tabId, frameId, useContext, editable);
      track("launch.done", { ms: Date.now() - clicked });
    } catch (e) {
      track("launch.failed", {
        ms: Date.now() - clicked,
        error: e instanceof Error ? e.message : String(e),
      });
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
    launch(tab.id, info.frameId ?? 0, true, info.editable);
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
    await chrome.alarms.create("relay-health", { periodInMinutes: 1 });
    void refreshRelayHealth();
    const s = await load();
    if (s.pending || s.pairing) schedule();
    await updateBadge();
    if (s.lastFill && s.lastFill.validUntil > Date.now()) startBadgeCountdown();
  }),
);
