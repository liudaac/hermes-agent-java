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
    default: { fg: "text-foreground", line: "stroke-foreground/60", fill: "fill-foreground/10" },
    warning: { fg: "text-amber-500", line: "stroke-amber-500/80", fill: "fill-amber-500/15" },
    danger: { fg: "text-rose-500", line: "stroke-rose-500/80", fill: "fill-rose-500/15" },
    success: { fg: "text-emerald-500", line: "stroke-emerald-500/80", fill: "fill-emerald-500/15" },
  }[tone];

  return (
    <Card className="card-hover relative overflow-hidden">
      <CardContent className="p-4">
        <div className="flex items-start justify-between gap-2">
          <div className="min-w-0">
            <div className="flex items-center gap-1.5 text-xs uppercase tracking-wider text-muted-foreground">
              {Icon && <Icon className="h-3 w-3" />}
              <span className="truncate">{label}</span>
            </div>
            <div className={cn("metric-number mt-1 text-2xl font-semibold sm:text-3xl", colors.fg)}>
              {value}
            </div>
            {(hint || typeof delta === "number") && (
              <div className="mt-0.5 flex items-center gap-1.5 text-xs text-muted-foreground">
                {typeof delta === "number" && <DeltaBadge delta={delta} />}
                {hint && <span className="truncate">{hint}</span>}
              </div>
            )}
          </div>
          {trend && trend.length > 1 && (
            <Sparkline points={trend} lineClass={colors.line} fillClass={colors.fill} />
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
  lineClass,
  fillClass,
  width = 88,
  height = 32,
}: {
  points: SparklinePoint[];
  lineClass?: string;
  fillClass?: string;
  width?: number;
  height?: number;
}) {
  const values = points.map((p) => p.value);
  const max = Math.max(1, ...values);
  const min = Math.min(0, ...values);
  const range = Math.max(1, max - min);
  const step = width / Math.max(1, points.length - 1);

  const linePath = points
    .map((p, i) => {
      const x = i * step;
      const y = height - ((p.value - min) / range) * height;
      return `${i === 0 ? "M" : "L"} ${x.toFixed(2)} ${y.toFixed(2)}`;
    })
    .join(" ");

  // Closed area for fill
  const last = points[points.length - 1];
  const lastX = (points.length - 1) * step;
  const lastY = height - ((last.value - min) / range) * height;
  const areaPath = `${linePath} L ${lastX.toFixed(2)} ${height} L 0 ${height} Z`;

  return (
    <svg width={width} height={height} className="shrink-0 overflow-visible">
      <defs>
        <linearGradient id="spark-fade" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="currentColor" stopOpacity="0.6" />
          <stop offset="100%" stopColor="currentColor" stopOpacity="0" />
        </linearGradient>
      </defs>
      <path d={areaPath} className={cn(fillClass)} />
      <path d={linePath} fill="none" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round" className={lineClass} />
      <circle cx={lastX} cy={lastY} r={2.4} className={cn("fill-current", lineClass)} />
      <circle cx={lastX} cy={lastY} r={5} className={cn("fill-current", lineClass)} opacity={0.2}>
        <animate attributeName="r" values="3;6;3" dur="2.2s" repeatCount="indefinite" />
        <animate attributeName="opacity" values="0.35;0;0.35" dur="2.2s" repeatCount="indefinite" />
      </circle>
    </svg>
  );
}
