import React, { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import "../style.css";
function App() {
  const [s, setS] = useState<any>({});
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
    void tick();
    return () => {
      stopped = true;
      clearTimeout(timer);
    };
  }, []);
  return (
    <main className="popup">
      <span className="eyebrow">OTPBRIDGE</span>
      <h1>Approve on your phone.</h1>
      <p aria-live="polite">{s.status ?? "Loading…"}</p>
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
