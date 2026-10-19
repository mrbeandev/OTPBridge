import React, { useEffect, useState } from "react";
import {
  formatReport,
  summarize,
  type TelemetryEvent,
  type TelemetrySummary,
} from "../telemetry";
export function Diagnostics() {
  const [summary, setSummary] = useState<TelemetrySummary | null>(null);
  const [message, setMessage] = useState("");
  async function fetchAll() {
    return (await chrome.runtime.sendMessage({ type: "telemetry" })) as {
      telemetry?: TelemetryEvent[];
      relay?: unknown;
    };
  }
  async function load() {
    const r = await fetchAll();
    setSummary(summarize(r?.telemetry ?? []));
  }
  useEffect(() => {
    void load();
  }, []);
  async function download() {
    const r = await fetchAll();
    const text = formatReport(r?.telemetry ?? [], r?.relay ?? null, {
      version: chrome.runtime.getManifest().version,
      browser:
        navigator.userAgent.match(/(Edg|Chrome|Firefox)\/\d+/)?.[0] ??
        "unknown",
    });
    const stamp = new Date().toISOString().replace(/[:.]/g, "-").slice(0, 19);
    const a = document.createElement("a");
    a.href = URL.createObjectURL(new Blob([text], { type: "text/plain" }));
    a.download = `otpbridge-extension-diagnostics-${stamp}.txt`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
    setMessage("Report saved. Attach the .txt file to your GitHub issue.");
  }
  async function clear() {
    await chrome.runtime.sendMessage({ type: "clearTelemetry" });
    setMessage("Diagnostics cleared.");
    await load();
  }
  const fmt = (t: number | null) => (t ? new Date(t).toLocaleString() : "—");
  return (
    <section className="diagnostics">
      <h2>Diagnostics</h2>
      <p className="muted">
        A local log of extension activity to help fix bugs. If something goes
        wrong, download the report and attach it to a GitHub issue.
      </p>
      {summary && (
        <dl className="diag-summary">
          <dt>Events recorded</dt>
          <dd>{summary.events}</dd>
          <dt>Errors / rejections</dt>
          <dd>{summary.errors}</dd>
          <dt>Oldest</dt>
          <dd>{fmt(summary.first)}</dd>
          <dt>Newest</dt>
          <dd>{fmt(summary.last)}</dd>
        </dl>
      )}
      <div className="diag-privacy">
        <strong>Safe to share</strong>
        <p>
          Never includes OTP codes, secret keys, relay tokens, encrypted
          messages, IP addresses, locations, emails or account names. These are
          removed when recorded and again on export. Stored only in this
          browser; nothing is uploaded.
        </p>
      </div>
      <button disabled={!summary?.events} onClick={() => void download()}>
        Download report (.txt)
      </button>{" "}
      <button
        className="secondary"
        disabled={!summary?.events}
        onClick={() => void clear()}
      >
        Clear log
      </button>
      {message && <p className="muted">{message}</p>}
    </section>
  );
}
