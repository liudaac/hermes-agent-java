import { Shield } from "lucide-react";

export default function Compare() {
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">比较分析</h1>
        <p className="mt-1 text-sm text-muted">A/B 对比、模型评测</p>
      </div>
      <div className="rounded-lg border border-border bg-surface p-6">
        <div className="flex items-center gap-3">
          <Shield className="h-5 w-5 text-muted" />
          <div>
            <div className="text-sm font-medium">模型比较工具</div>
            <div className="mt-1 text-sm text-muted">
              详细的 A/B 对比和模型评测工具在 Ops 控制台中。
            </div>
          </div>
        </div>
        <a
          href="/ops/index.html#/compare"
          className="mt-4 inline-flex items-center gap-1.5 rounded-md bg-accent px-3 py-1.5 text-sm font-medium text-white transition-colors hover:bg-accent-foreground"
        >
          前往 Ops Compare
        </a>
      </div>
    </div>
  );
}
