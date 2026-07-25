/**
 * useWorkspace - shared hook for workspace ID resolution + caching.
 *
 * Avoids repeated calls to getBusinessHome() across pages.
 * Caches the workspaceId in localStorage for subsequent loads.
 */
import { useEffect, useState } from "react";
import { portalApi } from "@/api/portal";

const CACHE_KEY = "hermes:workspaceId";

function loadCached(): string | null {
  try {
    return localStorage.getItem(CACHE_KEY);
  } catch {
    return null;
  }
}

function cacheWorkspaceId(id: string) {
  try {
    localStorage.setItem(CACHE_KEY, id);
  } catch {}
}

export function useWorkspace() {
  const [workspaceId, setWorkspaceId] = useState<string | null>(loadCached());
  const [loading, setLoading] = useState(!workspaceId);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (workspaceId) {
      // Have cached value, but verify in background
      portalApi
        .getBusinessHome()
        .then((home) => {
          const ws = home.workspaceId ?? home.workspaces?.[0]?.workspaceId;
          if (ws && ws !== workspaceId) {
            setWorkspaceId(ws);
            cacheWorkspaceId(ws);
          }
        })
        .catch(() => {});
      return;
    }

    let alive = true;
    setLoading(true);
    portalApi
      .getBusinessHome()
      .then((home) => {
        if (!alive) return;
        const ws = home.workspaceId ?? home.workspaces?.[0]?.workspaceId;
        if (ws) {
          setWorkspaceId(ws);
          cacheWorkspaceId(ws);
        } else {
          setError("找不到工作区");
        }
      })
      .catch((e) => {
        if (alive) setError(String(e?.message ?? e));
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, []);

  return { workspaceId, loading, error };
}
