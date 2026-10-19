import { requestContextSchema, type RequestContext } from "@otpbridge/protocol";
export interface ContextPreferences {
  location: boolean;
  publicIp: boolean;
  device: boolean;
  browser: boolean;
  deviceName: string;
}
export const defaultContextPreferences: ContextPreferences = {
  location: true,
  publicIp: true,
  device: true,
  browser: true,
  deviceName: "My computer",
};
export async function preferences(): Promise<ContextPreferences> {
  const { contextPreferences } =
    await chrome.storage.local.get("contextPreferences");
  return {
    ...defaultContextPreferences,
    ...(contextPreferences as Partial<ContextPreferences> | undefined),
  };
}
export async function bestEffortContext(): Promise<RequestContext> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    return await Promise.race([
      preferences()
        .then(collectContext)
        .catch(() => ({})),
      new Promise<RequestContext>((resolve) => {
        timer = setTimeout(() => resolve({}), 7000);
      }),
    ]);
  } finally {
    if (timer !== undefined) clearTimeout(timer);
  }
}
export async function collectContext(
  p: ContextPreferences,
): Promise<RequestContext> {
  const context: RequestContext = {};
  if (p.device) {
    context.device = {
      name: p.deviceName.trim().slice(0, 80) || "My computer",
      os: navigator.platform || "Unknown OS",
      ...(navigator.hardwareConcurrency
        ? { hardwareConcurrency: Math.min(1024, navigator.hardwareConcurrency) }
        : {}),
      ...((navigator as Navigator & { deviceMemory?: number }).deviceMemory
        ? {
            memoryGb: (navigator as Navigator & { deviceMemory: number })
              .deviceMemory,
          }
        : {}),
    };
  }
  if (p.browser) {
    const ua = navigator.userAgent;
    const match = /(Edg|Chrome|Firefox)\/([\d.]+)/.exec(ua);
    context.browser = {
      name: match?.[1] === "Edg" ? "Edge" : (match?.[1] ?? "Browser"),
      version: match?.[2] ?? "Unknown",
      language: navigator.language,
    };
  }
  if (
    p.publicIp &&
    (await chrome.permissions.contains({
      origins: ["https://api.ipify.org/*"],
    }))
  ) {
    try {
      const r = await fetch("https://api.ipify.org?format=json", {
        cache: "no-store",
        redirect: "error",
        signal: AbortSignal.timeout(2500),
      });
      if (r.ok) {
        const text = await r.text();
        if (text.length < 200) {
          const value = JSON.parse(text).ip;
          const parsed = requestContextSchema.safeParse({ publicIp: value });
          if (parsed.success) context.publicIp = parsed.data.publicIp;
        }
      }
    } catch {}
  }
  if (
    p.location &&
    (await chrome.permissions.contains({ permissions: ["geolocation"] }))
  ) {
    try {
      await chrome.offscreen.createDocument({
        url: "location.html",
        reasons: [chrome.offscreen.Reason.GEOLOCATION],
        justification:
          "Read location once for the user-initiated OTP request; never track in background.",
      });
      const result = await Promise.race([
        chrome.runtime.sendMessage({
          target: "location",
          type: "collectLocation",
        }),
        new Promise<null>((resolve) => setTimeout(() => resolve(null), 6000)),
      ]);
      if (result?.location) {
        const parsed = requestContextSchema.safeParse({
          location: result.location,
        });
        if (parsed.success) context.location = parsed.data.location;
      }
    } catch {
    } finally {
      await chrome.offscreen.closeDocument().catch(() => undefined);
    }
  }
  // Respect a toggle changed while a one-shot permission lookup was in flight.
  const latest = await preferences();
  if (!latest.location) delete context.location;
  if (!latest.publicIp) delete context.publicIp;
  if (!latest.device) delete context.device;
  if (!latest.browser) delete context.browser;
  return requestContextSchema.parse(context);
}
