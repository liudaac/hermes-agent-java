import { useEffect, useState, useCallback } from "react";
import {
  Layout, Users, Shield, BookOpen, GitBranch, Store,
  Activity, Network, Brain, Clipboard, Monitor, AlertTriangle,
  RefreshCw, Clock, TrendingUp, DollarSign, WifiOff,
} from "lucide-react";
import { fetchJSON } from "@/lib/api";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useI18n } from "@/i18n";

interface OrgSummary {
  [key: string]: any;
}

interface AgentNode {
  id: string; node: string; address: string; capabilities: string[];
  tags: string[]; load: number; healthy: boolean;
}

interface HandoffItem {
  id: string; agent: string; summary: string; priority: string; status: string; created: string;
}

interface TemplateItem {
  id: string; name: string; description: string; category: string;
  tools: string[]; installs: number; rating: string; level: string;
}

interface AnomalyItem {
  type: string; agent: string; message: string; time: string;
}

const TAB_KEYS = [
  "overview", "identity", "handoff", "auth", "knowledge",
  "workflow", "market", "observe", "distributed", "evolution", "compliance"
] as const;

const TAB_ICONS: Record<string, any> = {
  overview: Layout, identity: Users, handoff: Clipboard,
  auth: Shield, knowledge: BookOpen, workflow: GitBranch,
  market: Store, observe: Activity, distributed: Network,
  evolution: Brain, compliance: Monitor,
};

export default function OrgPage() {
  const { t } = useI18n();
  const [summary, setSummary] = useState<OrgSummary>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const [nodes, setNodes] = useState<AgentNode[]>([]);
  const [handoffs, setHandoffs] = useState<HandoffItem[]>([]);
  const [templates, setTemplates] = useState<TemplateItem[]>([]);
  const [anomalies, setAnomalies] = useState<AnomalyItem[]>([]);

  const fetchOrg = useCallback(async () => {
    setLoading(true)
    setError("");
    try {
      const s = await fetchJSON<any>("/api/org/summary");
      setSummary(s);
    } catch (e: any) {
      setError(e.message || "Failed to fetch org summary");
    } finally {
      setLoading(false);
    }
  }, []);

  const fetchNodes = async () => { try { const r = await fetchJSON<any>("/api/org/distributed/nodes"); setNodes(r.nodes || []); } catch {} };
  const fetchHandoffs = async () => { try { const r = await fetchJSON<any>("/api/org/handoff/pending"); setHandoffs(r.handoffs || []); } catch {} };
  const fetchTemplates = async () => { try { const r = await fetchJSON<any>("/api/org/templates"); setTemplates(r.templates || []); } catch {} };
  const fetchAnomalies = async () => { try { const r = await fetchJSON<any>("/api/org/observe/anomalies?n=10"); setAnomalies(r.anomalies || []); } catch {} };

  useEffect(() => { fetchOrg(); }, [fetchOrg]);

  if (loading) {
    return (
      <div className="flex items-center justify-center h-64">
        <RefreshCw className="animate-spin h-8 w-8 text-muted-foreground" />
      </div>
    );
  }

  if (error) {
    return (
      <div className="flex flex-col items-center justify-center h-64 gap-4">
        <WifiOff className="h-12 w-12 text-muted-foreground" />
        <p className="text-muted-foreground">{error}</p>
        <p className="text-xs text-muted-foreground">{t.org.wireHint}</p>
      </div>
    );
  }

  return (
    <div className="space-y-6 p-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">{t.org.title}</h1>
          <p className="text-muted-foreground text-sm">{t.org.subtitle}</p>
        </div>
        <Button variant="outline" size="sm" onClick={fetchOrg}>
          <RefreshCw className="h-4 w-4 mr-2" /> {t.org.refresh}
        </Button>
      </div>

      <Tabs defaultValue="overview">
        {(active, setActive) => (
          <>
            <TabsList className="w-full overflow-x-auto">
              {TAB_KEYS.map(k => {
                const Icon = TAB_ICONS[k];
                return (
                  <TabsTrigger
                    key={k}
                    active={active === k}
                    value={k}
                    onClick={() => setActive(k)}
                    className="gap-1.5"
                  >
                    <Icon className="h-3.5 w-3.5" />
                    {(t.org.tabs as any)[k]}
                  </TabsTrigger>
                );
              })}
            </TabsList>

            {active === "overview" && <OverviewTab summary={summary} />}
            {active === "identity" && <IdentityTab data={summary.identity} />}
            {active === "handoff" && <HandoffTab data={summary.handoff} handoffs={handoffs} onLoad={fetchHandoffs} />}
            {active === "auth" && <AuthTab data={summary.auth} />}
            {active === "knowledge" && <KnowledgeTab data={summary.knowledge} />}
            {active === "workflow" && <WorkflowTab data={summary.workflow} />}
            {active === "market" && <MarketTab data={summary.market} cost={summary.cost} templates={templates} onLoad={fetchTemplates} />}
            {active === "observe" && <ObserveTab data={summary.observe} anomalies={anomalies} onLoad={fetchAnomalies} />}
            {active === "distributed" && <DistributedTab data={summary.distributed} nodes={nodes} onLoad={fetchNodes} />}
            {active === "evolution" && <EvolutionTab data={summary.evolution} />}
            {active === "compliance" && <ComplianceTab data={summary.compliance} />}
          </>
        )}
      </Tabs>
    </div>
  );
}

function OverviewTab({ summary }: { summary: any }) {
  const { t } = useI18n();
  const items = Object.entries(summary).map(([k, v]) => ({ key: k, data: v }));
  return (
    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4 mt-4">
      {items.map(({ key, data }) => (
        <Card key={key}>
          <CardHeader className="pb-2">
            <CardTitle className="text-sm capitalize">{key}</CardTitle>
          </CardHeader>
          <CardContent>
            <pre className="text-xs text-muted-foreground max-h-48 overflow-auto">
              {JSON.stringify(data, null, 2)}
            </pre>
          </CardContent>
        </Card>
      ))}
      {items.length === 0 && (
        <p className="text-muted-foreground col-span-3 text-center py-12">{t.org.noModules}</p>
      )}
    </div>
  );
}

function IdentityTab({ data }: { data: any }) {
  const { t } = useI18n();
  if (!data) return <EmptyModule />;
  return (
    <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mt-4">
      <StatCard label={t.org.totalIdentities} value={data.total_identities} />
      <StatCard label={t.org.active} value={data.active} />
      <StatCard label={t.org.deactivated} value={data.deactivated} />
      <StatCard label={t.org.oidcBound} value={data.with_oidc} />
      <StatCard label={t.org.expiringCredentials} value={data.expiring_credentials} color="text-orange-500" />
    </div>
  );
}

function HandoffTab({ data, handoffs, onLoad }: { data: any; handoffs: HandoffItem[]; onLoad: () => void }) {
  const { t } = useI18n();
  useEffect(() => { if (!handoffs.length) onLoad(); }, []);
  if (!data) return <EmptyModule />;
  return (
    <div className="space-y-4 mt-4">
      <div className="grid grid-cols-4 gap-4">
        <StatCard label={t.org.pending} value={data.pending} color="text-yellow-500" />
        <StatCard label={t.org.acknowledged} value={data.acknowledged} />
        <StatCard label={t.org.escalated} value={data.escalated} color="text-red-500" />
        <StatCard label={t.org.resolved} value={data.total_resolved} />
      </div>
      {handoffs.length > 0 && (
        <div className="space-y-2">
          <h3 className="text-sm font-medium">{t.org.pendingHandoffs}</h3>
          {handoffs.map(h => (
            <Card key={h.id} className="p-3">
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-sm font-medium">{h.summary}</p>
                  <p className="text-xs text-muted-foreground">{h.agent} <Clock className="h-3 w-3 inline" /> {new Date(h.created).toLocaleString()}</p>
                </div>
                <Badge variant={h.priority === "CRITICAL" ? "destructive" : "outline"}>{h.priority}</Badge>
              </div>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}

function AuthTab({ data }: { data: any }) {
  const { t } = useI18n();
  if (!data) return <EmptyModule />;
  return (
    <div className="grid grid-cols-2 md:grid-cols-3 gap-4 mt-4">
      <StatCard label={t.org.subjects} value={data.rbac?.subjects} />
      <StatCard label={t.org.customRoles} value={data.rbac?.custom_roles} />
      <StatCard label={t.org.abacPolicies} value={data.abac_policies} />
      <StatCard label={t.org.overrides} value={data.overrides} />
    </div>
  );
}

function KnowledgeTab({ data }: { data: any }) {
  const { t } = useI18n();
  if (!data) return <EmptyModule />;
  return (
    <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mt-4">
      <StatCard label={t.org.totalEntries} value={data.total_entries} />
      <StatCard label={t.org.uniqueTags} value={data.unique_tags} />
      <StatCard label={t.org.uniqueTopics} value={data.unique_topics} />
      <StatCard label={t.org.staleEntries} value={data.stale_count} color={data.stale_count > 0 ? "text-orange-500" : ""} />
    </div>
  );
}

function WorkflowTab({ data }: { data: any }) {
  const { t } = useI18n();
  if (!data) return <EmptyModule />;
  return (
    <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mt-4">
      <StatCard label={t.org.activeWorkflows} value={data.active_workflows} color="text-blue-500" />
      <StatCard label={t.org.waitingHuman} value={data.waiting_for_human} color="text-yellow-500" />
      <StatCard label={t.org.completed} value={data.completed} />
      <StatCard label={t.org.failed} value={data.failed} color="text-red-500" />
      <StatCard label={t.org.workflowTemplates} value={data.template_count} />
      <StatCard label={t.org.compensated} value={data.compensated} />
    </div>
  );
}

function MarketTab({ data, cost, templates, onLoad }: { data: any; cost: any; templates: TemplateItem[]; onLoad: () => void }) {
  const { t } = useI18n();
  useEffect(() => { if (!templates.length) onLoad(); }, []);
  return (
    <div className="space-y-4 mt-4">
      {cost && (
        <div className="grid grid-cols-3 gap-4">
          <StatCard label={t.org.today} value={cost.today} icon={DollarSign} />
          <StatCard label={t.org.thisMonth} value={cost.this_month} icon={TrendingUp} />
          <StatCard label={t.org.forecast} value={cost.forecast} icon={TrendingUp} />
        </div>
      )}
      {templates.length > 0 && (
        <div className="space-y-2">
          <h3 className="text-sm font-medium">{t.org.templateTemplates.replace("{count}", String(data?.total || 0))}</h3>
          {templates.map(t => (
            <Card key={t.id} className="p-3">
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-sm font-medium">{t.name}</p>
                  <p className="text-xs text-muted-foreground">{t.description}</p>
                </div>
                <div className="flex items-center gap-2">
                  <Badge variant="outline">{t.category}</Badge>
                  <Badge variant="secondary">{t.installs} installs</Badge>
                </div>
              </div>
            </Card>
          ))}
        </div>
      )}
      {data && (
        <div className="grid grid-cols-2 gap-4">
          <StatCard label={t.org.totalTemplates} value={data.total} icon={Store} />
          <StatCard label={t.org.totalInstalls} value={data.installs} icon={TrendingUp} />
        </div>
      )}
    </div>
  );
}

function ObserveTab({ data, anomalies, onLoad }: { data: any; anomalies: AnomalyItem[]; onLoad: () => void }) {
  const { t } = useI18n();
  useEffect(() => { if (!anomalies.length) onLoad(); }, []);
  if (!data) return <EmptyModule />;
  return (
    <div className="space-y-4 mt-4">
      <div className="grid grid-cols-3 gap-4">
        <StatCard label={t.org.activeTraces} value={data.active_traces} color="text-blue-500" />
        <StatCard label={t.org.totalTraces} value={data.total_traces} />
        <StatCard label={t.org.agentsTracked} value={data.agents_tracked} />
      </div>
      {anomalies.length > 0 && (
        <div className="space-y-2">
          <h3 className="text-sm font-medium flex items-center gap-1.5"><AlertTriangle className="h-4 w-4 text-orange-500" /> {t.org.recentAnomalies}</h3>
          {anomalies.map((a, i) => (
            <Card key={i} className="p-3 border-l-4 border-l-orange-500">
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-sm">{a.message}</p>
                  <p className="text-xs text-muted-foreground">{a.agent} {new Date(a.time).toLocaleString()}</p>
                </div>
                <Badge variant="outline">{a.type}</Badge>
              </div>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}

function DistributedTab({ data, nodes, onLoad }: { data: any; nodes: AgentNode[]; onLoad: () => void }) {
  const { t } = useI18n();
  useEffect(() => { if (!nodes.length) onLoad(); }, []);
  if (!data) return <EmptyModule />;
  return (
    <div className="space-y-4 mt-4">
      <div className="grid grid-cols-3 gap-4">
        <StatCard label={t.org.totalAgents} value={data.total_agents} />
        <StatCard label={t.org.totalNodes} value={data.total_nodes} />
        <StatCard label={t.org.capabilities} value={data.capabilities?.length || 0} />
      </div>
      {nodes.length > 0 && (
        <div className="space-y-2">
          <h3 className="text-sm font-medium">{t.org.registeredNodes}</h3>
          {nodes.map(n => (
            <Card key={n.id} className="p-3">
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-sm font-medium">{n.id}</p>
                  <p className="text-xs text-muted-foreground">{n.address} {t.org.load}: {n.load}</p>
                </div>
                <div className="flex items-center gap-2">
                  <Badge variant={n.healthy ? "default" : "destructive"} className="text-xs">{n.healthy ? t.org.ok : t.org.down}</Badge>
                  {n.tags.slice(0,3).map(tag => <Badge key={tag} variant="outline" className="text-xs">{tag}</Badge>)}
                </div>
              </div>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}

function EvolutionTab({ data }: { data: any }) {
  const { t } = useI18n();
  if (!data) return <EmptyModule />;
  return (
    <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mt-4">
      <StatCard label={t.org.totalFailures} value={data.total_failures} />
      <StatCard label={t.org.resolved} value={data.resolved} />
      <StatCard label={t.org.resolutionRate} value={data.resolution_rate} />
      <StatCard label={t.org.pendingSuggestions} value={data.pending_suggestions} />
    </div>
  );
}

function ComplianceTab({ data }: { data: any }) {
  const { t } = useI18n();
  if (!data) return <EmptyModule />;
  return (
    <div className="grid grid-cols-2 gap-4 mt-4">
      <StatCard label={t.org.residencyRules} value={data.residency_rules} />
      <StatCard label={t.org.agentsWithRegions} value={data.agents_with_regions} />
    </div>
  );
}

function StatCard({ label, value, color, icon: Icon }: { label: string; value: any; color?: string; icon?: any }) {
  return (
    <Card>
      <CardContent className="pt-4">
        <div className="flex items-center gap-2 mb-1">
          {Icon && <Icon className="h-4 w-4 text-muted-foreground" />}
          <p className="text-xs text-muted-foreground">{label}</p>
        </div>
        <p className={`text-2xl font-bold ${color || ""}`}>{value != null ? value : "—"}</p>
      </CardContent>
    </Card>
  );
}

function EmptyModule() {
  const { t } = useI18n();
  return (
    <div className="flex flex-col items-center justify-center h-48 mt-4 text-center">
      <Network className="h-10 w-10 text-muted-foreground/30 mb-3" />
      <p className="text-muted-foreground">{t.org.notWired}</p>
      <p className="text-xs text-muted-foreground">{t.org.wireEmpty}</p>
    </div>
  );
}
