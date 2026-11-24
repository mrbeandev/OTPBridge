// Answers the phone's "which site is open?" question so a newly scanned
// account can be linked to its website automatically. Only the origin
// (scheme + host) of the active tab in the focused window is shared, and only
// with the phone over the end-to-end encrypted channel.
import { validOrigin } from "@otpbridge/protocol";

export type SiteAnswer =
  { status: "ok"; origin: string } | { status: "no-permission" | "no-site" };

/** Pure: turns a tab URL (or its absence) into the answer the phone receives. */
export function siteFromUrl(url: string | undefined): SiteAnswer {
  if (url === undefined) return { status: "no-permission" };
  try {
    const origin = new URL(url).origin;
    return validOrigin(origin)
      ? { status: "ok", origin }
      : { status: "no-site" };
  } catch {
    return { status: "no-site" };
  }
}

/**
 * Reads the active tab of the last-focused normal window. Without the `tabs`
 * permission Chrome only exposes tab.url for hosts we can access, so the
 * extension may honestly report "no-permission" instead of guessing.
 */
export async function activeSite(): Promise<SiteAnswer> {
  const [tab] = await chrome.tabs.query({
    active: true,
    lastFocusedWindow: true,
    windowType: "normal",
  });
  if (!tab) return { status: "no-site" };
  return siteFromUrl(tab.url);
}
