import React, { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import QRCode from "qrcode";
import { normalizeRelay } from "@otpbridge/protocol";
import "../style.css";
import { ContextSettings } from "./ContextSettings";
import { Diagnostics } from "./Diagnostics";
function App() {
  const [relay, setRelay] = useState(""),
    [name, setName] = useState("Laptop"),
    [s, setS] = useState<any>({}),
    [qr, setQr] = useState(""),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  async function command(type: string, extra = {}) {
    try {
      const result = await chrome.runtime.sendMessage({ type, ...extra });
      if (!result)
        throw new Error(
          "Extension worker did not respond. Reload the extension.",
        );
      setS(result);
      setError(result.error ?? "");
      return result;
    } catch (e) {
      setError(String(e));
    }
  }
  useEffect(() => {
    let stopped = false;
    let timer: ReturnType<typeof setTimeout>;
    async function refresh() {
      await command("status");
      if (!stopped) timer = setTimeout(() => void refresh(), 2200);
    }
    void refresh();
    return () => {
      stopped = true;
      clearTimeout(timer);
    };
  }, []);
  useEffect(() => {
    let live = true;
    if (s.pairing?.qr)
      void QRCode.toDataURL(s.pairing.qr, {
        width: 420,
        errorCorrectionLevel: "M",
        margin: 2,
      }).then((v) => {
        if (live) setQr(v);
      });
    else setQr("");
    return () => {
      live = false;
    };
  }, [s.pairing?.qr]);
  async function pair() {
    setBusy(true);
    setError("");
    try {
      const origin = normalizeRelay(relay);
      const allowed = await chrome.permissions.request({
        origins: [`${origin}/*`],
      });
      if (!allowed) throw new Error("Relay access was not granted");
      await command("startPair", { relay: origin, browserName: name });
    } catch (e) {
      setError(String(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <main>
      <header>
        <img
          className="brand-mark"
          src="icons/icon-128.png"
          alt=""
          width="56"
          height="56"
        />
        <span className="eyebrow">PHONE-APPROVED AUTHENTICATOR</span>
        <h1>OTPBridge</h1>
        <p>Your phone holds the secrets. Your relay carries ciphertext.</p>
      </header>
      <section>
        <h2>{s.device ? "Connected phone" : "Connect your phone"}</h2>
        {s.device ? (
          <>
            <p>
              <strong>{s.device.phoneName}</strong> · {s.device.browserName}
            </p>
            <code>{s.device.relay}</code>
            <p>
              Right-click a focused OTP input and choose{" "}
              <strong>Fill from phone</strong>, or press <kbd>Alt+Shift+O</kbd>.
              Then open the Android app and approve.
            </p>
            <p className="muted">
              {s.relayHealth?.reachable
                ? "Relay reachable · phone approval is still required"
                : "Relay status is being checked or is unavailable"}
            </p>
            {s.device.relay ===
              "https://otpbridge-relay.yousef-nabil-qa.workers.dev" && (
              <p className="error">
                This pairing still uses the earlier relay. To use your
                gtulasinathreddy account, unpair and pair again with
                https://otpbridge-relay.gtulasinathreddy.workers.dev. Accounts
                on your phone are not deleted.
              </p>
            )}
            {s.relayPermission === false && (
              <button
                className="secondary"
                onClick={async () => {
                  try {
                    const granted = await chrome.permissions.request({
                      origins: [`${normalizeRelay(s.device.relay)}/*`],
                    });
                    setError(granted ? "" : "Relay permission was not granted");
                  } catch (e) {
                    setError(String(e));
                  }
                }}
              >
                Restore relay permission
              </button>
            )}
            <button
              className="secondary"
              onClick={() => {
                if (
                  confirm(
                    "Unpair locally? Also remove this browser on your phone.",
                  )
                )
                  void command("unpair");
              }}
            >
              Unpair phone
            </button>
          </>
        ) : s.pairing ? (
          <>
            <p>
              In Android, choose <strong>Pair browser</strong> and scan this QR.
              Treat it like a password; do not share it.
            </p>
            {qr && (
              <img
                className="qr"
                src={qr}
                alt="Secret one-time phone pairing QR"
              />
            )}
            {s.pairing.sas && (
              <div className="verify">
                <h3>Approve on your phone</h3>
                <p>
                  Check the browser name and relay in the phone approval sheet.
                  This browser connects automatically when you approve.
                </p>
              </div>
            )}
            <button
              className="secondary"
              onClick={() => void command("cancel")}
            >
              Cancel pairing
            </button>
          </>
        ) : (
          <>
            <label>
              Relay URL
              <input
                placeholder="https://otpbridge-relay.your-name.workers.dev"
                value={relay}
                onChange={(e) => setRelay(e.target.value)}
                autoComplete="off"
              />
            </label>
            <label>
              Browser name
              <input
                value={name}
                maxLength={80}
                onChange={(e) => setName(e.target.value)}
              />
            </label>
            <button disabled={busy} onClick={() => void pair()}>
              {busy ? "Creating pairing…" : "Create pairing QR"}
            </button>
            <p className="muted">
              Deploy your own Cloudflare Worker first. No account or
              notification service is required.
            </p>
          </>
        )}
      </section>
      <ContextSettings />
      <Diagnostics />
      <section aria-live="polite">
        <h2>Status</h2>
        <p>{s.status}</p>
        {(error || s.error) && <p className="error">{error || s.error}</p>}
      </section>
      <footer>
        V1: manually open the phone app. Codes are filled, never submitted. This
        does not make TOTP phishing-resistant. Physical-device verification and
        independent security review are still required.
      </footer>
    </main>
  );
}
createRoot(document.getElementById("root")!).render(<App />);
