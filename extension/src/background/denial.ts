export const DENIAL_DOT_MS = 30_000;
export interface DenialState {
  kind: "otp" | "pairing";
  reason: string;
  at: number;
  unreadUntil: number;
  acknowledged: boolean;
  origin?: string;
}
export function createDenial(
  kind: DenialState["kind"],
  reason: string,
  now = Date.now(),
  origin?: string,
): DenialState {
  return {
    kind,
    reason,
    at: now,
    unreadUntil: now + DENIAL_DOT_MS,
    acknowledged: false,
    ...(origin ? { origin } : {}),
  };
}
export function showDenialDot(
  denial: DenialState | undefined,
  now = Date.now(),
): boolean {
  return !!denial && !denial.acknowledged && denial.unreadUntil > now;
}
