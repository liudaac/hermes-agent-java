import { Layers } from "lucide-react";

export default function Tenants() {
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">租户管理</h1>
        <p className="mt-1 text-sm text-muted">租户列表、配置、配额、密钥</p>
      </div>
      <div className="flex h-64 items-center justify-center rounded-lg border border-dashed border-border bg-surface/50">
        <div className="flex flex-col items-center gap-2 text-muted">
          <Layers className="h-8 w-8 opacity-40" />
          <span className="text-xs">页面建设中</span>
        </div>
      </div>
    </div>
  );
}
