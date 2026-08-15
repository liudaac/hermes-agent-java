import { useEffect, useState } from "react";
import { adminApi } from "@/lib/api";

export default function Models() {
  const [providers, setProviders] = useState<Array<{ id: string; name: string; baseUrl: string }>>([]);
  const [platformRoutes, setPlatformRoutes] = useState<Array<{ alias: string; model: string; provider: string }>>([]);
  const [modelInfo, setModelInfo] = useState<{ model: string; provider: string; contextWindow: number; maxTokens: number } | null>(null);
  const [loading, setLoading] = useState(true);
  const [tab, setTab] = useState<"routes" | "keys" | "info">("routes");

  useEffect(() => {
    Promise.all([
      adminApi.getAdminProviders().catch(() => ({ providers: [] })),
      adminApi.getAdminPlatformRoutes().catch(() => ({ routes: [] })),
      adminApi.getModelInfo().catch(() => null),
    ]).then(([p, r, m]) => {
      setProviders(p.providers ?? []);
      setPlatformRoutes(r.routes ?? []);
      setModelInfo(m);
      setLoading(false);
    });
  }, []);

  if (loading) return <div className="p-6 text-muted">加载中…</div>;

  const tabs = [
    { key: "routes", label: "模型路由" },
    { key: "keys", label: "API 密钥" },
    { key: "info", label: "当前模型" },
  ] as const;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">模型路由</h1>
        <p className="mt-1 text-sm text-muted">Provider 列表、模型路由、API Key</p>
      </div>

      <div className="flex gap-1 border-b border-border">
        {tabs.map((t) => (
          <button
            key={t.key}
            onClick={() => setTab(t.key)}
            className={`border-b-2 px-4 py-2 text-sm transition-colors ${
              tab === t.key ? "border-accent text-accent-foreground" : "border-transparent text-muted hover:text-foreground"
            }`}
          >
            {t.label}
          </button>
        ))}
      </div>

      {/* Platform routes */}
      {tab === "routes" && (
        <div className="space-y-4">
          <div>
            <h3 className="mb-2 text-sm font-medium text-muted">Provider 列表</h3>
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
              {providers.map((p) => (
                <div key={p.id} className="rounded-lg border border-border bg-surface p-4">
                  <div className="font-medium">{p.name}</div>
                  <div className="mt-1 font-mono text-xs text-muted">{p.baseUrl}</div>
                </div>
              ))}
              {providers.length === 0 && <p className="text-sm text-muted">暂无 Provider</p>}
            </div>
          </div>
          <div>
            <h3 className="mb-2 text-sm font-medium text-muted">平台路由</h3>
            <div className="overflow-hidden rounded-lg border border-border">
              <table className="w-full text-sm">
                <thead className="bg-surface text-xs uppercase tracking-wider text-muted">
                  <tr>
                    <th className="px-4 py-2.5 text-left">别名</th>
                    <th className="px-4 py-2.5 text-left">模型</th>
                    <th className="px-4 py-2.5 text-left">Provider</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border">
                  {platformRoutes.length === 0 ? (
                    <tr><td colSpan={3} className="px-4 py-8 text-center text-muted">暂无路由</td></tr>
                  ) : platformRoutes.map((r) => (
                    <tr key={r.alias} className="hover:bg-surface-hover">
                      <td className="px-4 py-2.5 font-mono text-xs font-medium">{r.alias}</td>
                      <td className="px-4 py-2.5 font-mono text-xs">{r.model}</td>
                      <td className="px-4 py-2.5">{r.provider}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      )}

      {/* API Keys */}
      {tab === "keys" && (
        <div className="space-y-3">
          <p className="text-sm text-muted">租户级 API Key 管理请在租户管理页面操作。</p>
          {providers.map((p) => (
            <div key={p.id} className="flex items-center justify-between rounded-lg border border-border bg-surface p-4">
              <div>
                <div className="font-medium">{p.name}</div>
                <div className="mt-0.5 font-mono text-xs text-muted">{p.id}</div>
              </div>
              <span className="rounded-md bg-surface-hover px-2.5 py-1 text-xs text-muted">
                需租户级配置
              </span>
            </div>
          ))}
        </div>
      )}

      {/* Current model info */}
      {tab === "info" && modelInfo && (
        <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
          <div className="rounded-lg border border-border bg-surface p-4">
            <div className="text-xs text-muted">模型</div>
            <div className="mt-1 font-mono text-sm font-medium">{modelInfo.model}</div>
          </div>
          <div className="rounded-lg border border-border bg-surface p-4">
            <div className="text-xs text-muted">Provider</div>
            <div className="mt-1 font-mono text-sm font-medium">{modelInfo.provider}</div>
          </div>
          <div className="rounded-lg border border-border bg-surface p-4">
            <div className="text-xs text-muted">上下文窗口</div>
            <div className="mt-1 text-lg font-bold tabular-nums">{modelInfo.contextWindow.toLocaleString()}</div>
          </div>
          <div className="rounded-lg border border-border bg-surface p-4">
            <div className="text-xs text-muted">最大 Token</div>
            <div className="mt-1 text-lg font-bold tabular-nums">{modelInfo.maxTokens.toLocaleString()}</div>
          </div>
        </div>
      )}
    </div>
  );
}
