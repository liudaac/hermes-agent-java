export default function ApiBrowser() {
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-bold tracking-tight text-foreground">API 浏览器</h1>
        <p className="mt-1 text-sm text-muted">225 个端点文档 + Try it</p>
      </div>
      <div className="flex h-64 items-center justify-center rounded-lg border border-dashed border-border bg-surface/50">
        <div className="flex flex-col items-center gap-2 text-muted">
          <span className="font-mono text-2xl">{"</>"}</span>
          <span className="text-xs font-mono">页面建设中</span>
        </div>
      </div>
    </div>
  );
}
