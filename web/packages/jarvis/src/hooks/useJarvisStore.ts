/**
 * useJarvisStore — global Zustand store for Jarvis state.
 *
 * Per design.md §2.6.3 (Behavior decisions) and §11 (cross-space
 * consistency), this is the *single source of truth* for:
 *   - current form (primary + sub)
 *   - overlay mode (hidden / summoned / fullscreen)
 *   - conversation history (latest N turns)
 *   - cross-space context (which SPA the user is in)
 *   - approval gate state
 *
 * Three SPAs (portal/ops/noc) each have their own React tree, so
 * the store is replicated per SPA. Synchronization across tabs /
 * across SPAs happens via localStorage + window-level
 * `jarvis:broadcast` event (see `useCrossSpaceSync`).
 */

import create from "zustand";
import type { FormName, SubFormName } from "../core/JarvisFSM";

export type OverlayMode = "hidden" | "summoned" | "fullscreen";

export interface JarvisMessage {
  id: string;
  role: "user" | "jarvis" | "tool" | "approval";
  text: string;
  timestamp: number;
  meta?: Record<string, unknown>;
}

/** Public state shape (no setters). Setters live as standalone exports. */
export interface JarvisState {
  form: FormName;
  sub?: SubFormName;
  overlay: OverlayMode;
  enabled: boolean;
  messages: JarvisMessage[];
  pendingApproval?: {
    approvalId: string;
    title: string;
    risk: "low" | "medium" | "high";
  };
}

const STORAGE_KEY = "hermes-jarvis-state";

function loadPersisted(): JarvisState {
  if (typeof window === "undefined") {
    return { form: "core", overlay: "hidden", enabled: true, messages: [] };
  }
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) {
      return { form: "core", overlay: "hidden", enabled: true, messages: [] };
    }
    const parsed = JSON.parse(raw) as Partial<JarvisState>;
    return {
      form: parsed.form ?? "core",
      sub: parsed.sub,
      overlay: parsed.overlay ?? "hidden",
      enabled: parsed.enabled ?? true,
      messages: [],
    };
  } catch {
    return { form: "core", overlay: "hidden", enabled: true, messages: [] };
  }
}

function persist(state: JarvisState): void {
  if (typeof window === "undefined") return;
  try {
    window.localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({
        form: state.form,
        sub: state.sub,
        overlay: state.overlay,
        enabled: state.enabled,
      }),
    );
  } catch {
    // ignore quota errors
  }
}

export const useJarvisStore = create<JarvisState>(() => ({
  ...loadPersisted(),
  messages: [],
}));

// Standalone setters (zustand 3.x doesn't compose setters into the
// store's state type, so we keep them as plain functions that use
// `useJarvisStore.setState` directly).
export const setForm = (form: FormName, sub?: SubFormName) => {
  const s = useJarvisStore.getState();
  const next: JarvisState = { ...s, form };
  if (sub !== undefined) next.sub = sub;
  else delete next.sub;
  persist(next);
  useJarvisStore.setState(next);
};
export const setOverlay = (overlay: JarvisState["overlay"]) => {
  const s = useJarvisStore.getState();
  const next: JarvisState = { ...s, overlay };
  persist(next);
  useJarvisStore.setState(next);
};
export const setEnabled = (enabled: boolean) => {
  const s = useJarvisStore.getState();
  const next: JarvisState = { ...s, enabled };
  persist(next);
  useJarvisStore.setState(next);
};
export const pushMessage = (msg: JarvisMessage) => {
  const s = useJarvisStore.getState();
  useJarvisStore.setState({ messages: [...s.messages.slice(-49), msg] });
};
export const clearMessages = () => useJarvisStore.setState({ messages: [] });
export const setPendingApproval = (a: JarvisState["pendingApproval"]) => {
  useJarvisStore.setState({ pendingApproval: a });
};
