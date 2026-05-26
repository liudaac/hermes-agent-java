import { useEffect, useRef, useState } from "react";
import {
  Clock,
  Pause,
  Play,
  Plus,
  Trash2,
  Zap,
  CalendarClock,
  X,
  CheckCircle2,
  AlertTriangle,
} from "lucide-react";
import { H2 } from "@nous-research/ui";
import { api } from "@/lib/api";
import type {
  CronJob,
  CronRunRecord,
  CronTriggerResult,
  CronSchedulePreview,
} from "@/lib/api";
import { useToast } from "@/hooks/useToast";
import { Toast } from "@/components/Toast";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectOption } from "@/components/ui/select";
import { useI18n } from "@/i18n";

function formatTime(iso?: string | null): string {
  if (!iso) return "—";
  const d = new Date(iso);
  return d.toLocaleString();
}

const STATUS_VARIANT: Record<string, "success" | "warning" | "destructive"> = {
  enabled: "success",
  scheduled: "success",
  paused: "warning",
  error: "destructive",
  completed: "destructive",
};

const PRESETS: { label: string; expr: string }[] = [
  { label: "every 5 min", expr: "5m" },
  { label: "every hour", expr: "1h" },
  { label: "daily 09:00", expr: "0 9 * * *" },
  { label: "weekdays 09:00", expr: "0 9 * * 1-5" },
  { label: "every 15 min", expr: "*/15 * * * *" },
];

export default function CronPage() {
  const [jobs, setJobs] = useState<CronJob[]>([]);
  const [loading, setLoading] = useState(true);
  const { toast, showToast } = useToast();
  const { t } = useI18n();

  // New job form state
  const [prompt, setPrompt] = useState("");
  const [schedule, setSchedule] = useState("");
  const [name, setName] = useState("");
  const [deliver, setDeliver] = useState("local");
  const [creating, setCreating] = useState(false);

  // Schedule preview
  const [preview, setPreview] = useState<CronSchedulePreview | null>(null);
  const previewTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  // Trigger output modal
  const [triggerResult, setTriggerResult] =
    useState<CronTriggerResult | null>(null);
  const [triggerModalOpen, setTriggerModalOpen] = useState(false);

  // Run history drawer
  const [historyJob, setHistoryJob] = useState<CronJob | null>(null);
  const [historyRuns, setHistoryRuns] = useState<CronRunRecord[]>([]);
  const historySource = useRef<EventSource | null>(null);

  const loadJobs = () => {
    api
      .getCronJobs()
      .then(setJobs)
      .catch(() => showToast(t.common.loading, "error"))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    loadJobs();
  }, []);

  // Schedule preview (debounced)
  useEffect(() => {
    if (previewTimer.current) clearTimeout(previewTimer.current);
    if (!schedule.trim()) {
      setPreview(null);
      return;
    }
    previewTimer.current = setTimeout(() => {
      api
        .previewCronSchedule(schedule.trim(), 5)
        .then(setPreview)
        .catch(() => setPreview(null));
    }, 400);
    return () => {
      if (previewTimer.current) clearTimeout(previewTimer.current);
    };
  }, [schedule]);

  // Clean up SSE on unmount
  useEffect(() => {
    return () => {
      if (historySource.current) historySource.current.close();
    };
  }, []);

  const handleCreate = async () => {
    if (!prompt.trim() || !schedule.trim()) {
      showToast(`${t.cron.prompt} & ${t.cron.schedule} required`, "error");
      return;
    }
    setCreating(true);
    try {
      await api.createCronJob({
        prompt: prompt.trim(),
        schedule: schedule.trim(),
        name: name.trim() || undefined,
        deliver,
      });
      showToast(t.common.create + " ✓", "success");
      setPrompt("");
      setSchedule("");
      setName("");
      setDeliver("local");
      loadJobs();
    } catch (e) {
      showToast(`${t.config.failedToSave}: ${e}`, "error");
    } finally {
      setCreating(false);
    }
  };

  const handlePauseResume = async (job: CronJob) => {
    try {
      const isPaused = job.state === "paused";
      if (isPaused) {
        await api.resumeCronJob(job.id);
        showToast(
          `${t.cron.resume}: "${job.name || job.prompt.slice(0, 30)}"`,
          "success",
        );
      } else {
        await api.pauseCronJob(job.id);
        showToast(
          `${t.cron.pause}: "${job.name || job.prompt.slice(0, 30)}"`,
          "success",
        );
      }
      loadJobs();
    } catch (e) {
      showToast(`${t.status.error}: ${e}`, "error");
    }
  };

  const handleTrigger = async (job: CronJob) => {
    try {
      const result = await api.triggerCronJob(job.id);
      setTriggerResult(result);
      setTriggerModalOpen(true);
      showToast(
        `${t.cron.triggerNow}: "${job.name || job.prompt.slice(0, 30)}"`,
        result.ok ? "success" : "error",
      );
      loadJobs();
    } catch (e) {
      showToast(`${t.status.error}: ${e}`, "error");
    }
  };

  const openHistory = async (job: CronJob) => {
    setHistoryJob(job);
    try {
      const data = await api.getCronJobRuns(job.id);
      setHistoryRuns(data.runs);
    } catch {
      setHistoryRuns([]);
    }
    // Subscribe to live runs while drawer is open
    if (historySource.current) historySource.current.close();
    const src = await api.openCronRunStream(job.id);
    historySource.current = src;
    src.addEventListener("run", (e: MessageEvent) => {
      try {
        const rec = JSON.parse(e.data) as CronRunRecord & { id: string };
        setHistoryRuns((prev) => {
          const next = [...prev, rec];
          if (next.length > 50) next.splice(0, next.length - 50);
          return next;
        });
      } catch {
        // ignore
      }
    });
  };

  const closeHistory = () => {
    setHistoryJob(null);
    setHistoryRuns([]);
    if (historySource.current) {
      historySource.current.close();
      historySource.current = null;
    }
  };

  const handleDelete = async (job: CronJob) => {
    try {
      await api.deleteCronJob(job.id);
      showToast(
        `${t.common.delete}: "${job.name || job.prompt.slice(0, 30)}"`,
        "success",
      );
      loadJobs();
    } catch (e) {
      showToast(`${t.status.error}: ${e}`, "error");
    }
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center py-24">
        <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-6">
      <Toast toast={toast} />

      {/* Create new job form */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-base">
            <Plus className="h-4 w-4" />
            {t.cron.newJob}
          </CardTitle>
        </CardHeader>
        <CardContent>
          <div className="grid gap-4">
            <div className="grid gap-2">
              <Label htmlFor="cron-name">{t.cron.nameOptional}</Label>
              <Input
                id="cron-name"
                placeholder={t.cron.namePlaceholder}
                value={name}
                onChange={(e) => setName(e.target.value)}
              />
            </div>

            <div className="grid gap-2">
              <Label htmlFor="cron-prompt">{t.cron.prompt}</Label>
              <textarea
                id="cron-prompt"
                className="flex min-h-[80px] w-full border border-input bg-transparent px-3 py-2 text-sm shadow-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
                placeholder={t.cron.promptPlaceholder}
                value={prompt}
                onChange={(e) => setPrompt(e.target.value)}
              />
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
              <div className="grid gap-2">
                <Label htmlFor="cron-schedule">{t.cron.schedule}</Label>
                <Input
                  id="cron-schedule"
                  placeholder={t.cron.schedulePlaceholder}
                  value={schedule}
                  onChange={(e) => setSchedule(e.target.value)}
                />
                <div className="flex flex-wrap gap-1">
                  {PRESETS.map((p) => (
                    <button
                      key={p.expr}
                      type="button"
                      onClick={() => setSchedule(p.expr)}
                      className="text-[10px] px-1.5 py-0.5 border border-input rounded hover:bg-accent/40 cursor-pointer text-muted-foreground"
                    >
                      {p.label}
                    </button>
                  ))}
                </div>
                {preview && (
                  <div className="text-[11px] text-muted-foreground border border-border/40 rounded p-2 mt-1">
                    <div className="flex items-center gap-1 mb-1">
                      <CalendarClock className="h-3 w-3" />
                      <span className="font-medium">
                        {preview.schedule.display}
                      </span>
                      {!preview.valid && (
                        <Badge variant="destructive" className="text-[10px] ml-auto">
                          invalid
                        </Badge>
                      )}
                    </div>
                    {preview.upcoming.length > 0 && (
                      <ul className="space-y-0.5">
                        {preview.upcoming.slice(0, 3).map((iso) => (
                          <li key={iso} className="font-mono">
                            → {formatTime(iso)}
                          </li>
                        ))}
                      </ul>
                    )}
                  </div>
                )}
              </div>

              <div className="grid gap-2">
                <Label htmlFor="cron-deliver">{t.cron.deliverTo}</Label>
                <Select
                  id="cron-deliver"
                  value={deliver}
                  onValueChange={(v) => setDeliver(v)}
                >
                  <SelectOption value="local">
                    {t.cron.delivery.local}
                  </SelectOption>
                  <SelectOption value="telegram">
                    {t.cron.delivery.telegram}
                  </SelectOption>
                  <SelectOption value="discord">
                    {t.cron.delivery.discord}
                  </SelectOption>
                  <SelectOption value="slack">
                    {t.cron.delivery.slack}
                  </SelectOption>
                  <SelectOption value="email">
                    {t.cron.delivery.email}
                  </SelectOption>
                </Select>
              </div>

              <div className="flex items-end">
                <Button
                  onClick={handleCreate}
                  disabled={creating}
                  className="w-full"
                >
                  <Plus className="h-3 w-3" />
                  {creating ? t.common.creating : t.common.create}
                </Button>
              </div>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Jobs list */}
      <div className="flex flex-col gap-3">
        <H2
          variant="sm"
          className="flex items-center gap-2 text-muted-foreground"
        >
          <Clock className="h-4 w-4" />
          {t.cron.scheduledJobs} ({jobs.length})
        </H2>

        {jobs.length === 0 && (
          <Card>
            <CardContent className="py-8 text-center text-sm text-muted-foreground">
              {t.cron.noJobs}
            </CardContent>
          </Card>
        )}

        {jobs.map((job) => (
          <Card key={job.id}>
            <CardContent className="flex items-center gap-4 py-4">
              {/* Info */}
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2 mb-1">
                  <span className="font-medium text-sm truncate">
                    {job.name ||
                      job.prompt.slice(0, 60) +
                        (job.prompt.length > 60 ? "..." : "")}
                  </span>
                  <Badge variant={STATUS_VARIANT[job.state] ?? "secondary"}>
                    {job.state}
                  </Badge>
                  {job.deliver && job.deliver !== "local" && (
                    <Badge variant="outline">{job.deliver}</Badge>
                  )}
                </div>
                {job.name && (
                  <p className="text-xs text-muted-foreground truncate mb-1">
                    {job.prompt.slice(0, 100)}
                    {job.prompt.length > 100 ? "..." : ""}
                  </p>
                )}
                <div className="flex items-center gap-4 text-xs text-muted-foreground">
                  <span className="font-mono">{job.schedule_display}</span>
                  <span>
                    {t.cron.last}: {formatTime(job.last_run_at)}
                  </span>
                  <span>
                    {t.cron.next}: {formatTime(job.next_run_at)}
                  </span>
                </div>
                {job.last_error && (
                  <p className="text-xs text-destructive mt-1">
                    {job.last_error}
                  </p>
                )}
              </div>

              {/* Actions */}
              <div className="flex items-center gap-1 shrink-0">
                <Button
                  variant="ghost"
                  size="icon"
                  title={job.state === "paused" ? t.cron.resume : t.cron.pause}
                  aria-label={
                    job.state === "paused" ? t.cron.resume : t.cron.pause
                  }
                  onClick={() => handlePauseResume(job)}
                >
                  {job.state === "paused" ? (
                    <Play className="h-4 w-4 text-success" />
                  ) : (
                    <Pause className="h-4 w-4 text-warning" />
                  )}
                </Button>

                <Button
                  variant="ghost"
                  size="icon"
                  title={t.cron.triggerNow}
                  aria-label={t.cron.triggerNow}
                  onClick={() => handleTrigger(job)}
                >
                  <Zap className="h-4 w-4" />
                </Button>

                <Button
                  variant="ghost"
                  size="icon"
                  title="Run history"
                  aria-label="Run history"
                  onClick={() => openHistory(job)}
                >
                  <CalendarClock className="h-4 w-4" />
                </Button>

                <Button
                  variant="ghost"
                  size="icon"
                  title={t.common.delete}
                  aria-label={t.common.delete}
                  onClick={() => handleDelete(job)}
                >
                  <Trash2 className="h-4 w-4 text-destructive" />
                </Button>
              </div>
            </CardContent>
          </Card>
        ))}
      </div>

      {/* Trigger output modal */}
      {triggerModalOpen && triggerResult && (
        <div
          className="fixed inset-0 z-50 bg-background/80 backdrop-blur-sm flex items-center justify-center p-4"
          onClick={() => setTriggerModalOpen(false)}
        >
          <Card
            className="w-full max-w-2xl max-h-[80vh] overflow-hidden flex flex-col"
            onClick={(e) => e.stopPropagation()}
          >
            <CardHeader className="flex flex-row items-center justify-between gap-2">
              <CardTitle className="flex items-center gap-2 text-base">
                {triggerResult.ok ? (
                  <CheckCircle2 className="h-4 w-4 text-success" />
                ) : (
                  <AlertTriangle className="h-4 w-4 text-destructive" />
                )}
                Trigger result
                <Badge variant={triggerResult.ok ? "success" : "destructive"}>
                  {triggerResult.duration_ms} ms
                </Badge>
              </CardTitle>
              <Button
                variant="ghost"
                size="icon"
                onClick={() => setTriggerModalOpen(false)}
              >
                <X className="h-4 w-4" />
              </Button>
            </CardHeader>
            <CardContent className="overflow-auto">
              {triggerResult.error && (
                <pre className="text-xs text-destructive bg-destructive/10 p-2 rounded mb-3 whitespace-pre-wrap">
                  {triggerResult.error}
                </pre>
              )}
              <pre className="text-xs bg-muted/40 p-3 rounded whitespace-pre-wrap font-mono leading-relaxed">
                {triggerResult.output || "(no output)"}
              </pre>
            </CardContent>
          </Card>
        </div>
      )}

      {/* Run history drawer */}
      {historyJob && (
        <div
          className="fixed inset-0 z-50 bg-background/80 backdrop-blur-sm flex items-end sm:items-center justify-end sm:justify-center p-4"
          onClick={closeHistory}
        >
          <Card
            className="w-full max-w-3xl max-h-[90vh] overflow-hidden flex flex-col"
            onClick={(e) => e.stopPropagation()}
          >
            <CardHeader className="flex flex-row items-center justify-between gap-2">
              <CardTitle className="flex items-center gap-2 text-base">
                <CalendarClock className="h-4 w-4" />
                Run history
                <span className="text-xs text-muted-foreground font-normal">
                  {historyJob.name ||
                    historyJob.prompt.slice(0, 40) +
                      (historyJob.prompt.length > 40 ? "…" : "")}
                </span>
                <Badge variant="secondary" className="text-[10px]">
                  live
                </Badge>
              </CardTitle>
              <Button variant="ghost" size="icon" onClick={closeHistory}>
                <X className="h-4 w-4" />
              </Button>
            </CardHeader>
            <CardContent className="overflow-auto space-y-2">
              {historyRuns.length === 0 ? (
                <p className="text-xs text-muted-foreground py-6 text-center">
                  No runs yet. Trigger the job to populate history.
                </p>
              ) : (
                historyRuns
                  .slice()
                  .reverse()
                  .map((r, i) => (
                    <div
                      key={i}
                      className="border border-border/40 rounded p-2 text-xs"
                    >
                      <div className="flex items-center gap-2 mb-1">
                        {r.ok ? (
                          <CheckCircle2 className="h-3 w-3 text-success" />
                        ) : (
                          <AlertTriangle className="h-3 w-3 text-destructive" />
                        )}
                        <span className="font-mono">{formatTime(r.at)}</span>
                        <Badge variant="outline" className="text-[10px] ml-auto">
                          {r.duration_ms} ms
                        </Badge>
                      </div>
                      {r.error && (
                        <pre className="text-[11px] text-destructive bg-destructive/10 p-2 rounded mb-1 whitespace-pre-wrap">
                          {r.error}
                        </pre>
                      )}
                      {r.output && (
                        <pre className="text-[11px] bg-muted/30 p-2 rounded whitespace-pre-wrap font-mono">
                          {r.output}
                        </pre>
                      )}
                    </div>
                  ))
              )}
            </CardContent>
          </Card>
        </div>
      )}
    </div>
  );
}
