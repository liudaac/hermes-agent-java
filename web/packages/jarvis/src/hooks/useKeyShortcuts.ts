/**
 * useKeyShortcuts — keyboard bindings (design.md §14).
 *
 *   ⌘/Ctrl + K    →  summon (toggle overlay)
 *   ⌘/Ctrl + .    →  minimize (set hidden)
 *   Esc           →  close (set hidden)
 *   Enter         →  (handled by InputBar)
 *   Shift+Enter   →  (handled by InputBar)
 */
import { useEffect } from "react";
import { useJarvisStore, setOverlay } from "./useJarvisStore";

export function useKeyShortcuts(opts?: { onSend?: () => void }): void {
  const overlay = useJarvisStore((s) => s.overlay);

  useEffect(() => {
    if (typeof window === "undefined") return;
    const onKey = (e: KeyboardEvent) => {
      const cmd = e.metaKey || e.ctrlKey;
      if (cmd && e.key.toLowerCase() === "k") {
        e.preventDefault();
        setOverlay(overlay === "summoned" ? "hidden" : "summoned");
        return;
      }
      if (cmd && e.key === ".") {
        e.preventDefault();
        setOverlay("hidden");
        return;
      }
      if (e.key === "Escape" && overlay !== "hidden") {
        e.preventDefault();
        setOverlay("hidden");
        return;
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [overlay]);

  void opts;
}
