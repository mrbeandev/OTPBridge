import { describe, expect, it } from "vitest";
import { formatReport, redact } from "../src/telemetry";
describe("diagnostics redaction", () => {
  it("removes codes, secrets, tokens and personal identifiers", () => {
    expect(redact("approved code 482913")).not.toContain("482913");
    expect(redact("seed JBSWY3DPEHPK3PXPJBSWY3DP")).not.toContain(
      "JBSWY3DPEHPK3PXP",
    );
    expect(redact("otpauth://totp/A:b@c.com?secret=ABC")).toBe("[otpauth-uri]");
    expect(redact("Kq3n8vS0b2xW1cY7dE4fG6hJ9kL5mN0pQ2rS4tU6vW8")).toBe(
      "[redacted-id]",
    );
    expect(redact("bob@example.com")).toBe("[email]");
    expect(redact("from 202.164.130.49")).toBe("from [ip]");
    expect(redact("11.2820224, 75.7727232")).toBe("[coords]");
    expect(redact("https://github.com/login?x=1")).toBe(
      "https://github.com/[path]",
    );
  });
  it("keeps useful diagnostic text", () => {
    expect(redact("Relay error 409")).toBe("Relay error 409");
  });
  it("report drops forbidden keys even if stored by an older build", () => {
    const text = formatReport(
      [
        {
          t: 0,
          ev: "fill.result",
          otp: "123456",
          token: "x",
          accountLabel: "bob",
          req: "abcd1234",
          filled: true,
        },
      ],
      {
        active: true,
        sequence: 3,
        traces: [{ t: 0, ev: "send.stored", payload: "secret", status: 201 }],
      },
      { version: "0.10.0", browser: "Chrome/155" },
    );
    expect(text).not.toMatch(/123456|token=|accountLabel|payload=/);
    expect(text).toContain("req=abcd1234");
    expect(text).toContain("filled=true");
    expect(text).toContain("status=201");
    expect(text).toContain("PRIVACY");
  });
});
