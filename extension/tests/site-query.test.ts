import { expect, it } from "vitest";
import { siteFromUrl } from "../src/background/site-query";

it("shares only the HTTPS origin of the active tab", () => {
  expect(
    siteFromUrl("https://github.com/login?return_to=%2Fsettings#x"),
  ).toEqual({
    status: "ok",
    origin: "https://github.com",
  });
  expect(siteFromUrl("https://accounts.example.com/")).toEqual({
    status: "ok",
    origin: "https://accounts.example.com",
  });
});

it("never sends internal pages, plain http or garbage", () => {
  for (const url of [
    "chrome://extensions/",
    "http://example.com/",
    "file:///home/me/secret.txt",
    "about:blank",
    "not a url",
  ])
    expect(siteFromUrl(url)).toEqual({ status: "no-site" });
});

it("reports missing permission instead of guessing", () => {
  expect(siteFromUrl(undefined)).toEqual({ status: "no-permission" });
});
