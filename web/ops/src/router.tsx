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
const ConfigPage = lazy(() => import("@/pages/ConfigPage"));
const EnvPage = lazy(() => import("@/pages/EnvPage"));
const SpaceAdminPage = lazy(() => import("@/pages/SpaceAdminPage"));
const OrgAdminPage = lazy(() => import("@/pages/OrgAdminPage"));
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
 * Ops router. Three-layer admin replaces Tenants/Skills/Org.
 * NOC pages will be added in Step 3.
 */
export function OpsRouter() {
  return (
    <div className="min-h-screen bg-background text-foreground antialiased">
      <OpsTopBar />
      <main className="mx-auto w-full max-w-[1600px] flex-1 px-3 sm:px-6 pt-4 sm:pt-6 pb-4 sm:pb-8">
        <Suspense fallback={<PageLoading />}>
          <Routes>
            <Route path="/" element={<StatusPage />} />
            <Route path="/spaces" element={<SpaceAdminPage />} />
            <Route path="/org" element={<OrgAdminPage />} />
            <Route path="/playground" element={<PlaygroundPage />} />
            <Route path="/compare" element={<ComparePage />} />
            <Route path="/sessions" element={<SessionsPage />} />
            <Route path="/analytics" element={<AnalyticsPage />} />
            <Route path="/logs" element={<LogsPage />} />
            <Route path="/cron" element={<CronPage />} />
            <Route path="/tools" element={<ToolsPage />} />
            <Route path="/dlq" element={<DLQPage />} />
            <Route path="/workflows" element={<WorkflowPage />} />
            <Route path="/hitl" element={<HumanLoopPage />} />
            <Route path="/config" element={<ConfigPage />} />
            <Route path="/env" element={<EnvPage />} />
            {/* Legacy redirects */}
            <Route path="/tenants" element={<SpaceAdminPage />} />
            <Route path="/skills" element={<SpaceAdminPage />} />
            <Route path="/sla" element={<AnalyticsPage />} />
            <Route path="*" element={<StatusPage />} />
          </Routes>
        </Suspense>
      </main>
      <JarvisCore />
    </div>
  );
}
