import { expect, it } from "vitest";
import { expiryBadge, remainingSeconds } from "../src/background/countdown";
it("rounds live expiry upward and clears at the exact deadline", () => {
  expect(remainingSeconds(30000, 0)).toBe(30);
  expect(expiryBadge(30000, 29001)).toBe("1");
  expect(expiryBadge(30000, 30000)).toBe("");
  expect(expiryBadge(30000, 31000)).toBe("");
});
it("uses absolute expiry after worker restart instead of resetting the timer", () => {
  expect(expiryBadge(120000, 100000)).toBe("20");
  expect(expiryBadge(120000, 115000)).toBe("5");
});
