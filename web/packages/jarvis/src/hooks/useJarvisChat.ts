/**
 * useJarvisChat - wire JarvisOverlay to the real backend
 * /api/jarvis/chat endpoint. Submits the user text, waits for the
 * reply, and appends both messages to the store.
 *
 * Returns an onSubmit(text) callback that Overlay / Fullscreen can
 * pass to JarvisCore.
 */
import { useCallback } from "react";
import { jarvisApi } from "../api/jarvisApi";
import {
  useJarvisStore,
  pushMessage,
  setPendingApproval,
  type JarvisMessage,
} from "./useJarvisStore";
import { useContextAwareness } from "./useContextAwareness";

/**
 * Navigate to a cross-space link. The backend returns paths like
 * "/portal/approvals" or "/noc/dlq" - we resolve these to the
 * correct SPA index.html so the browser does a full page load
 * (each SPA is a separate Vite entry / bundle).
 *
 * Path conventions:
 *   /portal/...  -> /portal/index.html#/...
 *   /ops/...     -> /ops/index.html#/...
 *   /noc/...     -> /noc/index.html#/...
 *   /api/...     -> ignore (not a navigation target)
 *   other        -> treat as same-SPA path
 */
export function navigateToSpace(path: string) {
  if (!path || !path.startsWith("/")) return;

  // Cross-space paths: /portal/*, /ops/*, /noc/*
  const spaceMatch = path.match(/^\/(portal|ops|noc)\b(.*)$/);
  if (spaceMatch) {
    const [, space, rest] = spaceMatch;
    // In dev, each SPA has its own port. In prod, they're at
    // /portal/index.html etc. We use the production path convention;
    // Vite dev proxy handles /portal/* -> :5175 transparently.
    const target = `/${space}/index.html${rest || ""}`;
    window.location.href = target;
    return;
  }

  // Same-SPA path (e.g. "/approvals" in portal).
  // Use hash routing so the SPA router picks it up without reload.
  if (!path.startsWith("/api/")) {
    window.location.hash = path;
  }
}

export function useJarvisChat() {
  const awareness = useContextAwareness();

  const onSubmit = useCallback(async (text: string) => {
    if (!text || !text.trim()) return;

    // Push user msg immediately (optimistic UI).
    const userMsg: JarvisMessage = {
      id: crypto.randomUUID(),
      role: "user",
      text: text.trim(),
      timestamp: Date.now(),
    };
    pushMessage(userMsg);

    // Push a placeholder jarvis msg so the user sees "thinking".
    const placeholderId = crypto.randomUUID();
    pushMessage({
      id: placeholderId,
      role: "jarvis",
      text: "…",
      timestamp: Date.now(),
      pending: true,
    });

    try {
      const context = {
        space: (awareness.space === "unknown" || awareness.space === "hub"
          ? undefined
          : awareness.space) as "portal" | "ops" | "noc" | undefined,
        workspaceId: awareness.workspaceId,
        activeResource: awareness.activeResource
          ? {
              kind: awareness.activeResource.kind,
              id: awareness.activeResource.id,
              label: awareness.activeResource.label,
            }
          : undefined,
      };
      const resp = await jarvisApi.chat({ message: text.trim(), context });

      // Replace the placeholder with the real reply.
      const jarvisMsg: JarvisMessage = {
        id: crypto.randomUUID(),
        role: "jarvis",
        text: resp.reply ?? "(空回复)",
        timestamp: Date.now(),
      };
      // Remove placeholder, add real.
      const s = useJarvisStore.getState();
      const next = s.messages.filter((m) => m.id !== placeholderId);
      next.push(jarvisMsg);
      useJarvisStore.setState({ messages: next.slice(-50) });

      // If there's a pending approval, surface it via the store.
      if (resp.approval) {
        setPendingApproval(resp.approval);
      }

      // If the reply includes a cross-space link, auto-navigate
      // after a short delay so the user can read the reply first.
      if (resp.crossSpaceLink && resp.crossSpaceLink.to) {
        const target = resp.crossSpaceLink.to;
        const label = resp.crossSpaceLink.label ?? "";
        // Append a subtle "navigating..." hint to the reply.
        useJarvisStore.setState({
          messages: useJarvisStore.getState().messages.map((m) =>
            m.id === jarvisMsg.id
              ? { ...m, text: m.text + `\n\n→ 正在跳转：${label || target}` }
              : m,
          ),
        });
        // Navigate after 1.5s so the user sees the reply + hint.
        setTimeout(() => navigateToSpace(target), 1500);
      }
    } catch (e) {
      const err = e instanceof Error ? e.message : String(e);
      const s = useJarvisStore.getState();
      const next = s.messages.filter((m) => m.id !== placeholderId);
      next.push({
        id: crypto.randomUUID(),
        role: "jarvis",
        text: "（连接失败：" + err + "）",
        timestamp: Date.now(),
        error: true,
      });
      useJarvisStore.setState({ messages: next });
    }
  }, [awareness.space, awareness.workspaceId, awareness.activeResource]);

  return { onSubmit };
}
