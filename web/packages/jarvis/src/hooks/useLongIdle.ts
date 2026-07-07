/**
 * useLongIdle — track how long the user has been idle.
 *
 * Per design.md §4.3, after > 5 minutes idle, Jarvis transitions
 * toward the Archive form. We expose a millisecond count so callers
 * can drive form transitions based on thresholds.
 */
import { useEffect, useState } from "react";

const ACTIVITY_EVENTS: Array<keyof DocumentEventMap> = [
  "mousemove",
  "keydown",
  "scroll",
  "pointerdown",
];

export function useLongIdle(thresholdMs = 5 * 60 * 1000): {
  idleMs: number;
  isIdle: boolean;
} {
  const [lastActivity, setLastActivity] = useState(() => Date.now());

  useEffect(() => {
    if (typeof document === "undefined") return;
    const bump = () => setLastActivity(Date.now());
    for (const e of ACTIVITY_EVENTS) {
      document.addEventListener(e, bump, { passive: true });
    }
    return () => {
      for (const e of ACTIVITY_EVENTS) {
        document.removeEventListener(e, bump);
      }
    };
  }, []);

  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (typeof window === "undefined") return;
    const id = window.setInterval(() => setNow(Date.now()), 30_000);
    return () => window.clearInterval(id);
  }, []);

  const idleMs = now - lastActivity;
  return { idleMs, isIdle: idleMs >= thresholdMs };
}
