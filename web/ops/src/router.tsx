import { lazy, Suspense } from "react";
import { Routes, Route, NavLink } from "react-router-dom";
import { JarvisCore } from "@hermes/jarvis";
import { OPS_NAV } from "@/lib/nav";
import { cn } from "@hermes/ui";
import { LanguageSwitcher } from "@/components/LanguageSwitcher";

const StatusPage = lazy(() => import("@/pages/StatusPage"));
const SessionsPage = lazy(() => import("@/pages/SessionsPage"));
const CronPage = lazy(() => import("@/pages/CronPage"));
const ToolsPage = lazy(() => import("@/pages/ToolsPage"));
const DLQPage = lazy(() => import("@/pages/DLQPage"));
const WorkflowPage = lazy(() => import("@/pages/WorkflowPage"));
const HumanLoopPage = lazy(() => import("@/pages/HumanLoopPage"));
const LogsPage = lazy(() => import("@/pages/LogsPage"));
const AnalyticsPage = lazy(() => import("@/pages/AnalyticsPage"));
const PlaygroundPage = lazy(() => import("@/pages/PlaygroundPage"));
const ComparePage = lazy(() => import("@/pages/ComparePage"));
const SLAPage = lazy(() => import("@/pages/SLAPage"));
const TracesPage = lazy(() => import("@/pages/TracesPage"));

const GROUP_LABELS: Record<string, string> = {
  operations: "运维",
  observability: "可观测",
  tools: "工具",
};

function PageLoading() {
  return <div className="flex h-64 items-center justify-center text-sm text-muted-foreground">Loading...</div>;
}

export function OpsRouter() {
  const groups = ["operations", "observability", "tools"] as const;
  return (
    <div className="min-h-screen bg-background text-foreground">
      <header className="sticky top-0 z-30 border-b border-border bg-card/80 backdrop-blur-md">
        <div className="mx-auto flex h-14 w-full max-w-[1600px] items-center justify-between px-6">
          <div className="flex items-center gap-3">
            <span className="text-sm font-semibold tracking-tight">Hermes Ops</span>
            <span className="text-[10px] font-medium uppercase tracking-wider text-muted-foreground">控制台</span>
          </div>
          <LanguageSwitcher />
        </div>
      </header>
      <div className="mx-auto flex w-full max-w-[1600px]">
        <aside className="sticky top-14 h-[calc(100vh-3.5rem)] w-56 shrink-0 border-r border-border bg-card/50 py-4">
          <nav className="flex flex-col gap-5 px-3">
            {groups.map((group) => (
              <div key={group}>
                <div className="mb-1.5 px-3 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                  {GROUP_LABELS[group]}
                </div>
                <div className="flex flex-col gap-0.5">
                  {OPS_NAV.filter((item) => item.group === group).map((item) => {
                    const Icon = item.icon;
                    return (
                      <NavLink
                        key={item.path}
                        to={item.path}
                        end={item.path === "/"}
                        className={({ isActive }) =>
                          cn(
                            "flex items-center gap-2.5 rounded-lg px-3 py-1.5 text-[13px] transition-colors",
                            isActive
                              ? "bg-primary/10 font-medium text-primary"
                              : "text-foreground/70 hover:bg-muted hover:text-foreground"
                          )
                        }
                      >
                        <Icon className="h-3.5 w-3.5" />
                        {item.label}
                      </NavLink>
                    );
                  })}
                </div>
              </div>
            ))}
          </nav>
        </aside>
        <main className="flex-1 overflow-x-hidden px-6 py-6">
          <Suspense fallback={<PageLoading />}>
            <Routes>
              <Route path="/" element={<StatusPage />} />
              <Route path="/sessions" element={<SessionsPage />} />
              <Route path="/cron" element={<CronPage />} />
              <Route path="/tools" element={<ToolsPage />} />
              <Route path="/dlq" element={<DLQPage />} />
              <Route path="/workflows" element={<WorkflowPage />} />
              <Route path="/hitl" element={<HumanLoopPage />} />
              <Route path="/logs" element={<LogsPage />} />
              <Route path="/analytics" element={<AnalyticsPage />} />
              <Route path="/playground" element={<PlaygroundPage />} />
              <Route path="/compare" element={<ComparePage />} />
              <Route path="/sla" element={<SLAPage />} />
              <Route path="/traces" element={<TracesPage />} />
              <Route path="/config" element={<RedirectToAdmin />} />
              <Route path="/env" element={<RedirectToDevPortal />} />
              <Route path="/spaces" element={<RedirectToAdmin />} />
              <Route path="/org" element={<RedirectToAdmin />} />
              <Route path="/tenants" element={<RedirectToAdmin />} />
              <Route path="/skills" element={<RedirectToAdmin />} />
              <Route path="*" element={<StatusPage />} />
            </Routes>
          </Suspense>
        </main>
      </div>
      <JarvisCore />
    </div>
  );
}

function RedirectToAdmin() {
  window.location.href = "/admin/index.html";
  return null;
}

function RedirectToDevPortal() {
  window.location.href = "/devportal/index.html";
  return null;
}
