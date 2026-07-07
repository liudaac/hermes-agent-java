import { lazy, Suspense } from "react";
import { Routes, Route, Navigate } from "react-router-dom";
import { NocTopBar } from "@/components/NocTopBar";
import { JarvisCore } from "@hermes/jarvis";

const OrgControlCenterPage = lazy(() => import("@/pages/OrgControlCenterPage"));
const TraceDetailPage = lazy(() => import("@/pages/TraceDetailPage"));
const WorkflowPage = lazy(() => import("@/pages/WorkflowPage"));
const SLAPage = lazy(() => import("@/pages/SLAPage"));
const DLQPage = lazy(() => import("@/pages/DLQPage"));
const HumanInTheLoopPage = lazy(() => import("@/pages/HumanInTheLoopPage"));

function PageLoading() {
  return (
    <div className="flex h-64 items-center justify-center text-sm tracking-[0.12em] opacity-70">
      Loading...
    </div>
  );
}

/**
 * NOC router. Flat routes — no nested space. Cross-product links are
 * full-page navigations via <a href> in the topbar.
 */
export function NocRouter() {
  return (
    <div className="min-h-screen bg-background text-foreground antialiased">
      <div className="noc-glow-bottom" aria-hidden />
      <NocTopBar />
      <main className="relative z-1 mx-auto w-full max-w-[1600px] flex-1 px-3 sm:px-6 pt-4 sm:pt-6 pb-4 sm:pb-8">
        <Suspense fallback={<PageLoading />}>
          <Routes>
            <Route path="/" element={<OrgControlCenterPage />} />
            <Route path="/traces/:traceId" element={<TraceDetailPage />} />
            <Route path="/workflows" element={<WorkflowPage />} />
            <Route path="/sla" element={<SLAPage />} />
            <Route path="/dlq" element={<DLQPage />} />
            <Route path="/hitl" element={<HumanInTheLoopPage />} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </Suspense>
      </main>

      {/* Cross-space dialogue shell — design.md §0 */}
      <JarvisCore />
    </div>
  );
}
