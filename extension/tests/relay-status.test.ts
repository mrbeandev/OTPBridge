import { afterEach, expect, it, vi } from "vitest";
import { probeRelay } from "../src/background/relay-status";
afterEach(() => vi.unstubAllGlobals());
it("never marks connected or makes a request without relay permission", async () => {
  const fetch = vi.fn();
  vi.stubGlobal("fetch", fetch);
  expect((await probeRelay("https://relay.example", false)).reachable).toBe(
    false,
  );
  expect(fetch).not.toHaveBeenCalled();
});
it("only marks reachable after compatible health response", async () => {
  vi.stubGlobal(
    "fetch",
    vi
      .fn()
      .mockResolvedValue(
        new Response(JSON.stringify({ ok: true, protocolVersions: [1] })),
      ),
  );
  expect((await probeRelay("https://relay.example", true)).reachable).toBe(
    true,
  );
  vi.stubGlobal(
    "fetch",
    vi
      .fn()
      .mockResolvedValue(
        new Response(JSON.stringify({ ok: true, protocolVersions: [2] })),
      ),
  );
  expect((await probeRelay("https://relay.example", true)).reachable).toBe(
    false,
  );
});
it("clears reachable on network failure or malformed health response", async () => {
  vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("offline")));
  expect((await probeRelay("https://relay.example", true)).reachable).toBe(
    false,
  );
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("not json")));
  expect((await probeRelay("https://relay.example", true)).reachable).toBe(
    false,
  );
});
