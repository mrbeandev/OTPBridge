// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  captureTarget,
  fillTarget as guardedFill,
} from "../src/content/target";
const fillTarget = (token: string, origin: string, otp: string) =>
  guardedFill(token, origin, otp, Date.now() + 90000, Date.now() + 30000);
beforeEach(() => {
  vi.restoreAllMocks();
  vi.spyOn(HTMLElement.prototype, "getClientRects").mockReturnValue({
    length: 1,
  } as DOMRectList);
  vi.spyOn(document, "visibilityState", "get").mockReturnValue("visible");
  document.body.innerHTML = '<form><input id="otp" type="text"></form>';
  delete (window as any).__otpbridge;
});
describe("guarded field insertion", () => {
  it("captures focused field and fills without submitting", () => {
    const input = document.querySelector("input")!;
    input.focus();
    const t = captureTarget(false)!;
    let inputs = 0,
      submits = 0;
    input.addEventListener("input", () => inputs++);
    document.querySelector("form")!.addEventListener("submit", () => submits++);
    expect(fillTarget(t.fieldToken, t.origin, "012345")).toBe(true);
    expect(input.value).toBe("012345");
    expect(inputs).toBe(1);
    expect(submits).toBe(0);
    expect(fillTarget(t.fieldToken, t.origin, "012345")).toBe(false);
  });
  it("does not guess when the menu was not opened on an editable field", () => {
    document.querySelector("input")!.focus();
    expect(captureTarget(true, false)).toBe(null);
  });
  it("resolves the first right-click on an editable field without a second click", () => {
    const input = document.querySelector("input")!;
    input.focus();
    const t = captureTarget(true, true)!;
    expect(t.how).toBe("editable-focus");
    expect(fillTarget(t.fieldToken, t.origin, "012345")).toBe(true);
    expect(input.value).toBe("012345");
  });
  it("prefers a recorded trusted right-click target over focus", () => {
    document.body.innerHTML = '<input id="a"><input id="b">';
    const [a, b] = [...document.querySelectorAll("input")];
    captureTarget(true, false); // installs listener
    a!.focus();
    // Synthetic events are untrusted and ignored; seed what a real trusted
    // right-click on `b` records.
    const state = (window as any).__otpbridge;
    state.last = b; state.lastAt = Date.now();
    const t = captureTarget(true, true)!;
    expect(t.how).toBe("remembered");
    expect(fillTarget(t.fieldToken, t.origin, "012345")).toBe(false); // b is not focused
  });
  it("ignores untrusted synthetic contextmenu events", () => {
    document.body.innerHTML = '<input id="a"><input id="b">';
    const [a, b] = [...document.querySelectorAll("input")];
    captureTarget(true, false);
    a!.focus();
    b!.dispatchEvent(new MouseEvent("contextmenu", { bubbles: true }));
    expect(captureTarget(true, true)!.how).toBe("editable-focus");
  });
  it("rejects changed value, lost focus, detached field and wrong origin", () => {
    for (const mutate of [
      (i: HTMLInputElement) => {
        i.value = "changed";
      },
      (i: HTMLInputElement) => i.blur(),
      (i: HTMLInputElement) => i.remove(),
    ]) {
      document.body.innerHTML = "<input>";
      const i = document.querySelector("input")!;
      i.focus();
      const t = captureTarget(false)!;
      mutate(i);
      expect(fillTarget(t.fieldToken, t.origin, "012345")).toBe(false);
    }
    document.body.innerHTML = "<input>";
    const i = document.querySelector("input")!;
    i.focus();
    const t = captureTarget(false)!;
    expect(
      fillTarget(t.fieldToken, "https://different.example", "012345"),
    ).toBe(false);
    expect(i.value).toBe("");
  });
  it("rejects disabled, readonly, unsupported and too-short controls", () => {
    for (const attr of ["disabled", "readonly", 'type="email"']) {
      document.body.innerHTML = `<input ${attr}>`;
      document.querySelector("input")!.focus();
      expect(captureTarget(false)).toBe(null);
    }
    document.body.innerHTML = '<input maxlength="4">';
    document.querySelector("input")!.focus();
    const t = captureTarget(false)!;
    expect(fillTarget(t.fieldToken, t.origin, "012345")).toBe(false);
  });
  it("rechecks code and request expiry at insertion", () => {
    const input = document.querySelector("input")!;
    input.focus();
    const t = captureTarget(false)!;
    expect(
      guardedFill(
        t.fieldToken,
        t.origin,
        "012345",
        Date.now() + 90000,
        Date.now() + 1000,
      ),
    ).toBe(false);
    expect(input.value).toBe("");
    expect(
      guardedFill(
        t.fieldToken,
        t.origin,
        "012345",
        Date.now() - 1,
        Date.now() + 30000,
      ),
    ).toBe(false);
  });
  it("rejects arbitrary strings instead of a valid OTP", () => {
    document.querySelector("input")!.focus();
    const t = captureTarget(false)!;
    expect(fillTarget(t.fieldToken, t.origin, "<script>")).toBe(false);
  });
});
