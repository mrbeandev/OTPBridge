import { expect, it } from "vitest";
import { createDenial, showDenialDot } from "../src/background/denial";
it("shows unread denial for exactly 30 seconds and then restores connection status", () => {
  const d = createDenial("otp", "denied", 1000, "https://example.com");
  expect(showDenialDot(d, 1000)).toBe(true);
  expect(showDenialDot(d, 30999)).toBe(true);
  expect(showDenialDot(d, 31000)).toBe(false);
  expect(d.reason).toBe("denied");
});
it("acknowledgement hides dot immediately but preserves denial message", () => {
  const d = createDenial("pairing", "denied", 1000);
  d.acknowledged = true;
  expect(showDenialDot(d, 1001)).toBe(false);
  expect(d.kind).toBe("pairing");
});
