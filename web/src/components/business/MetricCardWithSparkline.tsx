import { cn } from "@/lib/utils";
import { Card, CardContent } from "@/components/ui/card";
import { TrendingDown, TrendingUp, Minus, type LucideIcon } from "lucide-react";

interface SparklinePoint {
  date: string;
  value: number;
}

interface MetricCardWithSparklineProps {
  label: string;
  value: string | number;
  hint?: string;
  trend?: SparklinePoint[];
  delta?: number; // percentage vs previous bucket
  icon?: LucideIcon;
  tone?: "default" | "warning" | "danger" | "success";
}

export default function MetricCardWithSparkline({
  label,
  value,
  hint,
  trend,
  delta,
  icon: Icon,
  tone = "default",
}: MetricCardWithSparklineProps) {
  const colors = {
    default: { fg: "text-foreground", line: "stroke-foreground/60" },
    warning: { fg: "text-amber-500", line: "stroke-amber-500/70" },
    danger: { fg: "text-rose-500", line: "stroke-rose-500/70" },
    success: { fg: "text-emerald-500", line: "stroke-emerald-500/70" },
  }[tone];

  return (
    <Card>
      <CardContent className="p-4">
        <div className="flex items-start justify-between gap-2">
          <div className="min-w-0">
            <div className="flex items-center gap-1.5 text-[0.65rem] uppercase tracking-wider text-muted-foreground">
              {Icon && <Icon className="h-3 w-3" />}
              <span className="truncate">{label}</span>
            </div>
            <div className={cn("mt-1 font-mono text-2xl font-semibold tabular-nums", colors.fg)}>
              {value}
            </div>
            {(hint || typeof delta === "number") && (
              <div className="mt-0.5 flex items-center gap-1.5 text-[0.7rem] text-muted-foreground">
                {typeof delta === "number" && <DeltaBadge delta={delta} />}
                {hint && <span className="truncate">{hint}</span>}
              </div>
            )}
          </div>
          {trend && trend.length > 1 && (
            <Sparkline points={trend} className={cn("shrink-0", colors.line)} />
          )}
        </div>
      </CardContent>
    </Card>
  );
}

function DeltaBadge({ delta }: { delta: number }) {
  const positive = delta > 0;
  const neutral = Math.abs(delta) < 0.5;
  const Icon = neutral ? Minus : positive ? TrendingUp : TrendingDown;
  const color = neutral
    ? "text-muted-foreground"
    : positive
    ? "text-emerald-500"
    : "text-rose-500";
  return (
    <span className={cn("inline-flex items-center gap-0.5 font-mono", color)}>
      <Icon className="h-3 w-3" />
      {positive && !neutral && "+"}
      {delta.toFixed(1)}%
    </span>
  );
}

function Sparkline({
  points,
  className,
  width = 80,
  height = 30,
}: {
  points: SparklinePoint[];
  className?: string;
  width?: number;
  height?: number;
}) {
  const values = points.map((p) => p.value);
  const max = Math.max(1, ...values);
  const min = Math.min(0, ...values);
  const range = Math.max(1, max - min);
  const step = width / Math.max(1, points.length - 1);

  const path = points
    .map((p, i) => {
      const x = i * step;
      const y = height - ((p.value - min) / range) * height;
      return `${i === 0 ? "M" : "L"} ${x.toFixed(2)} ${y.toFixed(2)}`;
    })
    .join(" ");

  const last = points[points.length - 1];
  const lastX = (points.length - 1) * step;
  const lastY = height - ((last.value - min) / range) * height;

  return (
    <svg width={width} height={height} className={cn("overflow-visible", className)}>
      <path d={path} fill="none" strokeWidth={1.5} className={className} />
      <circle cx={lastX} cy={lastY} r={2.2} className="fill-current" />
    </svg>
  );
}
