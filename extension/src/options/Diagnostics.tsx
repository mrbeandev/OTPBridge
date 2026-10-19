import React, { useState } from "react";
type Event = { t: number; ev: string; [k: string]: unknown };
export function Diagnostics() {
  const [events, setEvents] = useState<Event[] | null>(null);
  async function load() {
    const r = await chrome.runtime.sendMessage({ type: "telemetry" });
    setEvents((r?.telemetry as Event[] | undefined) ?? []);
  }
  async function download() {
    const r = await chrome.runtime.sendMessage({ type: "telemetry" });
    const blob = new Blob(
      [
        JSON.stringify(
          {
            exportedAt: new Date().toISOString(),
            extension: r?.telemetry ?? [],
            relay: r?.relay ?? null,
          },
          null,
          1,
        ),
      ],
      { type: "application/json" },
    );
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `otpbridge-extension-telemetry-${Date.now()}.json`;
    a.click();
    URL.revokeObjectURL(a.href);
  }
  async function clear() {
    await chrome.runtime.sendMessage({ type: "clearTelemetry" });
    setEvents([]);
  }
  return (
    <section>
      <h2>Diagnostics</h2>
      <p className="muted">
        Local-only event log (last 600). No codes, keys, tokens, IPs or
        locations are recorded. Nothing is uploaded.
      </p>
      <button className="secondary" onClick={() => void load()}>
        Show recent events
      </button>
      <button className="secondary" onClick={() => void download()}>
        Export JSON
      </button>
      <button className="secondary" onClick={() => void clear()}>
        Clear
      </button>
      {events && (
        <pre className="telemetry">
          {events.length
            ? events
                .slice(-60)
                .reverse()
                .map((e) => {
                  const { t, ev, ...rest } = e;
                  return `${new Date(t).toLocaleTimeString()}  ${ev}  ${JSON.stringify(rest)}`;
                })
                .join("\n")
            : "No events yet."}
        </pre>
      )}
    </section>
  );
}
