import { afterEach, beforeAll, expect, it, vi } from "vitest";
import { ready, randomId, RelayClient } from "../src/index";
beforeAll(() => ready);
afterEach(() => vi.unstubAllGlobals());
it("normalizes relay origin and does not redirect bearer credentials", async () => {
  const channel = randomId(),
    token = randomId();
  const fetchMock = vi
    .fn()
    .mockResolvedValue(
      new Response(JSON.stringify({ messages: [], cursor: 0 }), {
        headers: { "Content-Type": "application/json" },
      }),
    );
  vi.stubGlobal("fetch", fetchMock);
  await new RelayClient("https://relay.example/", channel, token).poll(0);
  const [url, init] = fetchMock.mock.calls[0]!;
  expect(url).toBe(
    `https://relay.example/v1/channels/${channel}/messages?after=0`,
  );
  expect(init.redirect).toBe("error");
  expect(init.cache).toBe("no-store");
  expect(init.headers.Authorization).toBe(`Bearer ${token}`);
});
it("rejects rewound cursors and duplicate/out-of-order peer messages", async () => {
  const channel = randomId(),
    token = randomId(),
    id = randomId();
  for (const body of [
    { messages: [], cursor: 3 },
    {
      messages: [
        { sequence: 6, id, expiresAt: Date.now() + 1000, payload: "opaque" },
      ],
      cursor: 5,
    },
    {
      messages: [
        { sequence: 5, id, expiresAt: Date.now() + 1000, payload: "opaque" },
        { sequence: 6, id, expiresAt: Date.now() + 1000, payload: "opaque" },
      ],
      cursor: 6,
    },
  ]) {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response(JSON.stringify(body))),
    );
    await expect(
      new RelayClient("https://relay.example", channel, token).poll(4),
    ).rejects.toThrow();
  }
});
