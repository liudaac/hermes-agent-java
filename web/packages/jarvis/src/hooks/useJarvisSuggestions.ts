/**
 * useJarvisSuggestions — subscribe to the Jarvis SSE suggestion stream
 * and route incoming proactive alerts into the zustand store.
 *
 * Behavior:
 *   - Auto-opens the stream as soon as the SPA mounts, gated by
 *     `enabled` state.
 *   - Admin SPAs (ops / noc) pass all=true to see cross-workspace events.
 *   - Business SPA (portal) passes workspaceId to scope to its workspace.
 *   - When a suggestion arrives, pushes it into store.suggestions.
 *     Critical/warning severity pulses the orb to alert form
 *     (sets form="vigilant" or "pulse" briefly, then returns).
 *   - On error, backs off exponentially; uses the token auto-refresh
 *     built into jarvisApi.openSuggestionStream.
 */

import { useEffect, useRef } from "react";
import { jarvisApi } from "../api/jarvisApi";
import {
  useJarvisStore,
  pushSuggestion,
  markAllSuggestionsRead,
  setForm,
} from "./useJarvisStore";
import { broadcastForm } from "./useCrossSpaceSync";
import { useContextAwareness } from "./useContextAwareness";

export function useJarvisSuggestions() {
  const enabled = useJarvisStore((s) => s.enabled);
  const overlay = useJarvisStore((s) => s.overlay);
  const awareness = useContextAwareness();

  // Remember the previous form so we can pulse and restore.
  const prevFormRef = useRef<string | null>(null);
  const pulseTimerRef = useRef<number | null>(null);

  // Mark all as read whenever overlay opens to summoned/fullscreen.
  useEffect(() => {
    if (overlay === "summoned" || overlay === "fullscreen") {
      markAllSuggestionsRead();
    }
  }, [overlay]);

  useEffect(() => {
    if (!enabled) return;

    // Decide stream scope based on SPA:
    //   - ops/noc → admin view (all=true)
    //   - portal  → workspace-scoped
    //   - hub     → no stream (it's just a launcher)
    const space = awareness.space;
    const opts: { workspaceId?: string; all?: boolean } = {};
    if (space === "ops" || space === "noc") {
      opts.all = true;
    } else if (space === "portal" && awareness.workspaceId) {
      opts.workspaceId = awareness.workspaceId;
    } else {
      // hub/unknown: don't open a stream
      return;
    }

    const es = jarvisApi.openSuggestionStream((s) => {
      // 1. Push to store (unread).
      pushSuggestion({ ...s, read: false });

      // 2. Orb pulse: critical → pulse (red pulse); warning → vigilant (amber).
      // Skip if panel is open (user is already looking at Jarvis).
      const store = useJarvisStore.getState();
      if (store.overlay === "hidden") {
        if (pulseTimerRef.current) {
          window.clearTimeout(pulseTimerRef.current);
          pulseTimerRef.current = null;
        }
        prevFormRef.current = store.form;
        if (s.severity === "critical") {
          setForm("pulse");
          broadcastForm("pulse");
        } else if (s.severity === "warning") {
          setForm("vigilant");
          broadcastForm("vigilant");
        }
        // Revert back to previous form after ~2.4s.
        const revertTo = prevFormRef.current ?? "core";
        pulseTimerRef.current = window.setTimeout(() => {
          setForm(revertTo as "core" | "sphere" | "helix" | "cascade" | "pulse" | "net" | "dawn" | "archive" | "vigilant" | "reflective");
          broadcastForm(revertTo as "core" | "sphere" | "helix" | "cascade" | "pulse" | "net" | "dawn" | "archive" | "vigilant" | "reflective");
          pulseTimerRef.current = null;
        }, 2400);
      }
    }, opts);

    return () => {
      if (pulseTimerRef.current) {
        window.clearTimeout(pulseTimerRef.current);
        pulseTimerRef.current = null;
      }
      es.close();
    };
  }, [enabled, awareness.space, awareness.workspaceId]);
}
