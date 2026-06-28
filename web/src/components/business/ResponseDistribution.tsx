import { cn } from "@/lib/utils";
import { Card, CardContent } from "@/components/ui/card";
import { Activity } from "lucide-react";
import type { BusinessHomeResponseDistribution } from "@/lib/api";

interface ResponseDistributionProps {
  data?: BusinessHomeResponseDistribution;
  className?: string;
}

export default function ResponseDistribution({ data, className }: ResponseDistributionProps) {
  if (!data || data.sampleSize === 0) {
    return (
      <Card className={className}>
        <CardContent className="p-4">
          <Header />
          <p className="mt-3 text-xs text-muted-foreground">还没有响应时长样本</p>
        </CardContent>
      </Card>
    );
  }

  const maxCount = Math.max(1, ...data.buckets.map((b) => b.count));

  return (
    <Card className={className}>
      <CardContent className="p-4">
        <Header sampleSize={data.sampleSize} />
        <div className="mt-3 flex items-end gap-0.5 h-20">
          {data.buckets.map((b) => {
            const h = (b.count / maxCount) * 100;
            return (
              <div
                key={b.index}
                className="flex-1 group relative flex items-end"
                title={`${formatMs(b.fromMs)} – ${formatMs(b.toMs)} · ${b.count} runs`}
              >
                <div
                  className={cn(
                    "w-full rounded-sm transition-all",
                    b.count > 0 ? "bg-sky-500/60 group-hover:bg-sky-500" : "bg-muted/40",
                  )}
                  style={{ height: `${Math.max(2, h)}%` }}
                />
              </div>
            );
          })}
        </div>
        <div className="mt-3 grid grid-cols-2 gap-2 text-xs">
          <Stat label="P50" value={formatMs(data.p50Ms)} tone="sky" />
          <Stat label="P95" value={formatMs(data.p95Ms)} tone="amber" />
        </div>
      </CardContent>
    </Card>
  );
}

function Header({ sampleSize }: { sampleSize?: number }) {
  return (
    <div className="flex items-center justify-between">
      <div className="flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
        <Activity className="h-3.5 w-3.5" />
        响应时长分布
      </div>
      {typeof sampleSize === "number" && (
        <span className="font-mono text-xs text-muted-foreground">
          n = {sampleSize}
        </span>
      )}
    </div>
  );
}

function Stat({ label, value, tone }: { label: string; value: string; tone: "sky" | "amber" }) {
  const toneClass = tone === "sky" ? "text-sky-500" : "text-amber-500";
  return (
    <div className="rounded-md border border-border/60 px-2 py-1.5">
      <div className="text-xs uppercase tracking-wider text-muted-foreground">{label}</div>
      <div className={cn("font-mono text-sm font-semibold", toneClass)}>{value}</div>
    </div>
  );
}

function formatMs(ms: number): string {
  if (ms < 1000) return `${Math.round(ms)}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  return `${(ms / 60_000).toFixed(1)}m`;
}
