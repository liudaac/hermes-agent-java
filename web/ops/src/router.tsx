import { lazy, Suspense } from "react";
import { Routes, Route } from "react-router-dom";
import { OpsTopBar } from "@/components/OpsTopBar";
import { JarvisCore } from "@hermes/jarvis";

const StatusPage = lazy(() => import("@/pages/StatusPage"));
const PlaygroundPage = lazy(() => import("@/pages/PlaygroundPage"));
const ComparePage = lazy(() => import("@/pages/ComparePage"));
const SessionsPage = lazy(() => import("@/pages/SessionsPage"));
const AnalyticsPage = lazy(() => import("@/pages/AnalyticsPage"));
const LogsPage = lazy(() => import("@/pages/LogsPage"));
const CronPage = lazy(() => import("@/pages/CronPage"));
const ToolsPage = lazy(() => import("@/pages/ToolsPage"));
const DLQPage = lazy(() => import("@/pages/DLQPage"));
const WorkflowPage = lazy(() => import("@/pages/WorkflowPage"));
const HumanLoopPage = lazy(() => import("@/pages/HumanLoopPage"));

function PageLoading() {
  return (
    <div className="flex h-64 items-center justify-center text-sm tracking-[0.12em] opacity-70">
      Loading...
    </div>
  );
}

/**
 * Ops router - pure operations & observability.
 * Admin/Config/Env/Spaces/Org pages moved to Admin SPA.
 * OAuth/Env moved to DevPortal SPA.
 */
export function OpsRouter() {
  return (
    <div className="min-h-screen bg-background text-foreground antialiased">
      <OpsTopBar />
      <main className="mx-auto w-full max-w-[1600px] flex-1 px-3 sm:px-6 pt-4 sm:pt-6 pb-4 sm:pb-8">
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
            {/* Legacy redirects */}
            <Route path="/config" element={<RedirectToAdmin />} />
            <Route path="/env" element={<RedirectToDevPortal />} />
            <Route path="/spaces" element={<RedirectToAdmin />} />
            <Route path="/org" element={<RedirectToAdmin />} />
            <Route path="/tenants" element={<RedirectToAdmin />} />
            <Route path="/skills" element={<RedirectToAdmin />} />
            <Route path="/sla" element={<RedirectToAdmin />} />
            <Route path="*" element={<StatusPage />} />
          </Routes>
        </Suspense>
      </main>
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
