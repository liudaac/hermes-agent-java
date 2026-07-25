/**
 * useActiveHarnesses - shared hook for polling active harnesses.
 *
 * Single request shared across all EmployeeCard instances on a page,
 * instead of N cards each polling independently.
 */
import { useEffect, useState } from "react";
import { portalApi } from "@/api/portal";

export interface HarnessInfo {
  sessionId: string;
  tenantId: string;
  status: string;
  debug: Record<string, unknown>;
}

export function useActiveHarnesses(intervalMs = 10_000) {
  const [harnesses, setHarnesses] = useState<HarnessInfo[]>([]);

  useEffect(() => {
    let alive = true;
    const poll = () => {
      portalApi
        .getActiveHarnesses()
        .then((res) => {
          if (!alive) return;
          setHarnesses(res.harnesses ?? []);
        })
        .catch(() => {});
    };
    poll();
    const timer = setInterval(poll, intervalMs);
    return () => {
      alive = false;
      clearInterval(timer);
    };
  }, [intervalMs]);

  /** Find a harness matching a team ID. */
  function findForTeam(teamId: string): HarnessInfo | undefined {
    return harnesses.find(
      (h) =>
        h.sessionId.includes(teamId) ||
        (h.debug as Record<string, unknown>)?.teamId === teamId,
    );
  }

  return { harnesses, findForTeam };
}
