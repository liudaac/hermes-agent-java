import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  Sparkles, ArrowLeft, RefreshCw, GitBranch, CheckCircle2, X, Play,
  Lightbulb, BarChart3,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { api } from "@/lib/api";
import type { EvolutionProposalRecord, WorkspaceRecord } from "@/lib/api";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import { useToast } from "@/hooks/useToast";

const STATUSES = [
  { id: "ALL", label: "全部" },
  { id: "AWAITING_APPROVAL", label: "待审批" },
  { id: "APPROVED", label: "已审批" },
  { id: "APPLIED", label: "已应用" },
  { id: "REJECTED", label: "已驳回" },
];

export default function EvolutionPanelPage() {
  const navigate = useNavigate();
  const { showToast } = useToast();
  const [workspaces, setWorkspaces] = useState<WorkspaceRecord[]>([]);
  const [workspaceId, setWorkspaceId] = useState("");
  const [status, setStatus] = useState("ALL");
  const [proposals, setProposals] = useState<EvolutionProposalRecord[]>([]);
  const [loading, setLoading] = useState(false);
  const [busyId, setBusyId] = useState<string | null>(null);

  useEffect(() => {
    api.getBusinessHome().then((res) => {
      setWorkspaces(res.workspaces ?? []);
      if ((res.workspaces ?? []).length > 0 && !workspaceId) {
        setWorkspaceId(res.workspaces[0].workspaceId);
      }
    });
  }, []);

  useEffect(() => {
    if (!workspaceId) return;
    load();
  }, [workspaceId, status]);

  const load = async () => {
    if (!workspaceId) return;
    setLoading(true);
    try {
      const res = await api.listEvolutionProposals(workspaceId, status === "ALL" ? undefined : status);
      setProposals(res.proposals ?? []);
    } catch (e) {
      showToast(`加载提案失败：${String(e)}`, "error");
    } finally {
      setLoading(false);
    }
  };

  const counts = useMemo(() => {
    const m: Record<string, number> = { ALL: proposals.length };
    proposals.forEach((p) => {
      const s = (p.status ?? "DRAFT").toUpperCase();
      m[s] = (m[s] ?? 0) + 1;
    });
    return m;
  }, [proposals]);

  const handle = async (proposal: EvolutionProposalRecord, action: "approve" | "reject" | "apply") => {
    if (!proposal.workspaceId) return;
    setBusyId(proposal.proposalId);
    try {
      if (action === "approve") {
        await api.approveEvolutionProposal(proposal.workspaceId, proposal.proposalId, { actor: "business-portal-ui", reason: "Reviewed in Evolution panel" });
        showToast("已通过", "success");
      } else if (action === "reject") {
        const reason = prompt("驳回原因：");
        if (!reason) return;
        await api.rejectEvolutionProposal(proposal.workspaceId, proposal.proposalId, { actor: "business-portal-ui", reason });
        showToast("已驳回", "success");
      } else {
        await api.applyEvolutionProposal(proposal.workspaceId, proposal.proposalId, { actor: "business-portal-ui" });
        showToast("已应用，进入灰度", "success");
      }
      await load();
    } catch (e) {
      showToast(`操作失败：${String(e)}`, "error");
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div className="space-y-5">
      <div className="flex flex-col gap-3 md:flex-row md:items-start md:justify-between">
        <div>
          <div className="flex items-center gap-2 text-xs uppercase tracking-normal sm:tracking-[0.18em] opacity-60">
            <Sparkles className="h-4 w-4" /> 自进化中心
          </div>
          <h1 className="mt-1 text-2xl font-semibold tracking-tight">系统建议 → 人审 → 灰度</h1>
          <p className="mt-2 max-w-2xl text-sm text-muted-foreground">
            数字员工根据运行数据自动总结建议，必须经过人审才会进入灰度发布；每一次进化都有完整证据链。
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Button variant="ghost" size="sm" onClick={() => navigate("/business-portal")}>
            <ArrowLeft className="mr-1 h-3.5 w-3.5" /> 返回 Portal
          </Button>
          <Button variant="outline" size="sm" onClick={load} disabled={loading || !workspaceId}>
            <RefreshCw className={cn("mr-1.5 h-3.5 w-3.5", loading && "animate-spin")} /> 刷新
          </Button>
        </div>
      </div>

      <Card>
        <CardContent className="flex flex-wrap items-center gap-2 p-3">
          <select value={workspaceId} onChange={(e) => setWorkspaceId(e.target.value)}
            className="rounded-md border border-border bg-background px-2 py-1 text-xs">
            <option value="">选择工作空间</option>
            {workspaces.map((ws) => (<option key={ws.workspaceId} value={ws.workspaceId}>{ws.name}</option>))}
          </select>
          <div className="h-4 w-px bg-border" />
          <div className="flex flex-wrap items-center gap-1">
            {STATUSES.map((s) => {
              const active = status === s.id;
              const n = counts[s.id] ?? (s.id === "ALL" ? proposals.length : 0);
              return (
                <button key={s.id} onClick={() => setStatus(s.id)}
                  className={cn("rounded-full px-3 py-1 text-xs font-medium transition-colors",
                    active ? "bg-foreground text-background" : "bg-muted text-foreground hover:bg-muted/80")}>
                  {s.label}
                  {n > 0 && <span className="ml-1 opacity-60 font-mono text-xs">{n}</span>}
                </button>
              );
            })}
          </div>
        </CardContent>
      </Card>

      {!workspaceId ? (
        <Card><CardContent className="flex flex-col items-center justify-center gap-2 py-10 text-center text-sm text-muted-foreground">
          <Sparkles className="h-8 w-8 opacity-40" />
          <p>选择一个工作空间查看进化提案</p>
        </CardContent></Card>
      ) : proposals.length === 0 ? (
        <Card><CardContent className="flex flex-col items-center justify-center gap-2 py-10 text-center">
          <Lightbulb className="h-8 w-8 text-muted-foreground/40" />
          <p className="text-sm font-medium">还没有进化提案</p>
          <p className="text-xs text-muted-foreground">系统在运行数据积累足够后会自动生成建议</p>
        </CardContent></Card>
      ) : (
        <div className="space-y-3">
          {proposals.map((p) => (
            <ProposalCard key={p.proposalId} proposal={p} busy={busyId === p.proposalId}
              onApprove={() => handle(p, "approve")}
              onReject={() => handle(p, "reject")}
              onApply={() => handle(p, "apply")} />
          ))}
        </div>
      )}
    </div>
  );
}

function ProposalCard({ proposal, busy, onApprove, onReject, onApply }: {
  proposal: EvolutionProposalRecord;
  busy: boolean;
  onApprove: () => void;
  onReject: () => void;
  onApply: () => void;
}) {
  const s = (proposal.status ?? "DRAFT").toUpperCase();
  const variantMap: Record<string, "outline" | "info" | "warning" | "success" | "destructive" | "live"> = {
    DRAFT: "outline", EVALUATED: "info", AWAITING_APPROVAL: "warning",
    APPROVED: "success", REJECTED: "destructive", APPLIED: "live",
  };
  const variant = variantMap[s] ?? "outline";

  return (
    <Card>
      <CardHeader className="pb-3">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <CardTitle className="text-base leading-snug">{proposal.title || proposal.proposalId}</CardTitle>
            <p className="mt-1 text-xs text-muted-foreground">
              <code className="font-mono">{proposal.proposalId}</code>
              {proposal.teamId ? <> · 目标团队 <code className="font-mono">{proposal.teamId}</code></> : null}
              {proposal.scenarioId ? <> · 场景 <code className="font-mono">{proposal.scenarioId}</code></> : null}
            </p>
          </div>
          <Badge variant={variant} className="text-xs uppercase tracking-wider">{s}</Badge>
        </div>
      </CardHeader>
      <CardContent className="space-y-3">
        {proposal.finding && <Field icon={BarChart3} label="发现"><p>{proposal.finding}</p></Field>}
        {proposal.proposedChange && <Field icon={GitBranch} label="建议变更"><p>{proposal.proposedChange}</p></Field>}
        {proposal.expectedBenefit && <Field icon={Sparkles} label="预期收益"><p>{proposal.expectedBenefit}</p></Field>}
        <div className="flex flex-wrap items-center justify-end gap-2 pt-2 border-t border-border/60">
          {(s === "DRAFT" || s === "EVALUATED" || s === "AWAITING_APPROVAL") && (
            <>
              <Button variant="outline" size="sm" onClick={onReject} disabled={busy}>
                <X className="mr-1 h-3.5 w-3.5" /> 驳回
              </Button>
              <Button size="sm" onClick={onApprove} disabled={busy}>
                <CheckCircle2 className="mr-1 h-3.5 w-3.5" /> 通过
              </Button>
            </>
          )}
          {s === "APPROVED" && (
            <Button size="sm" onClick={onApply} disabled={busy}>
              <Play className="mr-1 h-3.5 w-3.5" /> 应用并灰度
            </Button>
          )}
        </div>
      </CardContent>
    </Card>
  );
}

function Field({ icon: Icon, label, children }: { icon: LucideIcon; label: string; children: React.ReactNode }) {
  return (
    <div>
      <div className="mb-1 flex items-center gap-1.5 text-xs uppercase tracking-wider text-muted-foreground">
        <Icon className="h-3 w-3" />
        {label}
      </div>
      <div className="rounded-md border border-border/60 bg-muted/30 px-3 py-2 text-sm leading-relaxed">
        {children}
      </div>
    </div>
  );
}
