import React, { useEffect, useState } from "react";
import {
  defaultContextPreferences,
  type ContextPreferences,
} from "../background/request-context";
export function ContextSettings() {
  const [p, setP] = useState<ContextPreferences>(defaultContextPreferences),
    [message, setMessage] = useState("");
  useEffect(() => {
    void chrome.storage.local.get("contextPreferences").then((v) =>
      setP({
        ...defaultContextPreferences,
        ...(v.contextPreferences as Partial<ContextPreferences> | undefined),
      }),
    );
  }, []);
  async function change(
    key: keyof ContextPreferences,
    value: boolean | string,
  ) {
    const next = { ...p, [key]: value };
    setP(next);
    await chrome.storage.local.set({ contextPreferences: next });
  }
  return (
    <section>
      <h2>Request details</h2>
      <p className="muted">
        Controls what is collected and shown on your phone. Turning an item off
        stops collection—not just display. Website domain is always included.
      </p>
      <label className="toggle-row">
        <span>
          Requesting website <small>Required for safe approval</small>
        </span>
        <input type="checkbox" checked disabled />
      </label>
      {(
        [
          [
            "location",
            "Location",
            "One-shot browser location, accuracy and time. Uses the extension location permission only when enabled; GPS accuracy is not guaranteed.",
          ],
          [
            "publicIp",
            "Public IP",
            "Uses api.ipify.org only during requests. That service sees your network address; VPNs may change it.",
          ],
          [
            "device",
            "Device details",
            "Your chosen name, OS and browser-exposed CPU/memory hints; no serial numbers.",
          ],
          ["browser", "Browser details", "Browser version and language."],
        ] as const
      ).map(([key, label, detail]) => (
        <label className="toggle-row" key={key}>
          <span>
            {label}
            <small>{detail}</small>
          </span>
          <input
            type="checkbox"
            checked={p[key]}
            onChange={(e) => void change(key, e.target.checked)}
          />
        </label>
      ))}
      {p.device && (
        <label>
          Device name
          <input
            maxLength={80}
            value={p.deviceName}
            onChange={(e) => void change("deviceName", e.target.value)}
          />
        </label>
      )}

      <p className="muted">
        No background tracking. Details are end-to-end encrypted, but
        browser-provided context can be falsified by a compromised device.
        Denied/unavailable fields never block sending.
      </p>
      {message && <p role="status">{message}</p>}
    </section>
  );
}
