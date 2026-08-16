import { useEffect, useState } from "react";
import { adminApi, type TenantSummary } from "@/lib/api";

export default function Tenants() {
  const [tenants, setTenants] = useState<TenantSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [showCreate, setShowCreate] = useState(false);
  const [newTenantId, setNewTenantId] = useState("");

  useEffect(() => {
    adminApi.getTenants()
      .then((res) => setTenants(res.tenants ?? []))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  const handleCreate = async () => {
    if (!newTenantId.trim()) return;
    try {
      await adminApi.createTenant(newTenantId.trim());
      setNewTenantId("");
      setShowCreate(false);
      const res = await adminApi.getTenants();
      setTenants(res.tenants ?? []);
    } catch (e) {
      alert(`创建失败: ${e}`);
    }
  };

  const handleAction = async (tenantId: string, action: "suspend" | "resume" | "delete") => {
    if (!confirm(`确定要 ${action} 租户 ${tenantId} 吗？`)) return;
    try {
      if (action === "suspend") await adminApi.suspendTenant(tenantId);
      else if (action === "resume") await adminApi.resumeTenant(tenantId);
      else if (action === "delete") await adminApi.deleteTenant(tenantId);
      const res = await adminApi.getTenants();
      setTenants(res.tenants ?? []);
    } catch (e) {
      alert(`操作失败: ${e}`);
    }
  };

  if (loading) return <div className="p-6 text-muted">加载中…</div>;

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">租户管理</h1>
          <p className="mt-1 text-sm text-muted">租户列表、配置、配额、密钥</p>
        </div>
        <button
          onClick={() => setShowCreate(!showCreate)}
          className="rounded-md bg-accent px-3 py-1.5 text-sm font-medium text-white transition-colors hover:bg-accent-foreground"
        >
          {showCreate ? "取消" : "新建租户"}
        </button>
      </div>

      {showCreate && (
        <div className="flex gap-2 rounded-lg border border-border bg-surface p-4">
          <input
            type="text"
            placeholder="租户 ID（如 my-team）"
            value={newTenantId}
            onChange={(e) => setNewTenantId(e.target.value)}
            className="flex-1 rounded-md border border-border bg-background px-3 py-1.5 text-sm"
            onKeyDown={(e) => e.key === "Enter" && handleCreate()}
          />
          <button
            onClick={handleCreate}
            className="rounded-md bg-accent px-4 py-1.5 text-sm font-medium text-white"
          >
            创建
          </button>
        </div>
      )}

      <div className="overflow-hidden rounded-lg border border-border">
        <table className="w-full text-sm">
          <thead className="bg-surface text-xs uppercase tracking-wider text-muted">
            <tr>
              <th className="px-4 py-2.5 text-left">租户 ID</th>
              <th className="px-4 py-2.5 text-left">状态</th>
              <th className="px-4 py-2.5 text-right">会话数</th>
              <th className="px-4 py-2.5 text-left">模型</th>
              <th className="px-4 py-2.5 text-right">最后活跃</th>
              <th className="px-4 py-2.5 text-right">操作</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {tenants.length === 0 ? (
              <tr><td colSpan={6} className="px-4 py-8 text-center text-muted">暂无租户</td></tr>
            ) : tenants.map((t) => (
              <tr key={t.tenantId} className="hover:bg-surface-hover">
                <td className="px-4 py-2.5 font-mono text-xs font-medium">{t.tenantId}</td>
                <td className="px-4 py-2.5">
                  <span className={`rounded-md px-2 py-0.5 text-xs font-medium ${
                    t.status === "active" ? "bg-success/10 text-success"
                    : t.status === "suspended" ? "bg-warning/10 text-warning"
                    : "bg-surface-hover text-muted"
                  }`}>
                    {t.status}
                  </span>
                </td>
                <td className="px-4 py-2.5 text-right tabular-nums">{t.sessionCount}</td>
                <td className="px-4 py-2.5 font-mono text-xs text-muted">{t.modelAlias ?? "-"}</td>
                <td className="px-4 py-2.5 text-right text-xs text-muted">
                  {t.lastActiveAt ? new Date(t.lastActiveAt).toLocaleString() : "-"}
                </td>
                <td className="px-4 py-2.5 text-right">
                  <div className="flex justify-end gap-1">
                    {t.status === "active" ? (
                      <button onClick={() => handleAction(t.tenantId, "suspend")} className="rounded px-2 py-0.5 text-xs text-warning hover:bg-warning/10">暂停</button>
                    ) : (
                      <button onClick={() => handleAction(t.tenantId, "resume")} className="rounded px-2 py-0.5 text-xs text-success hover:bg-success/10">恢复</button>
                    )}
                    <button onClick={() => handleAction(t.tenantId, "delete")} className="rounded px-2 py-0.5 text-xs text-destructive hover:bg-destructive/10">删除</button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
