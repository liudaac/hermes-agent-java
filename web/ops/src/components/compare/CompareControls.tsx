import { Play, Square } from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@hermes/ui";
import { Button } from "@hermes/ui";
import { Input } from "@hermes/ui";
import { useI18n } from "@/i18n";

interface CompareControlsProps {
  autoRunning: boolean;
  autoModeOpen: boolean;
  autoTopic: string;
  autoRounds: number;
  conclusion: string;
  conclusionLoading: boolean;
  hasMessages: boolean;
  onToggleMode: () => void;
  onTopicChange: (v: string) => void;
  onRoundsChange: (v: number) => void;
  onStart: () => void;
  onStop: () => void;
  onGenerateConclusion: () => void;
  activeLabels: string;
}

export function CompareControls({
  autoRunning,
  autoModeOpen,
  autoTopic,
  autoRounds,
  conclusion,
  conclusionLoading,
  hasMessages,
  onToggleMode,
  onTopicChange,
  onRoundsChange,
  onStart,
  onStop,
  onGenerateConclusion,
  activeLabels,
}: CompareControlsProps) {
  const { t } = useI18n();

  return (
    <>
      {/* Auto chat mode */}
      <div className="border border-current/20 rounded-sm overflow-hidden shrink-0">
        <button
          onClick={onToggleMode}
          className="w-full flex items-center justify-between px-3 py-2 text-xs tracking-wider opacity-70 hover:opacity-100 transition-opacity bg-current/5"
        >
          <span className="flex items-center gap-1.5">
            {autoRunning ? (
              <>
                <span className="relative flex h-2 w-2">
                  <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-midground opacity-75" />
                  <span className="relative inline-flex rounded-full h-2 w-2 bg-midground" />
                </span>
                {t.compare.autoChatRunning}
              </>
            ) : (
              <>
                <Play className="h-3 w-3" />
                {t.compare.autoChatMode}
              </>
            )}
          </span>
          {autoModeOpen ? (
            <span className="text-[10px] opacity-50">{t.compare.collapse}</span>
          ) : (
            <span className="text-[10px] opacity-50">{t.compare.expand}</span>
          )}
        </button>
        {autoModeOpen && (
          <div className="p-3 space-y-3">
            <div className="flex gap-3">
              <div className="flex-1">
                <label className="text-[10px] opacity-60 block mb-1">
                  {t.compare.initialTopic}
                </label>
                <Input
                  value={autoTopic}
                  onChange={(e) => onTopicChange(e.target.value)}
                  placeholder={t.compare.initialTopicPlaceholder}
                  disabled={autoRunning}
                  className="h-8 text-xs"
                />
              </div>
              <div className="w-24">
                <label className="text-[10px] opacity-60 block mb-1">
                  {t.compare.rounds}
                </label>
                <Input
                  type="number"
                  min={1}
                  max={20}
                  value={autoRounds}
                  onChange={(e) => onRoundsChange(Number(e.target.value))}
                  disabled={autoRunning}
                  className="h-8 text-xs"
                />
              </div>
            </div>
            <div className="flex items-center gap-2 text-[10px] opacity-50">
              <span>
                {t.compare.roundsHint
                  .replace("{participants}", activeLabels)
                  .replace("{rounds}", String(autoRounds))
                  .replace("{totalMessages}", String(autoRounds * 2))}
              </span>
            </div>
            <div className="flex justify-end gap-2">
              {autoRunning ? (
                <Button
                  variant="destructive"
                  size="sm"
                  onClick={onStop}
                  className="h-7 text-xs px-3"
                >
                  <Square className="h-3 w-3 mr-1" />
                  {t.compare.stop}
                </Button>
              ) : (
                <Button
                  size="sm"
                  onClick={onStart}
                  disabled={!autoTopic.trim()}
                  className="h-7 text-xs px-3"
                >
                  <Play className="h-3 w-3 mr-1" />
                  {t.compare.startAutoChat}
                </Button>
              )}
            </div>
          </div>
        )}
      </div>

      {/* Conclusion */}
      {(conclusion || conclusionLoading || hasMessages) && (
        <Card className="shrink-0">
          <CardHeader className="py-2">
            <div className="flex items-center justify-between">
              <CardTitle className="text-sm">{t.compare.conclusion}</CardTitle>
              <Button
                variant="outline"
                size="sm"
                onClick={onGenerateConclusion}
                disabled={conclusionLoading || autoRunning || !hasMessages}
                className="h-7 text-xs px-3"
              >
                {conclusionLoading ? t.compare.conclusionLoading : t.compare.generateConclusion}
              </Button>
            </div>
          </CardHeader>
          {(conclusion || conclusionLoading) && (
            <CardContent className="pt-0 text-xs max-h-48 overflow-y-auto">
              <div className="leading-relaxed whitespace-pre-wrap">
                {conclusion || t.compare.conclusionLoading}
              </div>
            </CardContent>
          )}
        </Card>
      )}
    </>
  );
}
