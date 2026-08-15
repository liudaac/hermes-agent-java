import { useEffect, useState } from "react";
import { threeLayerApi, type UserProfile } from "@/lib/api";

export default function Users() {
  const [users, setUsers] = useState<UserProfile[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    threeLayerApi.getOrgUsers()
      .then((res) => setUsers(res.users ?? []))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  if (loading) return <div className="p-6 text-muted">加载中…</div>;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">用户管理</h1>
        <p className="mt-1 text-sm text-muted">用户列表、能力、偏好、画像</p>
      </div>

      <div className="overflow-hidden rounded-lg border border-border">
        <table className="w-full text-sm">
          <thead className="bg-surface text-xs uppercase tracking-wider text-muted">
            <tr>
              <th className="px-4 py-2.5 text-left">姓名</th>
              <th className="px-4 py-2.5 text-left">ID</th>
              <th className="px-4 py-2.5 text-left">邮箱</th>
              <th className="px-4 py-2.5 text-right">空间数</th>
              <th className="px-4 py-2.5 text-right">技能数</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {users.length === 0 ? (
              <tr><td colSpan={5} className="px-4 py-8 text-center text-muted">暂无用户</td></tr>
            ) : users.map((u) => (
              <tr key={u.userId} className="cursor-pointer hover:bg-surface-hover" onClick={() => setSelected(selected === u.userId ? null : u.userId)}>
                <td className="px-4 py-2.5 font-medium">{u.displayName}</td>
                <td className="px-4 py-2.5 font-mono text-xs text-muted">{u.userId}</td>
                <td className="px-4 py-2.5 text-muted">{u.email ?? "-"}</td>
                <td className="px-4 py-2.5 text-right tabular-nums">{u.spaces?.length ?? 0}</td>
                <td className="px-4 py-2.5 text-right tabular-nums">{u.capabilities?.personalSkills?.length ?? 0}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {selected && users.find((u) => u.userId === selected) && (
        <div className="rounded-lg border border-border bg-surface p-4">
          <h3 className="mb-3 text-sm font-medium text-foreground">
            {users.find((u) => u.userId === selected)?.displayName} 的详情
          </h3>
          {(() => {
            const u = users.find((u) => u.userId === selected)!;
            return (
              <div className="grid grid-cols-2 gap-4 text-sm">
                <div>
                  <div className="text-xs text-muted">空间成员</div>
                  <div className="mt-1 space-y-1">
                    {u.spaces?.map((s) => (
                      <div key={s.spaceId} className="flex items-center gap-2">
                        <span>{s.spaceName}</span>
                        <span className="rounded bg-surface-hover px-1.5 py-0.5 text-xs text-muted">{s.role}</span>
                      </div>
                    )) ?? <span className="text-muted">-</span>}
                  </div>
                </div>
                <div>
                  <div className="text-xs text-muted">偏好</div>
                  <div className="mt-1 space-y-1">
                    <div>语言: {u.preferences?.language ?? "-"}</div>
                    <div>风格: {u.preferences?.responseStyle ?? "-"}</div>
                    <div>自动审批: {u.preferences?.autoApproveSafe ? "是" : "否"}</div>
                  </div>
                </div>
              </div>
            );
          })()}
        </div>
      )}
    </div>
  );
}
