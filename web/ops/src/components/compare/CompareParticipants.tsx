import { Plus, RotateCcw, Clock, RefreshCw } from "lucide-react";
import { Button } from "@hermes/ui";
import { Badge } from "@hermes/ui";
import { cn } from "@hermes/ui";
import type { CompareRun } from "@/lib/api";
import { useI18n } from "@/i18n";

interface CompareParticipantsProps {
  historyOpen: boolean;
  onToggleHistory: () => void;
  historyRuns: CompareRun[];
  historyLoading: boolean;
  onRefreshHistory: () => void;
  onLoadRun: (runId: string) => void;
  formatRunTime: (value: string) => string;
}

export function CompareParticipants({
  historyOpen,
  onToggleHistory,
  historyRuns,
  historyLoading,
  onRefreshHistory,
  onLoadRun,
  formatRunTime,
}: CompareParticipantsProps) {
  const { t } = useI18n();

  return (
    <>
      {/* History section */}
      <div className="border border-current/20 rounded-sm overflow-hidden shrink-0">
        <button
          onClick={onToggleHistory}
          className="w-full flex items-center justify-between px-3 py-2 text-xs tracking-wider opacity-70 hover:opacity-100 transition-opacity bg-current/5"
        >
          <span>{t.compare.history}</span>
          <span className="text-[10px] opacity-50">{historyOpen ? t.compare.collapse : t.compare.expand}</span>
        </button>
        {historyOpen && (
          <div className="p-3 space-y-2 max-h-64 overflow-y-auto">
            <div className="flex items-center justify-between gap-2">
              <div className="text-[10px] opacity-50">{historyRuns.length} runs</div>
              <Button
                variant="ghost"
                size="sm"
                onClick={onRefreshHistory}
                disabled={historyLoading}
                className="h-6 px-2 text-[10px]"
                title={t.compare.refreshHistory}
              >
                <RefreshCw className={cn("h-3 w-3 mr-1", historyLoading && "animate-spin")} />
                {t.compare.refreshHistory}
              </Button>
            </div>
            {historyRuns.length === 0 && (
              <div className="text-xs opacity-50">{t.compare.noHistory}</div>
            )}
            {historyRuns.slice(0, 20).map((run) => (
              <button
                key={run.id}
                onClick={() => onLoadRun(run.id)}
                className="w-full text-left border border-current/10 hover:border-current/30 rounded-sm px-2 py-1.5 text-xs transition-colors"
                title={t.compare.openRun}
              >
                <div className="flex items-center justify-between gap-2">
                  <span className="truncate font-medium">{run.topic}</span>
                  <Badge variant="outline" className="text-[10px] h-5 shrink-0">{run.status}</Badge>
                </div>
                <div className="opacity-50 mt-1 truncate">
                  {run.participants.map((p) => p.tenant_id).join(" -> ")} · {run.event_count} {t.compare.runEvents}
                </div>
                <div className="opacity-45 mt-1 flex flex-wrap items-center gap-x-3 gap-y-1">
                  <span className="inline-flex items-center gap-1">
                    <Clock className="h-3 w-3" />
                    {t.compare.runCreated}: {formatRunTime(run.created_at)}
                  </span>
                  <span>{t.compare.runUpdated}: {formatRunTime(run.updated_at)}</span>
                </div>
                {run.error && (
                  <div className="mt-1 text-red-300 truncate">
                    {t.compare.runError}: {run.error}
                  </div>
                )}
              </button>
            ))}
          </div>
        )}
      </div>
    </>
  );
}

/** Action buttons for adding/removing participants */
export function ParticipantActions({
  participantsCount,
  autoRunning,
  onAdd,
  onClearAll,
}: {
  participantsCount: number;
  autoRunning: boolean;
  onAdd: () => void;
  onClearAll: () => void;
}) {
  const { t } = useI18n();

  return (
    <div className="flex items-center gap-2">
      <Badge variant="outline" className="text-xs">
        {participantsCount} {t.compare.participants}
      </Badge>
      <Button
        variant="outline"
        size="sm"
        onClick={onAdd}
        disabled={autoRunning}
        className="h-7 text-xs px-2"
      >
        <Plus className="h-3 w-3 mr-1" />
        {t.compare.addParticipant}
      </Button>
      <Button
        variant="ghost"
        size="sm"
        onClick={onClearAll}
        disabled={autoRunning}
        className="h-6 px-1.5"
        title={t.compare.clearBoth}
      >
        <RotateCcw className="h-3 w-3" />
      </Button>
    </div>
  );
}
