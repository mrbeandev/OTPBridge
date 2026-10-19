export function remainingSeconds(validUntil: number, now = Date.now()): number {
  return Math.max(0, Math.ceil((validUntil - now) / 1000));
}
export function expiryBadge(validUntil: number, now = Date.now()): string {
  const remaining = remainingSeconds(validUntil, now);
  return remaining > 0 ? String(remaining) : "";
}
