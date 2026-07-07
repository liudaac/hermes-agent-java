/**
 * useCrossSpaceSync — broadcast Jarvis state across tabs and SPAs.
 *
 * Implementation: BroadcastChannel + storage event fallback.
 *
 * When Portal updates the active workspace, NOC / Ops also need to
 * know. When Jarvis changes form, the change is visible in the
 * bottom-right corner of whichever SPA the user is in.
 *
 * We do not sync the conversation history across SPAs (per design.md
 * §11.1 "短期 sessionStorage" — each tab has its own scrollback).
 */
import { useEffect } from "react";
import { setForm, setOverlay } from "./useJarvisStore";
import type { FormName, SubFormName } from "../core/JarvisFSM";
import type { JarvisState } from "./useJarvisStore";

const CHANNEL = "hermes-jarvis-broadcast";
const IGNORE_KEY = "__jarvis_sync_ignore__";

export function useCrossSpaceSync(): void {
  useEffect(() => {
    if (typeof window === "undefined") return;
    const bc = "BroadcastChannel" in window ? new BroadcastChannel(CHANNEL) : null;

    const onMessage = (e: MessageEvent) => {
      const data = e.data as { type: string; payload?: unknown } | undefined;
      if (!data) return;
      if (data.type === "form") {
        const { form, sub } = data.payload as { form: FormName; sub?: SubFormName };
        setForm(form, sub);
      } else if (data.type === "overlay") {
        const mode = data.payload as JarvisState["overlay"];
        setOverlay(mode);
      }
    };

    bc?.addEventListener("message", onMessage);

    // Storage-event fallback (older browsers).
    const onStorage = (e: StorageEvent) => {
      if (e.key !== `${CHANNEL}:form`) return;
      if (e.newValue) {
        try {
          const parsed = JSON.parse(e.newValue);
          (window as Window & { [IGNORE_KEY]?: boolean })[IGNORE_KEY] = true;
          setForm(parsed.form, parsed.sub);
        } catch {
          // ignore
        }
      }
    };
    window.addEventListener("storage", onStorage);

    return () => {
      bc?.removeEventListener("message", onMessage);
      bc?.close();
      window.removeEventListener("storage", onStorage);
    };
  }, []);
}

/** Broadcast a form change to other tabs. */
export function broadcastForm(form: string, sub?: string): void {
  if (typeof window === "undefined") return;
  const bc = "BroadcastChannel" in window ? new BroadcastChannel(CHANNEL) : null;
  bc?.postMessage({ type: "form", payload: { form, sub } });
  try {
    window.localStorage.setItem(
      `${CHANNEL}:form`,
      JSON.stringify({ form, sub, t: Date.now() }),
    );
  } catch {
    // ignore
  }
}
