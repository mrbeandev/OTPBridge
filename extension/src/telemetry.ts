// Local-only diagnostics. Values are redacted when recorded and again when a
// report is exported: no OTP codes, secrets, keys, tokens, payloads, IPs,
// coordinates, emails, account names or URL paths can reach the report.
const KEY = "telemetry";
const MAX = 600;
export type TelemetryEvent = { t: number; ev: string; [k: string]: unknown };
let queue: TelemetryEvent[] = [];
let flushing: Promise<void> | undefined;
const FORBIDDEN_KEY =
  /otp|code|secret|seed|token|key|payload|cipher|nonce|lat|lon|^ip$|ip_|email|account|label|issuer|uri|bootstrap/i;
const ALLOWED_KEYS = new Set(["req", "msg"]);

/** Pattern-based scrubbing for free text such as error messages. */
export function redact(value: string): string {
  return value
    .slice(0, 300)
    .replace(/otpauth:\/\/\S+/gi, "[otpauth-uri]")
    .replace(/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g, "[email]")
    .replace(/\b\d{1,3}(\.\d{1,3}){3}\b/g, "[ip]")
    .replace(/\b[0-9a-f]{0,4}(:[0-9a-f]{0,4}){2,7}\b/gi, "[ip]")
    .replace(/-?\d{1,3}\.\d{3,},\s*-?\d{1,3}\.\d{3,}/g, "[coords]")
    .replace(/[A-Za-z0-9_-]{32,}/g, "[redacted-id]")
    .replace(/\b[A-Z2-7]{16,}=*\b/g, "[secret]")
    .replace(/(?<![\d.])\d{6,8}(?![\d.])/g, "[digits]")
    .replace(/(https?:\/\/[^\s/?#]+)[/?#]\S*/g, "$1/[path]");
}
function scrub(data: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(data)) {
    if (FORBIDDEN_KEY.test(k) && !ALLOWED_KEYS.has(k)) continue;
    if (typeof v === "string") out[k] = redact(v);
    else if (typeof v === "number" || typeof v === "boolean" || v === null)
      out[k] = v;
  }
  return out;
}
/** 8-char prefix of a random request/message ID, for correlation only. */
export const short = (id: string | undefined) =>
  id ? id.slice(0, 8) : undefined;
export function track(ev: string, data: Record<string, unknown> = {}): void {
  const event = { t: Date.now(), ev: ev.slice(0, 60), ...scrub(data) };
  console.info("[otpbridge]", event.ev, event);
  queue.push(event);
  flushing ??= (async () => {
    try {
      while (queue.length) {
        const batch = queue;
        queue = [];
        const stored = (await chrome.storage.local.get(KEY))[KEY] as
          TelemetryEvent[] | undefined;
        await chrome.storage.local.set({
          [KEY]: [...(stored ?? []), ...batch].slice(-MAX),
        });
      }
    } catch {
      /* Diagnostics must never break the product path. */
    } finally {
      flushing = undefined;
    }
  })();
}
export async function readTelemetry(): Promise<TelemetryEvent[]> {
  return (
    ((await chrome.storage.local.get(KEY))[KEY] as
      TelemetryEvent[] | undefined) ?? []
  );
}
export async function clearTelemetry(): Promise<void> {
  await chrome.storage.local.remove(KEY);
}
export interface TelemetrySummary {
  events: number;
  errors: number;
  first: number | null;
  last: number | null;
}
export function summarize(events: TelemetryEvent[]): TelemetrySummary {
  return {
    events: events.length,
    errors: events.filter((e) => /error|failed|rejected|denied/i.test(e.ev))
      .length,
    first: events[0]?.t ?? null,
    last: events.at(-1)?.t ?? null,
  };
}
function line(t: number, ev: string, rest: Record<string, unknown>): string {
  const fields = Object.entries(rest)
    .map(([k, v]) => `${k}=${redact(String(v))}`)
    .join(" ");
  return `${new Date(t).toISOString()}  ${ev.padEnd(26)} ${fields}`;
}
/** Plain-text report suitable for attaching to a public GitHub issue. */
export function formatReport(
  events: TelemetryEvent[],
  relay: unknown,
  meta: { version: string; browser: string },
): string {
  const s = summarize(events);
  const out = [
    "OTPBridge extension diagnostics report",
    `Generated: ${new Date().toISOString()}`,
    `Extension: ${meta.version}  Browser: ${redact(meta.browser)}`,
    `Events: ${s.events}  Errors/rejections: ${s.errors}`,
    "",
    "PRIVACY: This file never contains OTP codes, TOTP secrets, keys, relay tokens,",
    "encrypted payloads, IP addresses, coordinates, emails or account names.",
    "Values are redacted when recorded and again when exported. Review before sharing.",
    "-".repeat(78),
    ...events.map(({ t, ev, ...rest }) => line(t, ev, scrub(rest))),
  ];
  const r = relay as {
    traces?: Array<Record<string, unknown>>;
    error?: string;
    active?: boolean;
    sequence?: number;
  } | null;
  out.push("", "Relay channel traces (metadata only)", "-".repeat(78));
  if (!r) out.push("Not paired.");
  else if (r.error) out.push(`Unavailable: ${redact(String(r.error))}`);
  else {
    out.push(`active=${r.active} sequence=${r.sequence}`);
    for (const { t, ev, ...rest } of r.traces ?? [])
      out.push(line(Number(t), String(ev), scrub(rest)));
  }
  return out.join("\n") + "\n";
}
