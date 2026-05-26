import { useEffect, useState, useCallback, useRef } from "react";
import { FileText, RefreshCw, ChevronRight, Layers } from "lucide-react";
import { H2 } from "@nous-research/ui";
import { api } from "@/lib/api";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Switch } from "@/components/ui/switch";
import { Label } from "@/components/ui/label";
import { useI18n } from "@/i18n";

const FILES = ["agent", "errors", "gateway"] as const;
const LEVELS = ["ALL", "DEBUG", "INFO", "WARNING", "ERROR"] as const;
const COMPONENTS = ["all", "gateway", "agent", "tools", "cli", "cron"] as const;
const LINE_COUNTS = [50, 100, 200, 500] as const;
const MAX_TAIL_BUFFER = 2000;

function classifyLine(line: string): "error" | "warning" | "info" | "debug" {
  const upper = line.toUpperCase();
  if (
    upper.includes("ERROR") ||
    upper.includes("CRITICAL") ||
    upper.includes("FATAL")
  )
    return "error";
  if (upper.includes("WARNING") || upper.includes("WARN")) return "warning";
  if (upper.includes("DEBUG")) return "debug";
  return "info";
}

const LINE_COLORS: Record<string, string> = {
  error: "text-destructive",
  warning: "text-warning",
  info: "text-foreground",
  debug: "text-muted-foreground/60",
};

function SidebarHeading({ children }: { children: React.ReactNode }) {
  return (
    <span className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground/60 px-2.5 pt-3 pb-1">
      {children}
    </span>
  );
}

function SidebarItem<T extends string>({
  label,
  value,
  current,
  onChange,
}: SidebarItemProps<T>) {
  const isActive = current === value;
  return (
    <button
      type="button"
      onClick={() => onChange(value)}
      className={`group flex items-center gap-2 px-2.5 py-1 text-left text-xs transition-colors cursor-pointer ${
        isActive
          ? "bg-primary/10 text-primary font-medium"
          : "text-muted-foreground hover:text-foreground hover:bg-muted/50"
      }`}
    >
      <span className="flex-1 truncate">{label}</span>
      {isActive && (
        <ChevronRight className="h-3 w-3 text-primary/50 shrink-0" />
      )}
    </button>
  );
}

export default function LogsPage() {
  const [file, setFile] = useState<(typeof FILES)[number]>("agent");
  const [level, setLevel] = useState<(typeof LEVELS)[number]>("ALL");
  const [component, setComponent] =
    useState<(typeof COMPONENTS)[number]>("all");
  const [lineCount, setLineCount] = useState<(typeof LINE_COUNTS)[number]>(100);
  const [autoRefresh, setAutoRefresh] = useState(false);
  const [liveTail, setLiveTail] = useState(false);
  const [aggregate, setAggregate] = useState(false);
  const [lines, setLines] = useState<string[]>([]);
  const [aggregateLines, setAggregateLines] = useState<
    { file: string; line: string }[]
  >([]);
  const [loading, setLoading] = useState(false);
  const [tailConnected, setTailConnected] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const tailSourceRef = useRef<EventSource | null>(null);
  const { t } = useI18n();

  const scrollToBottom = useCallback(() => {
    setTimeout(() => {
      if (scrollRef.current) {
        scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
      }
    }, 30);
  }, []);

  const fetchLogs = useCallback(() => {
    setLoading(true);
    setError(null);
    if (aggregate) {
      api
        .getLogAggregate({
          files: [`${file}.log`],
          lines: lineCount,
          level,
          component,
        })
        .then((resp) => {
          setAggregateLines(resp.entries);
          scrollToBottom();
        })
        .catch((err) => setError(String(err)))
        .finally(() => setLoading(false));
    } else {
      api
        .getLogs({ file, lines: lineCount, level, component })
        .then((resp) => {
          setLines(resp.lines);
          scrollToBottom();
        })
        .catch((err) => setError(String(err)))
        .finally(() => setLoading(false));
    }
  }, [file, lineCount, level, component, aggregate, scrollToBottom]);

  useEffect(() => {
    fetchLogs();
  }, [fetchLogs]);

  // Polling auto-refresh — disabled when live tail is on.
  useEffect(() => {
    if (!autoRefresh || liveTail) return;
    const interval = setInterval(fetchLogs, 5000);
    return () => clearInterval(interval);
  }, [autoRefresh, liveTail, fetchLogs]);

  // SSE live tail
  useEffect(() => {
    if (!liveTail) {
      if (tailSourceRef.current) {
        tailSourceRef.current.close();
        tailSourceRef.current = null;
      }
      setTailConnected(false);
      return;
    }

    let cancelled = false;
    (async () => {
      try {
        const source = await api.openLogTail({
          file: `${file}.log`,
          level,
          component,
        });
        if (cancelled) {
          source.close();
          return;
        }
        tailSourceRef.current = source;
        source.addEventListener("ready", () => setTailConnected(true));
        source.addEventListener("line", (e: MessageEvent) => {
          try {
            const data = JSON.parse(e.data) as { file: string; line: string };
            if (aggregate) {
              setAggregateLines((prev) => {
                const next = [...prev, data];
                if (next.length > MAX_TAIL_BUFFER)
                  next.splice(0, next.length - MAX_TAIL_BUFFER);
                return next;
              });
            } else {
              setLines((prev) => {
                const next = [...prev, data.line];
                if (next.length > MAX_TAIL_BUFFER)
                  next.splice(0, next.length - MAX_TAIL_BUFFER);
                return next;
              });
            }
            scrollToBottom();
          } catch {
            // ignore malformed event
          }
        });
        source.addEventListener("error", () => {
          setTailConnected(false);
        });
      } catch (err) {
        if (!cancelled) {
          setError(`Failed to start tail: ${String(err)}`);
          setLiveTail(false);
        }
      }
    })();

    return () => {
      cancelled = true;
      if (tailSourceRef.current) {
        tailSourceRef.current.close();
        tailSourceRef.current = null;
      }
      setTailConnected(false);
    };
  }, [liveTail, file, level, component, aggregate, scrollToBottom]);

  const displayLines: { file?: string; line: string }[] = aggregate
    ? aggregateLines
    : lines.map((l) => ({ line: l }));

  return (
    <div className="flex flex-col gap-4">
      {/* ═══════════════ Header ═══════════════ */}
      <div className="flex items-center justify-between gap-4">
        <div className="flex items-center gap-2">
          <FileText className="h-5 w-5 text-muted-foreground" />
          <H2 variant="sm">{t.logs.title}</H2>
          {loading && (
            <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary border-t-transparent" />
          )}
          <Badge variant="secondary" className="text-[10px]">
            {file} · {level} · {component}
          </Badge>
        </div>
        <div className="flex items-center gap-3">
          <div className="flex items-center gap-2">
            <Switch checked={aggregate} onCheckedChange={setAggregate} />
            <Label className="text-xs flex items-center gap-1">
              <Layers className="h-3 w-3" /> merge
            </Label>
          </div>
          <div className="flex items-center gap-2">
            <Switch checked={liveTail} onCheckedChange={setLiveTail} />
            <Label className="text-xs">live tail</Label>
            {liveTail && (
              <Badge
                variant={tailConnected ? "success" : "outline"}
                className="text-[10px]"
              >
                <span
                  className={`mr-1 inline-block h-1.5 w-1.5 rounded-full ${
                    tailConnected
                      ? "bg-current animate-pulse"
                      : "bg-current opacity-50"
                  }`}
                />
                {tailConnected ? "streaming" : "connecting"}
              </Badge>
            )}
          </div>
          <div className="flex items-center gap-2">
            <Switch
              checked={autoRefresh}
              onCheckedChange={setAutoRefresh}
              disabled={liveTail}
            />
            <Label className="text-xs">{t.logs.autoRefresh}</Label>
            {autoRefresh && !liveTail && (
              <Badge variant="success" className="text-[10px]">
                <span className="mr-1 inline-block h-1.5 w-1.5 animate-pulse rounded-full bg-current" />
                {t.common.live}
              </Badge>
            )}
          </div>
          <Button
            variant="outline"
            size="sm"
            onClick={fetchLogs}
            className="text-xs h-7"
            disabled={liveTail}
          >
            <RefreshCw className="h-3 w-3 mr-1" />
            {t.common.refresh}
          </Button>
        </div>
      </div>

      {/* ═══════════════ Sidebar + Content ═══════════════ */}
      <div
        className="flex flex-col sm:flex-row gap-4"
        style={{ minHeight: "calc(100vh - 180px)" }}
      >
        {/* ---- Sidebar ---- */}
        <div className="sm:w-44 sm:shrink-0">
          <div className="sm:sticky sm:top-[72px] flex flex-col gap-0.5">
            <SidebarHeading>{t.logs.file}</SidebarHeading>
            {FILES.map((f) => (
              <SidebarItem
                key={f}
                label={f}
                value={f}
                current={file}
                onChange={setFile}
              />
            ))}

            <SidebarHeading>{t.logs.level}</SidebarHeading>
            {LEVELS.map((l) => (
              <SidebarItem
                key={l}
                label={l}
                value={l}
                current={level}
                onChange={setLevel}
              />
            ))}

            <SidebarHeading>{t.logs.component}</SidebarHeading>
            {COMPONENTS.map((c) => (
              <SidebarItem
                key={c}
                label={c}
                value={c}
                current={component}
                onChange={setComponent}
              />
            ))}

            <SidebarHeading>{t.logs.lines}</SidebarHeading>
            {LINE_COUNTS.map((n) => (
              <SidebarItem
                key={n}
                label={String(n)}
                value={String(n)}
                current={String(lineCount)}
                onChange={(v) =>
                  setLineCount(Number(v) as (typeof LINE_COUNTS)[number])
                }
              />
            ))}
          </div>
        </div>

        {/* ---- Content ---- */}
        <div className="flex-1 min-w-0">
          <Card>
            <CardHeader className="py-3 px-4">
              <CardTitle className="text-sm flex items-center gap-2">
                <FileText className="h-4 w-4" />
                {file}.log
              </CardTitle>
            </CardHeader>
            <CardContent className="p-0">
              {error && (
                <div className="bg-destructive/10 border-b border-destructive/20 p-3">
                  <p className="text-sm text-destructive">{error}</p>
                </div>
              )}

              <div
                ref={scrollRef}
                className="p-4 font-mono-ui text-xs leading-5 overflow-auto max-h-[600px] min-h-[200px]"
              >
                {displayLines.length === 0 && !loading && (
                  <p className="text-muted-foreground text-center py-8">
                    {t.logs.noLogLines}
                  </p>
                )}
                {displayLines.map((entry, i) => {
                  const cls = classifyLine(entry.line);
                  return (
                    <div
                      key={i}
                      className={`${LINE_COLORS[cls]} hover:bg-secondary/20 px-1 -mx-1 flex gap-2`}
                    >
                      {entry.file && (
                        <span className="text-muted-foreground/60 shrink-0 w-24 truncate">
                          {entry.file}
                        </span>
                      )}
                      <span className="flex-1 break-all">{entry.line}</span>
                    </div>
                  );
                })}
              </div>
            </CardContent>
          </Card>
        </div>
      </div>
    </div>
  );
}

interface SidebarItemProps<T extends string> {
  label: string;
  value: T;
  current: T;
  onChange: (v: T) => void;
}
