/**
 * useJarvisChat — wire JarvisOverlay to the real backend
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
