import { useState } from "react";
import { Sparkles, Loader2, CheckCircle2, Wand2, Users, Shield, Plug, MessageSquare } from "lucide-react";
import { api } from "@/lib/api";
import type { QuickTeamDraft } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { useToast } from "@/hooks/useToast";

interface QuickTeamBuilderProps {
  workspaceId: string;
  onTeamCreated?: () => void;
}

export default function QuickTeamBuilder({ workspaceId, onTeamCreated }: QuickTeamBuilderProps) {
  const { showToast } = useToast();
  const [description, setDescription] = useState("");
  const [step, setStep] = useState<"input" | "drafting" | "confirm" | "publishing">("input");
  const [draft, setDraft] = useState<QuickTeamDraft | null>(null);
  const [answers, setAnswers] = useState<string[]>([]);

  const handleGenerate = async () => {
    if (!description.trim() || !workspaceId) return;
    setStep("drafting");
    try {
      const res = await api.quickTeamDraft(workspaceId, description.trim());
      if (res.ok && res.draft) {
        setDraft(res.draft);
        setAnswers(new Array(res.draft.questions?.length ?? 0).fill(""));
        setStep("confirm");
      } else {
        showToast("Failed to generate draft", "error");
        setStep("input");
      }
    } catch (e) {
      showToast(String(e), "error");
      setStep("input");
    }
  };

  const handleRefine = async () => {
    if (!draft || !workspaceId) return;
    setStep("drafting");
    try {
      const res = await api.quickTeamRefine(workspaceId, description.trim(), draft.rawJson, answers.filter(Boolean));
      if (res.ok && res.draft) {
        setDraft(res.draft);
        setAnswers(new Array(res.draft.questions?.length ?? 0).fill(""));
        setStep("confirm");
      } else {
        showToast("Failed to refine draft", "error");
        setStep("confirm");
      }
    } catch (e) {
      showToast(String(e), "error");
      setStep("confirm");
    }
  };

  const handlePublish = async () => {
    if (!draft || !workspaceId) return;
    setStep("publishing");
    try {
      const res = await api.quickTeamPublish(workspaceId, draft);
      if (res.ok) {
        showToast(`Team "${draft.teamName}" created!`, "success");
        setDescription("");
        setDraft(null);
        setAnswers([]);
        setStep("input");
        onTeamCreated?.();
      } else {
        showToast("Failed to publish team", "error");
        setStep("confirm");
      }
    } catch (e) {
      showToast(String(e), "error");
      setStep("confirm");
    }
  };

  if (step === "input") {
    return (
      <Card className="border-dashed border-border/70">
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-base">
            <Sparkles className="h-5 w-5 text-amber-400" />
            Quick Team Builder
          </CardTitle>
          <CardDescription>
            Describe what you need in one sentence. AI will draft the team blueprint.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="flex gap-2">
            <Input
              placeholder='e.g. "帮我做一个处理售后退货的客服团队"'
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && handleGenerate()}
              disabled={!workspaceId}
              className="flex-1"
            />
            <Button onClick={handleGenerate} disabled={!description.trim() || !workspaceId}>
              <Wand2 className="mr-2 h-4 w-4" /> Generate
            </Button>
          </div>
          {!workspaceId && (
            <p className="mt-2 text-xs text-muted-foreground">Select a workspace first.</p>
          )}
        </CardContent>
      </Card>
    );
  }

  if (step === "drafting") {
    return (
      <Card>
        <CardContent className="flex flex-col items-center justify-center py-12">
          <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
          <p className="mt-4 text-sm text-muted-foreground">AI is designing your team...</p>
        </CardContent>
      </Card>
    );
  }

  if (step === "publishing") {
    return (
      <Card>
        <CardContent className="flex flex-col items-center justify-center py-12">
          <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
          <p className="mt-4 text-sm text-muted-foreground">Publishing team blueprint...</p>
        </CardContent>
      </Card>
    );
  }

  // Confirm step
  if (!draft) return null;

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-base">
          <CheckCircle2 className="h-5 w-5 text-emerald-400" />
          Confirm Team Blueprint
        </CardTitle>
        <CardDescription>
          Review the AI-generated draft. Answer clarifying questions to refine, or publish directly.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-5">
        {/* Team overview */}
        <div className="space-y-2">
          <h3 className="text-sm font-medium">{draft.teamName}</h3>
          <p className="text-sm text-muted-foreground">{draft.description}</p>
          <div className="flex flex-wrap gap-2">
            <Badge variant="outline">{draft.scenario}</Badge>
            <Badge variant="outline">{draft.tone}</Badge>
            {draft.approvalThreshold && (
              <Badge variant="secondary">
                <Shield className="mr-1 h-3 w-3" />
                {draft.approvalThreshold}
              </Badge>
            )}
          </div>
        </div>

        {/* Agents */}
        <div className="space-y-2">
          <h4 className="flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
            <Users className="h-3.5 w-3.5" /> Agents ({draft.agents?.length ?? 0})
          </h4>
          <div className="space-y-2">
            {draft.agents?.map((agent) => (
              <div key={agent.agentId} className="rounded-md border border-border/60 p-3">
                <div className="flex items-center justify-between">
                  <span className="text-sm font-medium">{agent.displayName}</span>
                  <span className="text-xs text-muted-foreground">{agent.agentId}</span>
                </div>
                <p className="mt-1 text-xs text-muted-foreground">{agent.responsibility}</p>
                {agent.allowedTools?.length > 0 && (
                  <div className="mt-2 flex flex-wrap gap-1">
                    {agent.allowedTools.map((t) => (
                      <Badge key={t} variant="outline" className="text-xs">{t}</Badge>
                    ))}
                  </div>
                )}
              </div>
            ))}
          </div>
        </div>

        {/* Connectors */}
        {draft.suggestedConnectors?.length > 0 && (
          <div className="space-y-2">
            <h4 className="flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
              <Plug className="h-3.5 w-3.5" /> Suggested Connectors
            </h4>
            <div className="flex flex-wrap gap-2">
              {draft.suggestedConnectors.map((c) => (
                <Badge key={c} variant="outline" className="text-xs">{c}</Badge>
              ))}
            </div>
          </div>
        )}

        {/* Operating Manual */}
        {draft.operatingManual && (
          <details className="rounded-md border border-border/60">
            <summary className="cursor-pointer p-3 text-xs font-medium">Operating Manual</summary>
            <pre className="max-h-40 overflow-auto p-3 text-xs text-muted-foreground">{draft.operatingManual}</pre>
          </details>
        )}

        {/* Clarifying questions */}
        {draft.questions?.length > 0 && (
          <div className="space-y-3">
            <h4 className="flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
              <MessageSquare className="h-3.5 w-3.5" /> Clarify (optional)
            </h4>
            {draft.questions.map((q, i) => (
              <div key={i} className="space-y-1">
                <p className="text-xs text-muted-foreground">{i + 1}. {q}</p>
                <Input
                  value={answers[i] ?? ""}
                  onChange={(e) => {
                    const next = [...answers];
                    next[i] = e.target.value;
                    setAnswers(next);
                  }}
                  placeholder="Your answer..."
                  className="text-sm"
                />
              </div>
            ))}
            <Button variant="outline" size="sm" onClick={handleRefine} disabled={answers.every((a) => !a.trim())}>
              <Sparkles className="mr-2 h-3.5 w-3.5" /> Refine with answers
            </Button>
          </div>
        )}

        {/* Actions */}
        <div className="flex gap-2 pt-2">
          <Button variant="outline" onClick={() => { setStep("input"); setDraft(null); }}>
            Cancel
          </Button>
          <Button onClick={handlePublish} className="flex-1">
            <CheckCircle2 className="mr-2 h-4 w-4" /> Publish Team
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}
