import { Shield } from "lucide-react";

export default function ApprovalPolicy() {
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">审批策略</h1>
        <p className="mt-1 text-sm text-muted">审批模式配置、风险规则、通知通道</p>
      </div>
      <div className="flex h-64 items-center justify-center rounded-lg border border-dashed border-border bg-surface/50">
        <div className="flex flex-col items-center gap-2 text-muted">
          <Shield className="h-8 w-8 opacity-40" />
          <span className="text-xs">页面建设中</span>
        </div>
      </div>
    </div>
  );
}
