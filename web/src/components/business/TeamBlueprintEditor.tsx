import { useEffect, useState } from "react";
import {
  Bot,
  Copy,
  GripVertical,
  Loader2,
  Plus,
  Save,
  Sparkles,
  Trash2,
  Wand2,
  X,
} from "lucide-react";
import type { AgentBlueprintPayload, BusinessTeamCard } from "@/lib/api";
import { api } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { useToast } from "@/hooks/useToast";
import { cn } from "@/lib/utils";

const EMPTY_AGENT = (): AgentBlueprintPayload => ({
  agentId: "",
  displayName: "",
  responsibility: "",
  knowledgeRefs: [],
  allowedTools: [],
  allowedSkills: [],
  approvalRules: [],
});

interface TeamBlueprintEditorProps {
  workspaceId: string;
  team: BusinessTeamCard;
  onSaved?: () => void;
}

export default function TeamBlueprintEditor({ workspaceId, team, onSaved }: TeamBlueprintEditorProps) {
  const { showToast } = useToast();
  const [agents, setAgents] = useState<AgentBlueprintPayload[]>([EMPTY_AGENT()]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [activating, setActivating] = useState(false);
  const [draftVersion, setDraftVersion] = useState<number | null>(null);
  const [expandedIndex, setExpandedIndex] = useState<number | null>(0);

  useEffect(() => {
    api.getBusinessTeamBlueprint(workspaceId, team.teamId)
      .then((resp) => {
        const t = resp.team as BusinessTeamCard & { agents?: AgentBlueprintPayload[] };
        setAgents(t.agents && t.agents.length > 0 ? t.agents : [EMPTY_AGENT()]);
      })
      .catch(() => {
        showToast("Failed to load team blueprint.", "error");
      })
      .finally(() => setLoading(false));
  }, [workspaceId, team.teamId, showToast]);

  const updateAgent = (index: number, patch: Partial<AgentBlueprintPayload>) => {
    setAgents((prev) =>
      prev.map((a, i) => (i === index ? { ...a, ...patch } : a)),
    );
  };

  const removeAgent = (index: number) => {
    setAgents((prev) => prev.filter((_, i) => i !== index));
    if (expandedIndex === index) setExpandedIndex(null);
  };

  const duplicateAgent = (index: number) => {
    setAgents((prev) => {
      const copy = { ...prev[index], agentId: `${prev[index].agentId}-copy` };
      const next = [...prev];
      next.splice(index + 1, 0, copy);
      return next;
    });
    setExpandedIndex(index + 1);
  };

  const addAgent = () => {
    setAgents((prev) => [...prev, EMPTY_AGENT()]);
    setExpandedIndex(agents.length);
  };

  const saveDraft = async () => {
    const invalid = agents.filter((a) => !a.agentId || !a.displayName);
    if (invalid.length > 0) {
      showToast("All agents need an ID and display name.", "error");
      return;
    }
    setSaving(true);
    try {
      const resp = await api.createTeamBlueprintDraftVersion(workspaceId, team.teamId, {
        changeSummary: `Editor update: ${agents.length} agents`,
        agents,
      });
      setDraftVersion(resp.version.version);
      showToast(`Draft v${resp.version.version} created.`, "success");
      onSaved?.();
    } catch (err) {
      showToast(`Save failed: ${String(err)}`, "error");
    } finally {
      setSaving(false);
    }
  };

  const activate = async () => {
    if (!draftVersion) return;
    setActivating(true);
    try {
      await api.activateTeamBlueprintVersion(workspaceId, team.teamId, draftVersion);
      showToast(`Version ${draftVersion} activated.`, "success");
      onSaved?.();
    } catch (err) {
      showToast(`Activate failed: ${String(err)}`, "error");
    } finally {
      setActivating(false);
    }
  };

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h3 className="text-sm font-semibold tracking-tight">{team.name || team.teamId}</h3>
          <p className="text-xs text-muted-foreground">
            Active v{team.activeVersion} · {agents.length} agent{agents.length !== 1 ? "s" : ""}
          </p>
        </div>
        <div className="flex items-center gap-2">
          {draftVersion && (
            <Badge variant="info" className="text-xs">
              Draft v{draftVersion}
            </Badge>
          )}
          <Button size="sm" variant="outline" onClick={addAgent}>
            <Plus className="mr-1 h-3.5 w-3.5" />
            Add Agent
          </Button>
          <Button size="sm" onClick={saveDraft} disabled={saving}>
            {saving ? <Loader2 className="mr-1 h-3.5 w-3.5 animate-spin" /> : <Save className="mr-1 h-3.5 w-3.5" />}
            Save Draft
          </Button>
          {draftVersion && (
            <Button size="sm" variant="default" onClick={activate} disabled={activating}>
              {activating ? <Loader2 className="mr-1 h-3.5 w-3.5 animate-spin" /> : <Sparkles className="mr-1 h-3.5 w-3.5" />}
              Activate
            </Button>
          )}
        </div>
      </div>

      {/* Agent Cards */}
      {loading ? (
        <div className="flex items-center justify-center py-12 text-sm text-muted-foreground">
          <Loader2 className="mr-2 h-4 w-4 animate-spin" />
          Loading team blueprint...
        </div>
      ) : (
        <div className="grid gap-3">
          {agents.map((agent, idx) => (
            <AgentCard
              key={idx}
              index={idx}
              agent={agent}
              expanded={expandedIndex === idx}
              onToggle={() => setExpandedIndex(expandedIndex === idx ? null : idx)}
              onUpdate={(patch) => updateAgent(idx, patch)}
              onRemove={() => removeAgent(idx)}
              onDuplicate={() => duplicateAgent(idx)}
              canRemove={agents.length > 1}
            />
          ))}
        </div>
      )}

      {!loading && agents.length === 0 && (
        <div className="rounded-lg border border-dashed border-border p-8 text-center text-sm text-muted-foreground">
          No agents yet. Click "Add Agent" to start building your team.
        </div>
      )}
    </div>
  );
}

function AgentCard({
  index,
  agent,
  expanded,
  onToggle,
  onUpdate,
  onRemove,
  onDuplicate,
  canRemove,
}: {
  index: number;
  agent: AgentBlueprintPayload;
  expanded: boolean;
  onToggle: () => void;
  onUpdate: (patch: Partial<AgentBlueprintPayload>) => void;
  onRemove: () => void;
  onDuplicate: () => void;
  canRemove: boolean;
}) {
  const [tools, setTools] = useState(agent.allowedTools?.join(", ") ?? "");
  const [skills, setSkills] = useState(agent.allowedSkills?.join(", ") ?? "");
  const [rules, setRules] = useState(agent.approvalRules?.join(", ") ?? "");

  const commitTags = (field: "allowedTools" | "allowedSkills" | "approvalRules", raw: string) => {
    const values = raw
      .split(",")
      .map((s) => s.trim())
      .filter(Boolean);
    onUpdate({ [field]: values });
  };

  return (
    <Card className={cn("transition-shadow", expanded && "ring-1 ring-ring")}>
      {/* Collapsed Header — Always visible */}
      <CardHeader className="flex-row items-center gap-3 py-3">
        <GripVertical className="h-4 w-4 shrink-0 cursor-grab text-muted-foreground" />
        <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-md bg-primary/10">
          <Bot className="h-4 w-4 text-primary" />
        </div>
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <span className="text-sm font-medium">{agent.displayName || `Agent ${index + 1}`}</span>
            {agent.agentId && (
              <Badge variant="outline" className="text-[0.65rem]">
                {agent.agentId}
              </Badge>
            )}
          </div>
          <p className="truncate text-xs text-muted-foreground">
            {agent.responsibility || "No responsibility defined."}
          </p>
        </div>
        <div className="flex items-center gap-1">
          <Button variant="ghost" size="icon" className="h-7 w-7" onClick={onDuplicate} title="Duplicate">
            <Copy className="h-3.5 w-3.5" />
          </Button>
          {canRemove && (
            <Button variant="ghost" size="icon" className="h-7 w-7 text-destructive" onClick={onRemove} title="Remove">
              <Trash2 className="h-3.5 w-3.5" />
            </Button>
          )}
          <Button variant="ghost" size="icon" className="h-7 w-7" onClick={onToggle} title={expanded ? "Collapse" : "Expand"}>
            {expanded ? <X className="h-3.5 w-3.5" /> : <Wand2 className="h-3.5 w-3.5" />}
          </Button>
        </div>
      </CardHeader>

      {/* Expanded Form */}
      {expanded && (
        <CardContent className="space-y-3 pb-4 pt-0">
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label="Agent ID" value={agent.agentId} onChange={(v) => onUpdate({ agentId: v })} placeholder="e.g. policy-expert" />
            <Field label="Display Name" value={agent.displayName} onChange={(v) => onUpdate({ displayName: v })} placeholder="e.g. Policy Expert" />
          </div>
          <Field label="Responsibility" value={agent.responsibility ?? ""} onChange={(v) => onUpdate({ responsibility: v })} placeholder="What this agent does in one sentence." />

          <div className="grid gap-3 sm:grid-cols-3">
            <TagField
              label="Allowed Tools"
              value={tools}
              onChange={setTools}
              onBlur={() => commitTags("allowedTools", tools)}
              placeholder="file, browser, search"
            />
            <TagField
              label="Allowed Skills"
              value={skills}
              onChange={setSkills}
              onBlur={() => commitTags("allowedSkills", skills)}
              placeholder="weather, classify"
            />
            <TagField
              label="Approval Rules"
              value={rules}
              onChange={setRules}
              onBlur={() => commitTags("approvalRules", rules)}
              placeholder="refund > 100, external-msg"
            />
          </div>
        </CardContent>
      )}
    </Card>
  );
}

function Field({
  label,
  value,
  onChange,
  placeholder,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  placeholder?: string;
}) {
  return (
    <div className="space-y-1">
      <label className="text-[0.65rem] uppercase tracking-[0.14em] opacity-60">{label}</label>
      <Input
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        className="h-8 text-xs"
      />
    </div>
  );
}

function TagField({
  label,
  value,
  onChange,
  onBlur,
  placeholder,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  onBlur: () => void;
  placeholder?: string;
}) {
  return (
    <div className="space-y-1">
      <label className="text-[0.65rem] uppercase tracking-[0.14em] opacity-60">{label}</label>
      <Input
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onBlur={onBlur}
        placeholder={placeholder}
        className="h-8 text-xs"
      />
      <p className="text-[0.6rem] text-muted-foreground">Comma-separated</p>
    </div>
  );
}
