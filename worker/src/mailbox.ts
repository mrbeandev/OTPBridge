import {
  boundedJson,
  DEVICE_TTL,
  hash,
  ID,
  json,
  MAX_MESSAGES,
  MAX_PAYLOAD,
  PAIR_TTL,
} from "./limits";
import { FCM_TOKEN, sendWake, type PushEnv } from "./push";
interface Config {
  browserHash: string;
  phoneHash: string;
  expiresAt: number;
  active: boolean;
  sequence: number;
}
interface StoredMessage {
  sequence: number;
  id: string;
  expiresAt: number;
  payload: string;
  role: "browser" | "phone";
}
type Trace = {
  t: number;
  ev: string;
  role?: string;
  status?: number;
  detail?: string;
  count?: number;
  seq?: number;
  msg?: string;
};
export class Mailbox implements DurableObject {
  // In-memory only: no storage writes, so diagnostics never consume quota.
  // Metadata only (role/status/sequence/id prefix) — never payloads or tokens.
  private traces: Trace[] = [];
  private trace(t: Omit<Trace, "t">) {
    this.traces.push({ t: Date.now(), ...t });
    if (this.traces.length > 200)
      this.traces.splice(0, this.traces.length - 200);
  }
  constructor(
    private readonly state: DurableObjectState,
    private readonly env: PushEnv = {},
  ) {}
  async fetch(request: Request): Promise<Response> {
    // Never hold the object's concurrency gate while reading an untrusted stream.
    let body: unknown;
    try {
      if (
        (new URL(request.url).pathname === "/messages" &&
          request.method === "POST") ||
        (new URL(request.url).pathname === "/push" && request.method === "PUT")
      )
        body = await boundedJson(request);
    } catch {
      return json({ error: "Invalid request" }, 400);
    }
    return this.state.blockConcurrencyWhile(async () => {
      try {
        return await this.handle(request, body);
      } catch {
        return json({ error: "Invalid request" }, 400);
      }
    });
  }
  private async handle(
    request: Request,
    parsedBody?: unknown,
  ): Promise<Response> {
    const path = new URL(request.url).pathname,
      now = Date.now(),
      s = this.state.storage;
    if (path === "/rate") {
      const rate = await s.get<{ count: number; until: number }>("rate");
      if (rate && rate.until > now && rate.count >= 10)
        return json({ error: "Rate limited" }, 429);
      const next =
        rate && rate.until > now
          ? { ...rate, count: rate.count + 1 }
          : { count: 1, until: now + 3600_000 };
      await s.put("rate", next);
      await s.setAlarm(next.until);
      return json({ ok: true });
    }
    if (path === "/init") {
      if (await s.get("config")) return json({ error: "Conflict" }, 409);
      const body = (await request.json()) as {
        browserHash: string;
        phoneHash: string;
        expiresAt: number;
      };
      if (
        !/^[a-f0-9]{64}$/.test(body.browserHash) ||
        !/^[a-f0-9]{64}$/.test(body.phoneHash) ||
        !Number.isSafeInteger(body.expiresAt) ||
        body.expiresAt <= now ||
        body.expiresAt > now + PAIR_TTL
      )
        return json({ error: "Invalid" }, 400);
      await s.put("config", {
        ...body,
        active: false,
        sequence: 0,
      } satisfies Config);
      await s.setAlarm(body.expiresAt);
      return json({ ok: true }, 201);
    }
    const cfg = await s.get<Config>("config");
    if (!cfg) return json({ error: "Channel unavailable" }, 404);
    if (cfg.expiresAt <= now) {
      await s.deleteAll();
      return json({ error: "Channel unavailable" }, 404);
    }
    const token = request.headers
      .get("Authorization")
      ?.match(/^Bearer ([A-Za-z0-9_-]{43})$/)?.[1];
    if (!token) {
      this.trace({ ev: "auth.missing", status: 401, detail: path });
      return json({ error: "Unauthorized" }, 401);
    }
    const digest = await hash(token);
    const role =
      digest === cfg.browserHash
        ? "browser"
        : digest === cfg.phoneHash
          ? "phone"
          : undefined;
    if (!role) {
      this.trace({ ev: "auth.rejected", status: 401, detail: path });
      return json({ error: "Unauthorized" }, 401);
    }
    if (path === "/diagnostics" && request.method === "GET")
      return json({
        now,
        active: cfg.active,
        sequence: cfg.sequence,
        expiresAt: cfg.expiresAt,
        traces: this.traces,
      });
    if (path === "/channel" && request.method === "DELETE") {
      this.trace({ ev: "channel.deleted", role, status: 204 });
      await s.deleteAll();
      await s.deleteAlarm();
      return new Response(null, { status: 204 });
    }
    if (path === "/activate" && request.method === "POST") {
      // Browser activates only after both explicit SAS confirmations.
      if (role !== "browser") return json({ error: "Forbidden" }, 403);
      if (cfg.active) return json({ ok: true });
      cfg.active = true;
      cfg.expiresAt = now + DEVICE_TTL;
      await s.put("config", cfg);
      await s.setAlarm(now + 120_000);
      return json({ ok: true });
    }
    if (path === "/push") {
      // Only the phone registers/removes its own push token for this channel.
      if (role !== "phone") return json({ error: "Forbidden" }, 403);
      if (request.method === "DELETE") {
        await s.delete("push");
        return new Response(null, { status: 204 });
      }
      const b = parsedBody as Record<string, unknown> | undefined;
      if (
        request.method !== "PUT" ||
        !b ||
        Object.keys(b).join() !== "token" ||
        typeof b.token !== "string" ||
        !FCM_TOKEN.test(b.token)
      )
        return json({ error: "Invalid push token" }, 400);
      await s.put("push", { token: b.token, lastSent: 0 });
      this.trace({ ev: "push.registered", role });
      return json({ ok: true, enabled: !!this.env.FCM_SERVICE_ACCOUNT });
    }
    const messages = await s.list<StoredMessage>({ prefix: "msg:" });
    for (const [key, m] of messages)
      if (m.expiresAt <= now) {
        await s.delete(key);
        messages.delete(key);
      }
    if (path === "/messages" && request.method === "POST") {
      const rateKey = `traffic:${role}`;
      let rate = await s.get<{ until: number; count: number }>(rateKey);
      if (!rate || rate.until <= now) rate = { until: now + 60_000, count: 0 };
      if (rate.count >= 30) {
        this.trace({ ev: "send.rate-limited", role, status: 429 });
        return json({ error: "Rate limited" }, 429);
      }
      const b = parsedBody as Record<string, any>;
      if (
        !b ||
        Object.keys(b).sort().join(",") !== "expiresAt,id,payload" ||
        !ID.test(b.id) ||
        !Number.isSafeInteger(b.expiresAt) ||
        b.expiresAt <= now ||
        b.expiresAt >
          Math.min(cfg.expiresAt, now + (cfg.active ? 120_000 : PAIR_TTL)) ||
        typeof b.payload !== "string" ||
        new TextEncoder().encode(b.payload).length > MAX_PAYLOAD
      ) {
        const reason = !b
          ? "no-body"
          : !ID.test(b.id)
            ? "bad-id"
            : !Number.isSafeInteger(b.expiresAt)
              ? "bad-expiry"
              : b.expiresAt <= now
                ? "already-expired"
                : b.expiresAt >
                    Math.min(
                      cfg.expiresAt,
                      now + (cfg.active ? 120_000 : PAIR_TTL),
                    )
                  ? "expiry-too-far"
                  : "bad-payload";
        this.trace({ ev: "send.invalid", role, status: 400, detail: reason });
        return json({ error: "Invalid message" }, 400);
      }
      if (await s.get(`seen:${b.id}`)) {
        this.trace({
          ev: "send.duplicate",
          role,
          status: 409,
          msg: String(b.id).slice(0, 8),
        });
        return json({ error: "Duplicate" }, 409);
      }
      if (messages.size >= MAX_MESSAGES) {
        this.trace({
          ev: "send.mailbox-full",
          role,
          status: 429,
          count: messages.size,
        });
        return json({ error: "Mailbox full" }, 429);
      }
      const m: StoredMessage = {
        id: b.id,
        expiresAt: b.expiresAt,
        payload: b.payload,
        sequence: ++cfg.sequence,
        role,
      };
      await s.put({
        [`msg:${String(m.sequence).padStart(16, "0")}`]: m,
        [`seen:${b.id}`]: b.expiresAt + PAIR_TTL,
        [rateKey]: { ...rate, count: rate.count + 1 },
        config: cfg,
      });
      await s.setAlarm(Math.min(now + 120_000, cfg.expiresAt));
      this.trace({
        ev: "send.stored",
        role,
        status: 201,
        seq: m.sequence,
        msg: m.id.slice(0, 8),
        count: messages.size + 1,
      });
      if (role === "browser") await this.wakePhone(now);
      return json({ ok: true }, 201);
    }
    if (path === "/messages" && request.method === "GET") {
      const afterRaw = new URL(request.url).searchParams.get("after") ?? "0";
      if (!/^\d{1,16}$/.test(afterRaw))
        return json({ error: "Invalid cursor" }, 400);
      const after = Number(afterRaw);
      if (!Number.isSafeInteger(after) || after > cfg.sequence)
        return json({ error: "Invalid cursor" }, 400);
      const visible = [...messages.values()]
        .filter((m) => m.role !== role && m.sequence > after)
        .sort((a, b) => a.sequence - b.sequence)
        .map(({ role: _, ...m }) => m);
      // Refresh inactivity only for active channels; never prolong unconfirmed pairing.
      if (cfg.active && cfg.expiresAt - now < DEVICE_TTL - 3600_000) {
        cfg.expiresAt = now + DEVICE_TTL;
        await s.put("config", cfg);
      }
      if (visible.length)
        this.trace({
          ev: "poll.delivered",
          role,
          count: visible.length,
          seq: cfg.sequence,
          detail: `after=${after}`,
        });
      return json({ messages: visible, cursor: cfg.sequence });
    }
    return json({ error: "Not found" }, 404);
  }
  /** Best effort; failures never affect message delivery. At most one push per 5 s. */
  private async wakePhone(now: number) {
    const s = this.state.storage;
    const push = await s.get<{ token: string; lastSent: number }>("push");
    if (!push || now - push.lastSent < 5_000) return;
    await s.put("push", { ...push, lastSent: now });
    const result = await sendWake(
      this.env,
      push.token,
      this.state.id.toString().slice(0, 16),
    );
    this.trace({ ev: `push.${result}` });
    if (result === "unregistered") await s.delete("push");
  }
  async alarm() {
    return this.state.blockConcurrencyWhile(() => this.cleanup());
  }
  private async cleanup() {
    const s = this.state.storage,
      now = Date.now();
    const cfg = await s.get<Config>("config");
    if (!cfg || cfg.expiresAt <= now) {
      await s.deleteAll();
      return;
    }
    for (const [k, m] of await s.list<StoredMessage>({ prefix: "msg:" }))
      if (m.expiresAt <= now) await s.delete(k);
    for (const [k, expiry] of await s.list<number>({ prefix: "seen:" }))
      if (expiry <= now) await s.delete(k);
    const remaining = await s.list({ prefix: "msg:", limit: 1 });
    const tombstones = await s.list({ prefix: "seen:", limit: 1 });
    await s.setAlarm(
      remaining.size || tombstones.size
        ? Math.min(now + 120_000, cfg.expiresAt)
        : cfg.expiresAt,
    );
  }
}
