import { GitBranch } from "lucide-react";

export default function Evolution() {
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">进化中心</h1>
        <p className="mt-1 text-sm text-muted">进化提案、失败模式、改进信号</p>
      </div>
      <div className="flex h-64 items-center justify-center rounded-lg border border-dashed border-border bg-surface/50">
        <div className="flex flex-col items-center gap-2 text-muted">
          <GitBranch className="h-8 w-8 opacity-40" />
          <span className="text-xs">页面建设中</span>
        </div>
      </div>
    </div>
  );
}
