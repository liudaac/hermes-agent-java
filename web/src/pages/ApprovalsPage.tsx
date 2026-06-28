import { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  ShieldAlert,
  RefreshCw,
  ArrowLeft,
  ListFilter,
  AlertTriangle,
  ClipboardList,
} from "lucide-react";
import {
  api,
  type BusinessApprovalRecord,
  type WorkspaceRecord,
} from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { useToast } from "@/hooks/useToast";
import { cn } from "@/lib/utils";
import { ApprovalRow } from "@/components/business/BusinessPortalSections";

type StatusFilter = "PENDING" | "ALL";

const STATUS_FILTERS: { id: StatusFilter; label: string }[] = [
  { id: "PENDING", label: "待审批" },
  { id: "ALL", label: "全部" },
];

const RISK_FILTERS: ("ALL" | "HIGH" | "MEDIUM" | "LOW")[] = ["ALL", "HIGH", "MEDIUM", "LOW"];

export default function ApprovalsPage() {
  const navigate = useNavigate();
  const { showToast } = useToast();
  const [approvals, setApprovals] = useState<BusinessApprovalRecord[]>([]);
  const [workspaces, setWorkspaces] = useState<WorkspaceRecord[]>([]);
  const [statusFilter, setStatusFilter] = useState<StatusFilter>("PENDING");
  const [riskFilter, setRiskFilter] = useState<typeof RISK_FILTERS[number]>("ALL");
  const [workspaceFilter, setWorkspaceFilter] = useState<string>("");
  const [loading, setLoading] = useState(true);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.getBusinessApprovals(workspaceFilter || undefined, statusFilter);
      setApprovals(res.approvals ?? []);
      if (workspaces.length === 0) {
        const home = await api.getBusinessHome();
        setWorkspaces(home.workspaces ?? []);
      }
    } catch (e) {
      showToast(`加载审批失败：${String(e)}`, "error");
    } finally {
      setLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [statusFilter, workspaceFilter]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  const filtered = useMemo(() => {
    if (riskFilter === "ALL") return approvals;
    return approvals.filter((a) => (a.riskLevel ?? "").toUpperCase() === riskFilter);
  }, [approvals, riskFilter]);

  const buckets = useMemo(() => {
    const m = { HIGH: 0, MEDIUM: 0, LOW: 0 } as Record<string, number>;
    approvals.forEach((a) => {
      const r = (a.riskLevel ?? "LOW").toUpperCase();
      m[r] = (m[r] ?? 0) + 1;
    });
    return m;
  }, [approvals]);

  const approve = async (approval: BusinessApprovalRecord, reason: string) => {
    await api.approveBusinessApproval(approval.workspaceId, approval.approvalId, {
      actor: "business-portal-ui",
      reason,
    });
    showToast("已通过审批", "success");
    refresh();
  };
  const reject = async (approval: BusinessApprovalRecord, reason: string) => {
    await api.rejectBusinessApproval(approval.workspaceId, approval.approvalId, {
      actor: "business-portal-ui",
      reason,
    });
    showToast("已驳回", "success");
    refresh();
  };
  const requestInfo = async (approval: BusinessApprovalRecord, requestedInfo: string) => {
    await api.requestBusinessApprovalInfo(approval.workspaceId, approval.approvalId, {
      actor: "business-portal-ui",
      requestedInfo,
    });
    showToast("已发出补充信息请求", "success");
    refresh();
  };
  const resume = async (approval: BusinessApprovalRecord) => {
    const evidence = approval.evidence as Record<string, string> | undefined;
    const scenarioId = evidence?.scenarioId;
    const userInput = evidence?.userInput;
    if (!scenarioId || !userInput) {
      showToast("缺少 scenarioId 或 userInput", "error");
      return;
    }
    await api.resumeExecution(approval.workspaceId, approval.approvalId, scenarioId, userInput);
    showToast("已恢复执行", "success");
    refresh();
  };

  return (
    <div className="space-y-5">
      <div className="aurora-bg flex flex-col gap-3 rounded-2xl border border-border/60 px-5 py-5 md:flex-row md:items-start md:justify-between md:px-7 md:py-6">
        <div>
          <div className="flex items-center gap-2 text-xs uppercase tracking-normal sm:tracking-[0.18em] opacity-70">
            <ShieldAlert className="h-4 w-4" /> 审批中心
          </div>
          <h1 className="mt-1 text-2xl font-semibold tracking-tight md:text-3xl">待办审批</h1>
          <p className="mt-2 text-sm text-muted-foreground">
            移动端友好的审批中心。所有高风险动作和需要人工把关的决策都汇集于此。
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Button variant="ghost" size="sm" onClick={() => navigate("/business-portal")}>
            <ArrowLeft className="mr-1 h-3.5 w-3.5" /> 返回 Portal
          </Button>
          <Button variant="outline" size="sm" onClick={refresh} disabled={loading}>
            <RefreshCw className={cn("mr-1.5 h-3.5 w-3.5", loading && "animate-spin")} /> 刷新
          </Button>
        </div>
      </div>

      {/* Risk summary */}
      <div className="grid grid-cols-3 gap-2 md:gap-3">
        <RiskBucket level="HIGH" count={buckets.HIGH ?? 0} icon={AlertTriangle} active={riskFilter === "HIGH"} onClick={() => setRiskFilter(riskFilter === "HIGH" ? "ALL" : "HIGH")} />
        <RiskBucket level="MEDIUM" count={buckets.MEDIUM ?? 0} icon={ListFilter} active={riskFilter === "MEDIUM"} onClick={() => setRiskFilter(riskFilter === "MEDIUM" ? "ALL" : "MEDIUM")} />
        <RiskBucket level="LOW" count={buckets.LOW ?? 0} icon={ClipboardList} active={riskFilter === "LOW"} onClick={() => setRiskFilter(riskFilter === "LOW" ? "ALL" : "LOW")} />
      </div>

      {/* Filters */}
      <Card>
        <CardContent className="flex flex-wrap items-center gap-2 p-3">
          <div className="flex items-center gap-1">
            {STATUS_FILTERS.map((f) => (
              <button
                key={f.id}
                onClick={() => setStatusFilter(f.id)}
                className={cn(
                  "rounded-full px-3 py-1 text-xs font-medium transition-colors",
                  statusFilter === f.id ? "bg-foreground text-background" : "bg-muted text-foreground hover:bg-muted/80",
                )}
              >
                {f.label}
              </button>
            ))}
          </div>
          <div className="h-4 w-px bg-border" />
          <select
            value={workspaceFilter}
            onChange={(e) => setWorkspaceFilter(e.target.value)}
            className="rounded-md border border-border bg-background px-2 py-1 text-xs"
          >
            <option value="">全部工作空间</option>
            {workspaces.map((ws) => (
              <option key={ws.workspaceId} value={ws.workspaceId}>{ws.name}</option>
            ))}
          </select>
          <div className="ml-auto text-xs text-muted-foreground">
            共 {filtered.length} 条
          </div>
        </CardContent>
      </Card>

      {/* Approval list */}
      {filtered.length === 0 ? (
        <Card>
          <CardContent className="flex flex-col items-center justify-center gap-3 py-10 text-center">
            <ShieldAlert className="h-8 w-8 text-muted-foreground/40" />
            <div>
              <p className="text-sm font-medium">没有待审批事项 🎉</p>
              <p className="mt-1 text-xs text-muted-foreground">所有高风险动作已处理完毕</p>
            </div>
          </CardContent>
        </Card>
      ) : (
        <div className="space-y-3">
          {filtered.map((approval) => (
            <ApprovalRow
              key={approval.approvalId}
              approval={approval}
              onApprove={approve}
              onReject={reject}
              onRequestInfo={requestInfo}
              onResumeExecution={resume}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function RiskBucket({
  level,
  count,
  icon: Icon,
  active,
  onClick,
}: {
  level: "HIGH" | "MEDIUM" | "LOW";
  count: number;
  icon: typeof AlertTriangle;
  active: boolean;
  onClick: () => void;
}) {
  const tone = {
    HIGH: { ring: "ring-rose-500/40", text: "text-rose-500", bg: "bg-rose-500/10", label: "🔴 高风险" },
    MEDIUM: { ring: "ring-amber-500/40", text: "text-amber-500", bg: "bg-amber-500/10", label: "🟡 中风险" },
    LOW: { ring: "ring-emerald-500/40", text: "text-emerald-500", bg: "bg-emerald-500/10", label: "🟢 低风险" },
  }[level];
  return (
    <button
      onClick={onClick}
      className={cn(
        "rounded-lg border border-border/60 p-3 text-left transition-all",
        active ? `ring-2 ${tone.ring}` : "hover:border-border",
      )}
    >
      <div className="flex items-center justify-between">
        <span className={cn("text-xs font-medium", tone.text)}>{tone.label}</span>
        <Icon className={cn("h-3.5 w-3.5", tone.text)} />
      </div>
      <div className={cn("mt-1 font-mono text-2xl font-semibold tabular-nums", tone.text)}>{count}</div>
    </button>
  );
}
