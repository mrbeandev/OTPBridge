import React, { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import "../style.css";
function App() {
  const [s, setS] = useState<any>({});
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 250);
    return () => clearInterval(timer);
  }, []);
  const codeSeconds = s.lastFill
    ? Math.max(0, Math.ceil((s.lastFill.validUntil - now) / 1000))
    : 0;
  async function refresh() {
    try {
      const result = await chrome.runtime.sendMessage({ type: "status" });
      setS(
        result ?? {
          error: "Extension worker did not respond. Reload the extension.",
        },
      );
    } catch {
      setS({ error: "Could not reach extension worker." });
    }
  }
  useEffect(() => {
    let stopped = false;
    let timer: ReturnType<typeof setTimeout>;
    async function tick() {
      await refresh();
      if (!stopped) timer = setTimeout(() => void tick(), 2200);
    }
    void chrome.runtime
      .sendMessage({ type: "ackDenial" })
      .then((result) => {
        if (!stopped && result) setS(result);
      })
      .catch(() => undefined)
      .finally(() => {
        if (!stopped) void tick();
      });
    return () => {
      stopped = true;
      clearTimeout(timer);
    };
  }, []);
  return (
    <main className="popup">
      <span className="eyebrow">OTPBRIDGE</span>
      <h1>
        {s.denial
          ? s.denial.kind === "pairing"
            ? "Pairing declined"
            : "OTP request denied"
          : "Approve on your phone."}
      </h1>
      <p aria-live="polite">{s.status ?? "Loading…"}</p>
      {s.denial && (
        <section role="status" className="denial-card">
          {s.denial.origin && <strong>{s.denial.origin}</strong>}
          <p>
            {s.denial.kind === "pairing"
              ? "Your phone declined this browser. No connection was created."
              : "No code was shared. You can start a new request from the OTP input."}
          </p>
          <p className="muted">
            {s.denial.reason === "account-mismatch"
              ? "The requested account did not match the saved website binding."
              : new Date(s.denial.at).toLocaleTimeString()}
          </p>
        </section>
      )}
      {s.lastFill && (
        <section>
          <strong>{s.lastFill.origin}</strong>
          <h2>
            {codeSeconds > 0 ? `${codeSeconds}s remaining` : "Code expired"}
          </h2>
          <p className="muted">
            {codeSeconds > 0
              ? "Submit before this code expires."
              : "Request a fresh code if you still need one."}
          </p>
        </section>
      )}
      {s.pending && (
        <section>
          <strong>{s.pending.origin}</strong>
          <p>
            Open OTPBridge on Android, review this request, and approve with
            biometrics.
          </p>
          <button
            className="secondary"
            onClick={async () => {
              await chrome.runtime.sendMessage({ type: "cancel" });
              await refresh();
            }}
          >
            Cancel request
          </button>
        </section>
      )}
      {s.error && <p className="error">{s.error}</p>}
      <button onClick={() => void chrome.runtime.openOptionsPage()}>
        {s.device ? "Settings & devices" : "Set up OTPBridge"}
      </button>
      <p className="muted">No notifications. No automatic submission.</p>
    </main>
  );
}
createRoot(document.getElementById("root")!).render(<App />);
