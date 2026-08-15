import { useEffect, useState } from "react";
import { threeLayerApi, type SpacePolicy } from "@/lib/api";

const SPACE = "default";

export default function ApprovalPolicy() {
  const [policy, setPolicy] = useState<SpacePolicy | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    threeLayerApi.getSpacePolicy(SPACE)
      .then((res) => setPolicy(res.policy ?? null))
      .catch(() => null)
      .finally(() => setLoading(false));
  }, []);

  if (loading) return <div className="p-6 text-muted">加载中…</div>;

  const approvalTypes = [
    { key: "terminal", label: "终端命令", desc: "Shell 执行审批" },
    { key: "file_write", label: "文件写入", desc: "文件创建/修改审批" },
    { key: "file_delete", label: "文件删除", desc: "文件删除审批" },
    { key: "code", label: "代码执行", desc: "代码运行审批" },
    { key: "browser", label: "浏览器操作", desc: "浏览器动作审批" },
    { key: "subagent", label: "子 Agent", desc: "子 Agent 创建审批" },
    { key: "skill_install", label: "技能安装", desc: "技能安装审批" },
  ];

  const modes = [
    { value: "AUTO", label: "自动", color: "bg-green-50 text-green-700" },
    { value: "PROMPT", label: "询问", color: "bg-blue-50 text-blue-700" },
    { value: "REQUIRE", label: "必须", color: "bg-amber-50 text-amber-700" },
    { value: "DENY", label: "禁止", color: "bg-red-50 text-red-700" },
  ];

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">审批策略</h1>
        <p className="mt-1 text-sm text-muted">审批模式配置、风险规则、通知通道</p>
      </div>

      {/* Approval types grid */}
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {approvalTypes.map((t) => {
          const currentMode = policy?.approvalModes?.[t.key] ?? "AUTO";
          return (
            <div key={t.key} className="rounded-lg border border-border bg-surface p-4">
              <div className="flex items-center justify-between">
                <div>
                  <div className="font-medium">{t.label}</div>
                  <div className="mt-0.5 text-xs text-muted">{t.desc}</div>
                </div>
                <span className={`rounded-md px-2 py-0.5 text-xs font-medium ${
                  modes.find((m) => m.value === currentMode)?.color ?? "bg-gray-50 text-gray-600"
                }`}>
                  {modes.find((m) => m.value === currentMode)?.label ?? currentMode}
                </span>
              </div>
            </div>
          );
        })}
      </div>

      {/* Sandbox & security */}
      <div className="rounded-lg border border-border bg-surface p-4">
        <h3 className="mb-3 text-sm font-medium text-foreground">安全策略</h3>
        <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
          <div>
            <div className="text-xs text-muted">沙箱隔离</div>
            <div className={`mt-1 text-sm font-medium ${policy?.sandboxEnforced ? "text-green-600" : "text-muted"}`}>
              {policy?.sandboxEnforced ? "启用" : "关闭"}
            </div>
          </div>
          <div>
            <div className="text-xs text-muted">衰减策略</div>
            <div className="mt-1 text-sm font-medium">{policy?.decayPolicy ?? "standard"}</div>
          </div>
          <div>
            <div className="text-xs text-muted">最大并发运行</div>
            <div className="mt-1 text-sm font-medium tabular-nums">{policy?.maxConcurrentRuns ?? 5}</div>
          </div>
          <div>
            <div className="text-xs text-muted">用户覆盖</div>
            <div className={`mt-1 text-sm font-medium ${policy?.allowUserOverride ? "text-green-600" : "text-muted"}`}>
              {policy?.allowUserOverride ? "允许" : "禁止"}
            </div>
          </div>
        </div>
      </div>

      {/* Notification channels */}
      <div className="rounded-lg border border-border bg-surface p-4">
        <h3 className="mb-3 text-sm font-medium text-foreground">通知通道</h3>
        <div className="flex flex-wrap gap-2">
          {["EMAIL", "WEBHOOK", "飞书", "钉钉"].map((ch) => (
            <span key={ch} className="rounded-md bg-surface-hover px-3 py-1 text-xs text-muted">
              {ch}
            </span>
          ))}
        </div>
        <p className="mt-2 text-xs text-muted">
          高风险审批可通过 4 种通道推送通知，在 BusinessApprovalNotifier 中配置。
        </p>
      </div>
    </div>
  );
}
