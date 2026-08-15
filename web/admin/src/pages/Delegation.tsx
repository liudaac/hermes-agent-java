import { useEffect, useState } from "react";
import { adminApi } from "@/lib/api";

export default function Delegation() {
  const [tasks, setTasks] = useState<Array<Record<string, unknown>>>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    adminApi.getControlDelegatedTasks()
      .then((res) => setTasks((res as { delegated_tasks?: Array<Record<string, unknown>> }).delegated_tasks ?? []))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  const handleExecute = async (tenantId: string, taskId: string) => {
    try {
      await adminApi.executeDelegatedTask(tenantId, taskId);
      const res = await adminApi.getControlDelegatedTasks();
      setTasks((res as { delegated_tasks?: Array<Record<string, unknown>> }).delegated_tasks ?? []);
    } catch (e) {
      alert(`执行失败: ${e}`);
    }
  };

  const handleVerify = async (tenantId: string, taskId: string) => {
    try {
      await adminApi.verifyDelegatedTask(tenantId, taskId);
      const res = await adminApi.getControlDelegatedTasks();
      setTasks((res as { delegated_tasks?: Array<Record<string, unknown>> }).delegated_tasks ?? []);
    } catch (e) {
      alert(`验证失败: ${e}`);
    }
  };

  if (loading) return <div className="p-6 text-muted">加载中…</div>;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">委派任务</h1>
        <p className="mt-1 text-sm text-muted">委派任务列表、执行/验证、意图重放</p>
      </div>

      <div className="overflow-hidden rounded-lg border border-border">
        <table className="w-full text-sm">
          <thead className="bg-surface text-xs uppercase tracking-wider text-muted">
            <tr>
              <th className="px-4 py-2.5 text-left">任务</th>
              <th className="px-4 py-2.5 text-left">租户</th>
              <th className="px-4 py-2.5 text-left">状态</th>
              <th className="px-4 py-2.5 text-right">操作</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {tasks.length === 0 ? (
              <tr><td colSpan={4} className="px-4 py-8 text-center text-muted">暂无委派任务</td></tr>
            ) : tasks.map((t, i) => (
              <tr key={i} className="hover:bg-surface-hover">
                <td className="px-4 py-2.5 font-medium">{String(t.description ?? t.taskId ?? `任务 #${i + 1}`)}</td>
                <td className="px-4 py-2.5 font-mono text-xs text-muted">{String(t.tenantId ?? "-")}</td>
                <td className="px-4 py-2.5">
                  <span className="rounded-md bg-surface-hover px-2 py-0.5 text-xs">{String(t.status ?? "pending")}</span>
                </td>
                <td className="px-4 py-2.5 text-right">
                  <div className="flex justify-end gap-1">
                    <button
                      onClick={() => handleExecute(String(t.tenantId), String(t.taskId))}
                      className="rounded px-2 py-0.5 text-xs text-blue-600 hover:bg-blue-50"
                    >
                      执行
                    </button>
                    <button
                      onClick={() => handleVerify(String(t.tenantId), String(t.taskId))}
                      className="rounded px-2 py-0.5 text-xs text-green-600 hover:bg-green-50"
                    >
                      验证
                    </button>
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
