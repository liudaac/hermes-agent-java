import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { MessageSquare, X, Sparkles, Send, ArrowRight } from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";

/**
 * Floating CTA that connects business users directly to the Playground
 * with optional context (workspace/scenario) carried via URL params.
 *
 * Renders as a circular FAB on the bottom-right; clicking opens a small
 * panel with quick prompts that hand off to /playground.
 */
export default function FloatingBusinessChat({
  workspaceId,
  scenarioId,
}: {
  workspaceId?: string;
  scenarioId?: string;
}) {
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState("");

  const QUICK_PROMPTS = [
    "我有 50 份简历需要筛选",
    "我要处理一张供应商发票",
    "员工申请离职，启动流程",
    "新员工后天入职，开始准备",
  ];

  const send = (prompt: string) => {
    const text = prompt.trim();
    if (!text) return;
    const params = new URLSearchParams();
    if (workspaceId) params.set("workspaceId", workspaceId);
    if (scenarioId) params.set("scenarioId", scenarioId);
    params.set("prompt", text);
    navigate(`/playground?${params.toString()}`);
  };

  return (
    <>
      {/* FAB */}
      <button
        onClick={() => setOpen((o) => !o)}
        className={cn(
          "fixed bottom-5 right-5 z-40 flex h-14 w-14 items-center justify-center rounded-full shadow-lg ring-2 ring-background transition-all",
          "bg-orange-500 hover:bg-orange-400 text-white",
          open && "scale-90",
        )}
        aria-label="打开 AI 助手"
      >
        {open ? <X className="h-6 w-6" /> : <MessageSquare className="h-6 w-6" />}
      </button>

      {/* Panel */}
      {open && (
        <div className="fixed bottom-24 right-5 z-40 w-[calc(100vw-2.5rem)] max-w-sm overflow-hidden rounded-xl border border-border bg-background shadow-2xl animate-in slide-in-from-bottom-4 duration-200">
          <div className="bg-gradient-to-br from-orange-500/15 to-transparent px-4 py-3 border-b border-border">
            <div className="flex items-center gap-2">
              <div className="flex h-8 w-8 items-center justify-center rounded-full bg-orange-500/20 text-orange-600 dark:text-orange-400">
                <Sparkles className="h-4 w-4" />
              </div>
              <div className="min-w-0 flex-1">
                <p className="text-sm font-semibold tracking-tight">数字员工助理</p>
                <p className="text-[0.65rem] text-muted-foreground">
                  {workspaceId
                    ? `当前空间：${workspaceId}`
                    : "随时开口，把工作交给团队"}
                </p>
              </div>
            </div>
          </div>

          <div className="p-3 space-y-2">
            <p className="text-[0.7rem] uppercase tracking-wider text-muted-foreground">快捷指令</p>
            <div className="flex flex-col gap-1.5">
              {QUICK_PROMPTS.map((prompt) => (
                <button
                  key={prompt}
                  onClick={() => send(prompt)}
                  className="rounded-md border border-border/70 px-3 py-2 text-left text-sm transition-colors hover:border-border hover:bg-muted/40"
                >
                  {prompt}
                  <ArrowRight className="ml-1 inline h-3 w-3 opacity-50" />
                </button>
              ))}
            </div>
          </div>

          <div className="border-t border-border p-3">
            <div className="flex items-end gap-2">
              <textarea
                value={draft}
                onChange={(e) => setDraft(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter" && !e.shiftKey) {
                    e.preventDefault();
                    send(draft);
                  }
                }}
                placeholder="或者直接告诉我你想做什么…"
                rows={2}
                className="flex-1 resize-none rounded-md border border-border bg-background px-2 py-1.5 text-sm focus:outline-none focus:ring-1 focus:ring-foreground"
              />
              <Button size="icon" onClick={() => send(draft)} disabled={!draft.trim()}>
                <Send className="h-4 w-4" />
              </Button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
