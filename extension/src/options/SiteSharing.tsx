import React, { useEffect, useState } from "react";

/**
 * Lets the phone ask which website is open when adding a new account, so the
 * account is linked to that site automatically. Needs Chrome's optional "tabs"
 * permission to read the active tab's address; only the site (scheme + host) is
 * sent, end-to-end encrypted, and only when the phone asks.
 */
export function SiteSharing() {
  const [enabled, setEnabled] = useState(false);
  const [message, setMessage] = useState("");
  useEffect(() => {
    void chrome.permissions
      .contains({ permissions: ["tabs"] })
      .then(setEnabled);
  }, []);
  async function toggle(on: boolean) {
    try {
      const ok = on
        ? await chrome.permissions.request({ permissions: ["tabs"] })
        : await chrome.permissions.remove({ permissions: ["tabs"] });
      if (ok) setEnabled(on);
      setMessage(
        on && !ok
          ? "Permission not granted; the phone will ask you to type the site."
          : "",
      );
    } catch (e) {
      setMessage(String(e));
    }
  }
  return (
    <section>
      <h2>Link new accounts to this website</h2>
      <p className="muted">
        When you add an account on your phone, it can ask this browser which
        site is open and link the account to it. Only the site address (for
        example github.com) is shared, encrypted, and only when your phone asks.
      </p>
      <label className="toggle-row">
        <span>
          Share the current site with my phone{" "}
          <small>Chrome will ask to “read your browsing history”</small>
        </span>
        <input
          type="checkbox"
          checked={enabled}
          onChange={(e) => void toggle(e.target.checked)}
        />
      </label>
      {message && <p className="muted">{message}</p>}
    </section>
  );
}
