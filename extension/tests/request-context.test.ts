import { afterEach, expect, it, vi } from "vitest";
import {
  collectContext,
  defaultContextPreferences,
} from "../src/background/request-context";
afterEach(() => vi.unstubAllGlobals());
it("collects and sends nothing when every optional detail is disabled", async () => {
  const fetch = vi.fn();
  const contains = vi.fn();
  vi.stubGlobal("fetch", fetch);
  vi.stubGlobal("chrome", {
    permissions: { contains },
    storage: { local: { get: vi.fn().mockResolvedValue({}) } },
  });
  expect(
    await collectContext({
      ...defaultContextPreferences,
      location: false,
      publicIp: false,
      device: false,
      browser: false,
    }),
  ).toEqual({});
  expect(fetch).not.toHaveBeenCalled();
  expect(contains).not.toHaveBeenCalled();
});
it("does not fetch location or public IP when access was not granted", async () => {
  const fetch = vi.fn();
  vi.stubGlobal("fetch", fetch);
  vi.stubGlobal("chrome", {
    permissions: { contains: vi.fn().mockResolvedValue(false) },
    storage: { local: { get: vi.fn().mockResolvedValue({}) } },
  });
  expect(
    await collectContext({
      ...defaultContextPreferences,
      device: false,
      browser: false,
    }),
  ).toEqual({});
  expect(fetch).not.toHaveBeenCalled();
});
