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

const MODULES = [
  { id: "overview", icon: Layout, label: "Overview" },
  { id: "identity", icon: Users, label: "Identity" },
  { id: "handoff", icon: Clipboard, label: "Handoffs" },
  { id: "auth", icon: Shield, label: "Auth" },
  { id: "knowledge", icon: BookOpen, label: "Knowledge" },
  { id: "workflow", icon: GitBranch, label: "Workflows" },
  { id: "market", icon: Store, label: "Market" },
  { id: "observe", icon: Activity, label: "Observe" },
  { id: "distributed", icon: Network, label: "Distributed" },
  { id: "evolution", icon: Brain, label: "Evolution" },
  { id: "compliance", icon: Monitor, label: "Compliance" },
];

export default function OrgPage() {
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
  const fetchTemplates = async () => { try { const r = await fetchJSON<any>("/api/org/market/templates"); setTemplates(r.templates || []); } catch {} };
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
        <p className="text-xs text-muted-foreground">Wire modules via DashboardServer to enable org features</p>
      </div>
    );
  }

  return (
    <div className="space-y-6 p-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">AI-Native Organization</h1>
          <p className="text-muted-foreground text-sm">Real-time organizational intelligence dashboard</p>
        </div>
        <Button variant="outline" size="sm" onClick={fetchOrg}>
          <RefreshCw className="h-4 w-4 mr-2" /> Refresh
        </Button>
      </div>

      <Tabs defaultValue="overview">
        {(active, setActive) => (
          <>
            <TabsList className="w-full overflow-x-auto">
              {MODULES.map(m => (
                <TabsTrigger
                  key={m.id}
                  active={active === m.id}
                  value={m.id}
                  onClick={() => setActive(m.id)}
                  className="gap-1.5"
                >
                  <m.icon className="h-3.5 w-3.5" />
                  {m.label}
                </TabsTrigger>
              ))}
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
        <p className="text-muted-foreground col-span-3 text-center py-12">No modules wired. Wire org module instances via DashboardServer.</p>
      )}
    </div>
  );
}

function IdentityTab({ data }: { data: any }) {
  if (!data) return <EmptyModule />;
  return (
    <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mt-4">
      <StatCard label="Total Identities" value={data.total_identities} />
      <StatCard label="Active" value={data.active} />
      <StatCard label="Deactivated" value={data.deactivated} />
      <StatCard label="OIDC Bound" value={data.with_oidc} />
      <StatCard label="Expiring Credentials" value={data.expiring_credentials} color="text-orange-500" />
    </div>
  );
}

function HandoffTab({ data, handoffs, onLoad }: { data: any; handoffs: HandoffItem[]; onLoad: () => void }) {
  useEffect(() => { if (!handoffs.length) onLoad(); }, []);
  if (!data) return <EmptyModule />;
  return (
    <div className="space-y-4 mt-4">
      <div className="grid grid-cols-4 gap-4">
        <StatCard label="Pending" value={data.pending} color="text-yellow-500" />
        <StatCard label="Acknowledged" value={data.acknowledged} />
        <StatCard label="Escalated" value={data.escalated} color="text-red-500" />
        <StatCard label="Resolved" value={data.total_resolved} />
      </div>
      {handoffs.length > 0 && (
        <div className="space-y-2">
          <h3 className="text-sm font-medium">Pending Handoffs</h3>
          {handoffs.map(h => (
            <Card key={h.id} className="p-3">
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-sm font-medium">{h.summary}</p>
                  <p className="text-xs text-muted-foreground">{h.agent} · <Clock className="h-3 w-3 inline" /> {new Date(h.created).toLocaleString()}</p>
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
  if (!data) return <EmptyModule />;
  return (
    <div className="grid grid-cols-2 md:grid-cols-3 gap-4 mt-4">
      <StatCard label="Subjects" value={data.rbac?.subjects} />
      <StatCard label="Custom Roles" value={data.rbac?.custom_roles} />
      <StatCard label="ABAC Policies" value={data.abac_policies} />
      <StatCard label="Overrides" value={data.overrides} />
    </div>
  );
}

function KnowledgeTab({ data }: { data: any }) {
  if (!data) return <EmptyModule />;
  return (
    <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mt-4">
      <StatCard label="Total Entries" value={data.total_entries} />
      <StatCard label="Unique Tags" value={data.unique_tags} />
      <StatCard label="Unique Topics" value={data.unique_topics} />
      <StatCard label="Stale Entries" value={data.stale_count} color={data.stale_count > 0 ? "text-orange-500" : ""} />
    </div>
  );
}

function WorkflowTab({ data }: { data: any }) {
  if (!data) return <EmptyModule />;
  return (
    <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mt-4">
      <StatCard label="Active" value={data.active_workflows} color="text-blue-500" />
      <StatCard label="Waiting Human" value={data.waiting_for_human} color="text-yellow-500" />
      <StatCard label="Completed" value={data.completed} />
      <StatCard label="Failed" value={data.failed} color="text-red-500" />
      <StatCard label="Templates" value={data.template_count} />
      <StatCard label="Compensated" value={data.compensated} />
    </div>
  );
}

function MarketTab({ data, cost, templates, onLoad }: { data: any; cost: any; templates: TemplateItem[]; onLoad: () => void }) {
  useEffect(() => { if (!templates.length) onLoad(); }, []);
  return (
    <div className="space-y-4 mt-4">
      {cost && (
        <div className="grid grid-cols-3 gap-4">
          <StatCard label="Today" value={cost.today} icon={DollarSign} />
          <StatCard label="This Month" value={cost.this_month} icon={TrendingUp} />
          <StatCard label="Forecast" value={cost.forecast} icon={TrendingUp} />
        </div>
      )}
      {templates.length > 0 && (
        <div className="space-y-2">
          <h3 className="text-sm font-medium">Templates ({data?.total || 0})</h3>
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
          <StatCard label="Total Templates" value={data.total} icon={Store} />
          <StatCard label="Total Installs" value={data.installs} icon={TrendingUp} />
        </div>
      )}
    </div>
  );
}

function ObserveTab({ data, anomalies, onLoad }: { data: any; anomalies: AnomalyItem[]; onLoad: () => void }) {
  useEffect(() => { if (!anomalies.length) onLoad(); }, []);
  if (!data) return <EmptyModule />;
  return (
    <div className="space-y-4 mt-4">
      <div className="grid grid-cols-3 gap-4">
        <StatCard label="Active Traces" value={data.active_traces} color="text-blue-500" />
        <StatCard label="Total Traces" value={data.total_traces} />
        <StatCard label="Agents Tracked" value={data.agents_tracked} />
      </div>
      {anomalies.length > 0 && (
        <div className="space-y-2">
          <h3 className="text-sm font-medium flex items-center gap-1.5"><AlertTriangle className="h-4 w-4 text-orange-500" /> Recent Anomalies</h3>
          {anomalies.map((a, i) => (
            <Card key={i} className="p-3 border-l-4 border-l-orange-500">
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-sm">{a.message}</p>
                  <p className="text-xs text-muted-foreground">{a.agent} · {new Date(a.time).toLocaleString()}</p>
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
  useEffect(() => { if (!nodes.length) onLoad(); }, []);
  if (!data) return <EmptyModule />;
  return (
    <div className="space-y-4 mt-4">
      <div className="grid grid-cols-3 gap-4">
        <StatCard label="Total Agents" value={data.total_agents} />
        <StatCard label="Total Nodes" value={data.total_nodes} />
        <StatCard label="Capabilities" value={data.capabilities?.length || 0} />
      </div>
      {nodes.length > 0 && (
        <div className="space-y-2">
          <h3 className="text-sm font-medium">Registered Nodes</h3>
          {nodes.map(n => (
            <Card key={n.id} className="p-3">
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-sm font-medium">{n.id}</p>
                  <p className="text-xs text-muted-foreground">{n.address} · load: {n.load}</p>
                </div>
                <div className="flex items-center gap-2">
                  <Badge variant={n.healthy ? "default" : "destructive"} className="text-xs">{n.healthy ? "OK" : "DOWN"}</Badge>
                  {n.tags.slice(0,3).map(t => <Badge key={t} variant="outline" className="text-xs">{t}</Badge>)}
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
  if (!data) return <EmptyModule />;
  return (
    <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mt-4">
      <StatCard label="Total Failures" value={data.total_failures} />
      <StatCard label="Resolved" value={data.resolved} />
      <StatCard label="Resolution Rate" value={data.resolution_rate} />
      <StatCard label="Pending Suggestions" value={data.pending_suggestions} />
    </div>
  );
}

function ComplianceTab({ data }: { data: any }) {
  if (!data) return <EmptyModule />;
  return (
    <div className="grid grid-cols-2 gap-4 mt-4">
      <StatCard label="Residency Rules" value={data.residency_rules} />
      <StatCard label="Agents w/ Regions" value={data.agents_with_regions} />
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
  return (
    <div className="flex flex-col items-center justify-center h-48 mt-4 text-center">
      <Network className="h-10 w-10 text-muted-foreground/30 mb-3" />
      <p className="text-muted-foreground">Module not yet wired</p>
      <p className="text-xs text-muted-foreground">Wire via orgApiHandler.with("moduleName", instance)</p>
    </div>
  );
}
