import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  ShieldAlert,
  ArrowLeft,
  RefreshCw,
  AlertTriangle,
  ShieldCheck,
} from "lucide-react";
import { api, type BusinessRiskPolicySummary } from "@/lib/api";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import { useToast } from "@/hooks/useToast";
import { categoryLabel } from "@/components/business/templateVisuals";

export default function RiskPolicyPage() {
  const navigate = useNavigate();
  const { showToast } = useToast();
  const [summary, setSummary] = useState<BusinessRiskPolicySummary | null>(null);
  const [loading, setLoading] = useState(true);

  const load = async () => {
    setLoading(true);
    try {
      const res = await api.getBusinessRiskPolicySummary();
      setSummary(res);
    } catch (e) {
      showToast(`加载风险策略失败：${String(e)}`, "error");
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => { load(); }, []);

  const totals = summary?.totals;

  return (
    <div className="space-y-5">
      <div className="aurora-bg flex flex-col gap-3 rounded-2xl border border-border/60 px-5 py-5 md:flex-row md:items-start md:justify-between md:px-7 md:py-6">
        <div>
          <div className="flex items-center gap-2 text-xs uppercase tracking-normal sm:tracking-[0.18em] opacity-70">
            <ShieldAlert className="h-4 w-4" /> 风险策略
          </div>
          <h1 className="mt-1 text-2xl font-semibold tracking-tight md:text-3xl">风险与审批边界总览</h1>
          <p className="mt-2 max-w-2xl text-sm text-muted-foreground">
            汇总所有数字员工角色的风险分级。高风险动作 100% 必须人审，中风险可配置策略，低风险自动通过。
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Button variant="ghost" size="sm" onClick={() => navigate("/business-portal")}>
            <ArrowLeft className="mr-1 h-3.5 w-3.5" /> 返回 Portal
          </Button>
          <Button variant="outline" size="sm" onClick={load} disabled={loading}>
            <RefreshCw className={cn("mr-1.5 h-3.5 w-3.5", loading && "animate-spin")} /> 刷新
          </Button>
        </div>
      </div>

      {/* Totals */}
      {totals && (
        <div className="grid grid-cols-2 gap-2 md:grid-cols-4 md:gap-3">
          <TotalCard label="数字员工" value={totals.agents} tone="default" icon={ShieldCheck} />
          <TotalCard label="🔴 高风险" value={totals.high} tone="rose" icon={AlertTriangle} />
          <TotalCard label="🟡 中风险" value={totals.medium} tone="amber" />
          <TotalCard label="🟢 低风险" value={totals.low} tone="emerald" />
        </div>
      )}

      {/* By category */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center justify-between">
            <span>分类风险分布</span>
            <Badge variant="outline" className="text-xs">{summary?.byCategory.length ?? 0} 类</Badge>
          </CardTitle>
        </CardHeader>
        <CardContent>
          {loading ? (
            <div className="space-y-2">
              {Array.from({ length: 3 }).map((_, i) => (
                <div key={i} className="h-10 rounded bg-muted animate-pulse" />
              ))}
            </div>
          ) : !summary || summary.byCategory.length === 0 ? (
            <p className="text-xs text-muted-foreground">还没有数据</p>
          ) : (
            <div className="space-y-2">
              {summary.byCategory.map((bucket) => (
                <CategoryRow key={bucket.category} bucket={bucket} />
              ))}
            </div>
          )}
        </CardContent>
      </Card>

      {/* High risk actions */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center justify-between">
            <span className="flex items-center gap-2">
              <AlertTriangle className="h-4 w-4 text-rose-500" />
              高风险动作清单
            </span>
            <Badge variant="destructive" className="text-xs font-mono">
              {summary?.highRiskActions.length ?? 0}
            </Badge>
          </CardTitle>
        </CardHeader>
        <CardContent>
          {!summary || summary.highRiskActions.length === 0 ? (
            <p className="text-xs text-muted-foreground">还没有高风险动作配置</p>
          ) : (
            <ul className="space-y-1.5">
              {summary.highRiskActions.map((a, i) => (
                <li
                  key={i}
                  className="flex items-start gap-3 rounded-md border border-rose-500/20 bg-rose-500/5 p-2.5 text-sm"
                >
                  <span className="text-rose-500 mt-0.5">●</span>
                  <div className="min-w-0 flex-1">
                    <p className="leading-snug">{a.action}</p>
                    <p className="mt-0.5 text-xs text-muted-foreground">
                      <span className="font-mono">{a.templateId}</span>
                      <span className="mx-1.5 opacity-30">·</span>
                      <span>{a.agentName} ({categoryLabel(a.category)})</span>
                    </p>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

function TotalCard({
  label,
  value,
  tone,
  icon: Icon,
}: {
  label: string;
  value: number;
  tone: "default" | "rose" | "amber" | "emerald";
  icon?: typeof ShieldCheck;
}) {
  const fg = {
    default: "text-foreground",
    rose: "text-rose-500",
    amber: "text-amber-500",
    emerald: "text-emerald-500",
  }[tone];
  return (
    <Card>
      <CardContent className="p-4">
        <div className="flex items-center gap-2 text-xs uppercase tracking-wider text-muted-foreground">
          {Icon && <Icon className="h-3 w-3" />}
          <span>{label}</span>
        </div>
        <div className={cn("mt-1 font-mono text-2xl font-semibold", fg)}>{value}</div>
      </CardContent>
    </Card>
  );
}

function CategoryRow({ bucket }: { bucket: BusinessRiskPolicySummary["byCategory"][number] }) {
  const total = bucket.high + bucket.medium + bucket.low;
  const high = total ? (bucket.high / total) * 100 : 0;
  const medium = total ? (bucket.medium / total) * 100 : 0;
  const low = total ? (bucket.low / total) * 100 : 0;
  return (
    <div className="rounded-md border border-border/60 p-3">
      <div className="flex items-center justify-between text-sm">
        <span className="font-medium">{categoryLabel(bucket.category)}</span>
        <span className="text-xs text-muted-foreground">
          {bucket.agents} agents · {total} 条规则
        </span>
      </div>
      <div className="mt-2 flex h-2 overflow-hidden rounded bg-muted">
        <div className="bg-rose-500" style={{ width: `${high}%` }} title={`高 ${bucket.high}`} />
        <div className="bg-amber-500" style={{ width: `${medium}%` }} title={`中 ${bucket.medium}`} />
        <div className="bg-emerald-500" style={{ width: `${low}%` }} title={`低 ${bucket.low}`} />
      </div>
      <div className="mt-1.5 flex items-center gap-3 text-xs text-muted-foreground">
        <span className="text-rose-500">🔴 {bucket.high}</span>
        <span className="text-amber-500">🟡 {bucket.medium}</span>
        <span className="text-emerald-500">🟢 {bucket.low}</span>
      </div>
    </div>
  );
}
