export type RelayHealth = {
  relay: string | null;
  reachable: boolean;
  permission: boolean;
  checkedAt: number;
};
export async function probeRelay(
  relay: string,
  permission: boolean,
): Promise<RelayHealth> {
  const result = { relay, permission, reachable: false, checkedAt: Date.now() };
  if (!permission) return result;
  try {
    const response = await fetch(`${relay}/health`, {
      cache: "no-store",
      redirect: "error",
      signal: AbortSignal.timeout(4000),
    });
    if (!response.ok) return result;
    const text = await response.text();
    if (text.length > 2048) return result;
    const data = JSON.parse(text);
    result.reachable =
      data.ok === true &&
      Array.isArray(data.protocolVersions) &&
      data.protocolVersions.includes(1);
  } catch {
    /* Offline is a normal status, not authentication failure. */
  }
  return result;
}
