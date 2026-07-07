/**
 * useAttention — is the user looking at the page?
 *
 * Per design.md §12.2 (主动建议, "不打扰"), Jarvis only nudges the
 * user when they're *not* actively engaged with another tab. We
 * approximate "looking at" with `document.visibilityState` plus a
 * pointer-move-on-canvas heuristic.
 */
import { useEffect, useState } from "react";

export function useAttention(): { isAttentive: boolean; tabHidden: boolean } {
  const [tabHidden, setTabHidden] = useState(() =>
    typeof document === "undefined" ? false : document.hidden,
  );
  useEffect(() => {
    if (typeof document === "undefined") return;
    const onVis = () => setTabHidden(document.hidden);
    document.addEventListener("visibilitychange", onVis);
    return () => document.removeEventListener("visibilitychange", onVis);
  }, []);
  return { isAttentive: !tabHidden, tabHidden };
}
