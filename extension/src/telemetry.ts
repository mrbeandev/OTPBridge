// Local-only diagnostics. Never record OTPs, secrets, tokens, keys, payloads,
// location or IP. Events stay in chrome.storage.local until exported/cleared.
const KEY = "telemetry";
const MAX = 600;
export type TelemetryEvent = { t: number; ev: string; [k: string]: unknown };
let queue: TelemetryEvent[] = [];
let flushing: Promise<void> | undefined;
const FORBIDDEN =
  /otp|secret|token|key|payload|ciphertext|nonce|latitude|longitude|ip$/i;
function scrub(data: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(data)) {
    if (FORBIDDEN.test(k)) continue;
    if (typeof v === "string") out[k] = v.slice(0, 300);
    else if (typeof v === "number" || typeof v === "boolean" || v === null)
      out[k] = v;
  }
  return out;
}
/** Short, non-reversible-in-practice correlation prefix for transport/request IDs. */
export const short = (id: string | undefined) =>
  id ? id.slice(0, 8) : undefined;
export function track(ev: string, data: Record<string, unknown> = {}): void {
  const event = { t: Date.now(), ev, ...scrub(data) };
  console.info("[otpbridge]", ev, event);
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
