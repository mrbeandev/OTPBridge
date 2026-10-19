// Content-free FCM wake-ups. The relay never learns what the request is for;
// the push only says "something is waiting", the app then fetches ciphertext.

export interface PushEnv {
  FCM_SERVICE_ACCOUNT?: string;
}
interface ServiceAccount {
  client_email: string;
  private_key: string;
  project_id: string;
  token_uri?: string;
}
let cached: { token: string; until: number; email: string } | undefined;

const b64url = (data: ArrayBuffer | Uint8Array | string) => {
  const bytes =
    typeof data === "string"
      ? new TextEncoder().encode(data)
      : new Uint8Array(data);
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
};

export function serviceAccount(env: PushEnv): ServiceAccount | null {
  if (!env.FCM_SERVICE_ACCOUNT) return null;
  try {
    const sa = JSON.parse(env.FCM_SERVICE_ACCOUNT) as ServiceAccount;
    return sa.client_email && sa.private_key && sa.project_id ? sa : null;
  } catch {
    return null;
  }
}

async function accessToken(sa: ServiceAccount): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  if (cached && cached.email === sa.client_email && cached.until > now + 60)
    return cached.token;
  const pem = sa.private_key
    .replace(/-----[^-]+-----/g, "")
    .replace(/\s+/g, "");
  const der = Uint8Array.from(atob(pem), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey(
    "pkcs8",
    der,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const aud = sa.token_uri ?? "https://oauth2.googleapis.com/token";
  const unsigned =
    b64url(JSON.stringify({ alg: "RS256", typ: "JWT" })) +
    "." +
    b64url(
      JSON.stringify({
        iss: sa.client_email,
        scope: "https://www.googleapis.com/auth/firebase.messaging",
        aud,
        iat: now,
        exp: now + 3600,
      }),
    );
  const sig = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(unsigned),
  );
  const res = await fetch(aud, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: `${unsigned}.${b64url(sig)}`,
    }),
  });
  if (!res.ok) throw new Error(`token ${res.status}`);
  const j = (await res.json()) as { access_token: string; expires_in: number };
  cached = {
    token: j.access_token,
    until: now + (j.expires_in || 3600),
    email: sa.client_email,
  };
  return j.access_token;
}

export type PushResult = "sent" | "unregistered" | "failed" | "disabled";

/** Sends a data-only, high-priority wake-up. Never includes request content. */
export async function sendWake(
  env: PushEnv,
  fcmToken: string,
  channelHint: string,
): Promise<PushResult> {
  const sa = serviceAccount(env);
  if (!sa) return "disabled";
  try {
    const token = await accessToken(sa);
    const res = await fetch(
      `https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`,
      {
        method: "POST",
        headers: {
          Authorization: `Bearer ${token}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          message: {
            token: fcmToken,
            // Collapse repeated wake-ups for the same paired browser.
            android: {
              priority: "HIGH",
              ttl: "90s",
              collapse_key: channelHint,
            },
            data: { t: "wake", c: channelHint },
          },
        }),
      },
    );
    if (res.ok) return "sent";
    const body = (await res.json().catch(() => ({}))) as {
      error?: { status?: string; details?: Array<{ errorCode?: string }> };
    };
    const code =
      body.error?.details?.find((d) => d.errorCode)?.errorCode ??
      body.error?.status;
    return res.status === 404 || code === "UNREGISTERED"
      ? "unregistered"
      : "failed";
  } catch {
    return "failed";
  }
}

export const FCM_TOKEN = /^[A-Za-z0-9_:-]{100,4096}$/;
