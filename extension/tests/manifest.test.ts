import { expect, it } from "vitest";
import manifest from "../manifest.json";
it("declares geolocation as required because Chrome rejects it as optional", () => {
  expect(manifest.permissions).toContain("geolocation");
  expect(
    (manifest as { optional_permissions?: string[] }).optional_permissions ??
      [],
  ).not.toContain("geolocation");
  expect(manifest.permissions).toContain("offscreen");
  expect(manifest.host_permissions).toEqual([
    "https://api.ipify.org/*",
    "https://otpbridge.mrbean.dev/*",
  ]);
});
