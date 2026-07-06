import { lazy, Suspense, useEffect, useMemo } from "react";
import { Routes, Route, Navigate, useLocation, useNavigate } from "react-router-dom";
import { migrateOldPath, rememberSpace, resolveDefaultSpace, SPACE_PATHS, type SpaceName } from "@/lib/routing/spaces";
import { RootRedirect } from "@/lib/routing/Redirects";
import { SpaceSwitcher } from "@/lib/routing/SpaceSwitcher";
import { SpaceNav } from "@/lib/routing/SpaceNav";
import { getNavForSpace, pathToSpace } from "@/lib/routing/nav";
import { SpaceThemeBridge } from "@/themes";
import { SpaceLayout } from "@/layouts/SpaceLayout";
import { SpaceDecorations } from "@/layouts/SpaceDecorations";
import {
  Activity,
  BarChart3,
  Clock,
  FileText,
  KeyRound,
  MessageSquare,
  Package,
  Settings,
  Puzzle,
  Sparkles,
  Terminal,
  Globe,
  Database,
  Shield,
  Wrench,
  Zap,
  Heart,
  Star,
  Code,
  Eye,
  Users,
  Bot,
  ArrowLeftRight,
  BriefcaseBusiness,
  GitBranch,
  Timer,
  AlertOctagon,
  Hand,
} from "lucide-react";
import { Cell, Grid, SelectionSwitcher, Typography } from "@nous-research/ui";
import { cn } from "@/lib/utils";
import { Backdrop } from "@/components/Backdrop";
const StatusPage = lazy(() => import("@/pages/StatusPage"));
const ConfigPage = lazy(() => import("@/pages/ConfigPage"));
const EnvPage = lazy(() => import("@/pages/EnvPage"));
const SessionsPage = lazy(() => import("@/pages/SessionsPage"));
const LogsPage = lazy(() => import("@/pages/LogsPage"));
const AnalyticsPage = lazy(() => import("@/pages/AnalyticsPage"));
const CronPage = lazy(() => import("@/pages/CronPage"));
const SkillsPage = lazy(() => import("@/pages/SkillsPage"));
const ToolsPage = lazy(() => import("@/pages/ToolsPage"));
const TenantsPage = lazy(() => import("@/pages/TenantsPage"));
const OrgPage = lazy(() => import("@/pages/OrgPage"));
const OrgControlCenterPage = lazy(() => import("@/pages/OrgControlCenterPage"));
const BusinessPortalStandalonePage = lazy(() => import("@/pages/BusinessPortalStandalonePage"));
const BusinessPortalHome = lazy(() => import("@/pages/BusinessPortalHome"));
const AgentMarketPage = lazy(() => import("@/pages/AgentMarketPage"));
const TemplateGalleryPage = lazy(() => import("@/pages/TemplateGalleryPage"));
const ApprovalsPage = lazy(() => import("@/pages/ApprovalsPage"));
const RiskPolicyPage = lazy(() => import("@/pages/RiskPolicyPage"));
const EvolutionPanelPage = lazy(() => import("@/pages/EvolutionPanelPage"));
const TraceDetailPage = lazy(() => import("@/pages/TraceDetailPage"));
const MyTemplatesPage = lazy(() => import("@/pages/MyTemplatesPage"));
const IndustryDashboardPage = lazy(() => import("@/pages/IndustryDashboardPage"));
const RunDetailPage = lazy(() => import("@/pages/RunDetailPage"));
const WorkflowPage = lazy(() => import("@/pages/WorkflowPage"));
const SLAPage = lazy(() => import("@/pages/SLAPage"));
const DLQPage = lazy(() => import("@/pages/DLQPage"));
const HumanInTheLoopPage = lazy(() => import("@/pages/HumanInTheLoopPage"));
import { LanguageSwitcher } from "@/components/LanguageSwitcher";
import { ThemeSwitcher } from "@/components/ThemeSwitcher";
const PlaygroundPage = lazy(() => import("@/pages/PlaygroundPage"));
const ComparePage = lazy(() => import("@/pages/ComparePage"));
import { useI18n } from "@/i18n";
import { usePlugins } from "@/plugins";
import type { RegisteredPlugin } from "@/plugins";

// Plugins can reference any of these by name in their manifest — keeps bundle
// size sane vs. importing the full lucide-react set.
const ICON_MAP: Record<string, React.ComponentType<{ className?: string }>> = {
  Activity,
  BarChart3,
  Clock,
  FileText,
  KeyRound,
  MessageSquare,
  Package,
  Settings,
  Puzzle,
  Sparkles,
  Terminal,
  Globe,
  Database,
  Shield,
  Wrench,
  Zap,
  Heart,
  Star,
  Code,
  Eye,
  Users,
  Bot,
  ArrowLeftRight,
  BriefcaseBusiness,
  GitBranch,
  Timer,
  AlertOctagon,
  Hand,
};

function resolveIcon(
  name: string,
): React.ComponentType<{ className?: string }> {
  return ICON_MAP[name] ?? Puzzle;
}

function buildNavItems(
  builtIn: NavItem[],
  plugins: RegisteredPlugin[],
): NavItem[] {
  const items = [...builtIn];

  for (const { manifest } of plugins) {
    const pluginItem: NavItem = {
      path: manifest.tab.path,
      label: manifest.label,
      icon: resolveIcon(manifest.icon),
    };

    const pos = manifest.tab.position ?? "end";
    if (pos === "end") {
      items.push(pluginItem);
    } else if (pos.startsWith("after:")) {
      // Plugin manifests may reference old top-level paths (e.g. "after:/status").
      // Migrate to the space-scoped path so we can find the right insertion point.
      const target =
        migrateOldPath("/" + pos.slice(6)) ?? "/" + pos.slice(6);
      const idx = items.findIndex((i) => i.path === target);
      items.splice(idx >= 0 ? idx + 1 : items.length, 0, pluginItem);
    } else if (pos.startsWith("before:")) {
      const target =
        migrateOldPath("/" + pos.slice(7)) ?? "/" + pos.slice(7);
      const idx = items.findIndex((i) => i.path === target);
      items.splice(idx >= 0 ? idx : items.length, 0, pluginItem);
    } else {
      items.push(pluginItem);
    }
  }

  return items;
}

export default function App() {
  const { t } = useI18n();
  const { plugins } = usePlugins();
  const location = useLocation();
  const navigate = useNavigate();

  // Redirect legacy top-level paths to their space-scoped equivalents
  // (e.g. /status → /ops, /business-portal/teams → /portal/teams).
  // Skips paths that are already under /portal, /ops, or /noc.
  useEffect(() => {
    const target = migrateOldPath(location.pathname);
    if (target && target !== location.pathname) {
      navigate(target, { replace: true });
    }
  }, [location.pathname, navigate]);

  // Persist the active space whenever the user enters one.
  useEffect(() => {
    if (location.pathname.startsWith(SPACE_PATHS.portal)) rememberSpace("portal");
    else if (location.pathname.startsWith(SPACE_PATHS.ops)) rememberSpace("ops");
    else if (location.pathname.startsWith(SPACE_PATHS.noc)) rememberSpace("noc");
  }, [location.pathname]);

  // Compute the active space from the URL.
  const activeSpace: SpaceName =
    pathToSpace(location.pathname) ?? resolveDefaultSpace();

  // Plugin manifests augment the active space's nav (legacy BUILTIN_NAV flow).
  const builtinItems = useMemo(() => {
    const nav = getNavForSpace(activeSpace);
    // For flat (Portal, NOC) use flat; for grouped (Ops) use flat
    return nav.flat;
  }, [activeSpace]);

  const navItems = useMemo(
    () => buildNavItems(builtinItems, plugins),
    [builtinItems, plugins],
  );

  return (
    <SpaceLayout>
      <div className="text-midground font-mondwest bg-black min-h-screen flex flex-col uppercase antialiased overflow-x-hidden">
        <SpaceDecorations space={activeSpace} />
        <SpaceThemeBridge activeSpace={activeSpace} />
        <SelectionSwitcher />
        <Backdrop />

      <header
        className={cn(
          "fixed top-0 left-0 right-0 z-40",
          "border-b border-current/20",
          "bg-background-base/90 backdrop-blur-sm",
        )}
      >
        <div className="mx-auto flex h-12 max-w-[1600px]">
          <div className="min-w-0 flex-1 overflow-x-auto scrollbar-none">
            <Grid
              className="h-full !border-t-0 !border-b-0"
              style={{
                gridTemplateColumns: `auto repeat(${navItems.length}, auto)`,
              }}
            >
              <Cell className="flex items-center !p-0 !px-3 sm:!px-5">
                <Typography
                  className="font-bold text-[1.0625rem] sm:text-[1.125rem] leading-[0.95] tracking-[0.0525rem] text-midground blend-lighter"
                >
                  Hermes
                  <br />
                  Agent
                </Typography>
              </Cell>

              <SpaceNav
                flat={navItems.map((i) => ({ path: i.path, label: i.label, labelKey: i.labelKey, icon: i.icon as any }))}
                templateColumns={`auto repeat(${navItems.length}, auto)`}
              />
            </Grid>
          </div>

          <Grid className="h-full shrink-0 !border-t-0 !border-b-0">
            <Cell className="flex items-center gap-2 !p-0 !px-2 sm:!px-4">
              <SpaceSwitcher activeSpace={activeSpace} />
              <ThemeSwitcher />
              <LanguageSwitcher />
              <Typography
                mondwest
                className="hidden sm:inline text-[0.7rem] tracking-[0.15em] opacity-50"
              >
                {t.app.webUi}
              </Typography>
            </Cell>
          </Grid>
        </div>
      </header>

      <main className="relative z-2 mx-auto w-full max-w-[1600px] flex-1 px-3 sm:px-6 pt-16 sm:pt-20 pb-4 sm:pb-8">
        <Suspense fallback={<PageLoading />}>
          <Routes>
          <Route path="/" element={<RootRedirect />} />
          {/* ── Three-space refactor: /portal /ops /noc ─────────────── */}
          <Route path="/portal" element={<BusinessPortalStandalonePage><BusinessPortalHome /></BusinessPortalStandalonePage>} />
          <Route path="/portal/workspaces" element={<BusinessPortalStandalonePage />} />
          <Route path="/portal/agents" element={<BusinessPortalStandalonePage><AgentMarketPage /></BusinessPortalStandalonePage>} />
          <Route path="/portal/templates" element={<BusinessPortalStandalonePage><TemplateGalleryPage /></BusinessPortalStandalonePage>} />
          <Route path="/portal/approvals" element={<BusinessPortalStandalonePage><ApprovalsPage /></BusinessPortalStandalonePage>} />
          <Route path="/portal/risk-policy" element={<BusinessPortalStandalonePage><RiskPolicyPage /></BusinessPortalStandalonePage>} />
          <Route path="/portal/evolution" element={<BusinessPortalStandalonePage><EvolutionPanelPage /></BusinessPortalStandalonePage>} />
          <Route path="/portal/my-templates" element={<BusinessPortalStandalonePage><MyTemplatesPage /></BusinessPortalStandalonePage>} />
          <Route path="/portal/industry-dashboard" element={<BusinessPortalStandalonePage><IndustryDashboardPage /></BusinessPortalStandalonePage>} />
          <Route path="/portal/runs/:workspaceId/:runId" element={<RunDetailPage />} />

          <Route path="/ops" element={<StatusPage />} />
          <Route path="/ops/playground" element={<PlaygroundPage />} />
          <Route path="/ops/compare" element={<ComparePage />} />
          <Route path="/ops/sessions" element={<SessionsPage />} />
          <Route path="/ops/analytics" element={<AnalyticsPage />} />
          <Route path="/ops/logs" element={<LogsPage />} />
          <Route path="/ops/cron" element={<CronPage />} />
          <Route path="/ops/skills" element={<SkillsPage />} />
          <Route path="/ops/tools" element={<ToolsPage />} />
          <Route path="/ops/tenants" element={<TenantsPage />} />
          <Route path="/ops/config" element={<ConfigPage />} />
          <Route path="/ops/env" element={<EnvPage />} />
          <Route path="/ops/org" element={<OrgPage />} />

          <Route path="/noc" element={<OrgControlCenterPage />} />
          <Route path="/noc/traces/:traceId" element={<TraceDetailPage />} />
          <Route path="/noc/workflows" element={<WorkflowPage />} />
          <Route path="/noc/sla" element={<SLAPage />} />
          <Route path="/noc/dlq" element={<DLQPage />} />
          <Route path="/noc/hitl" element={<HumanInTheLoopPage />} />
          <Route path="/playground" element={<PlaygroundPage />} />
          <Route path="/compare" element={<ComparePage />} />
          <Route path="/sessions" element={<SessionsPage />} />
          <Route path="/analytics" element={<AnalyticsPage />} />
          <Route path="/logs" element={<LogsPage />} />
          <Route path="/cron" element={<CronPage />} />
          <Route path="/skills" element={<SkillsPage />} />
          <Route path="/tools" element={<ToolsPage />} />
          <Route path="/tenants" element={<TenantsPage />} />
          <Route path="/business" element={<Navigate to="/portal" replace />} />
          {/* /business-portal/* — all redirect to /portal/* via migrateOldPath. */}
          <Route path="/business-portal" element={<Navigate to="/portal" replace />} />
          <Route path="/business-portal/*" element={<Navigate to="/portal" replace />} />
          <Route path="/traces/:traceId" element={<TraceDetailPage />} />
          <Route path="/runs/:workspaceId/:runId" element={<Navigate to="/portal" replace />} />
          <Route path="/config" element={<ConfigPage />} />
          <Route path="/env" element={<EnvPage />} />

          <Route path="/org" element={<OrgPage />} />
          <Route path="/org-manage" element={<Navigate to="/portal" replace />} />
          <Route path="/org-control" element={<OrgControlCenterPage />} />
          <Route path="/workflows" element={<WorkflowPage />} />
          <Route path="/sla" element={<SLAPage />} />
          <Route path="/dlq" element={<DLQPage />} />
          <Route path="/hitl" element={<HumanInTheLoopPage />} />
          {plugins.map(({ manifest, component: PluginComponent }) => (
            <Route
              key={manifest.name}
              path={manifest.tab.path}
              element={<PluginComponent />}
            />
          ))}

          <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </Suspense>
      </main>

      <footer className="relative z-2 border-t border-current/20">
        <Grid className="mx-auto max-w-[1600px] !border-t-0 !border-b-0">
          <Cell className="flex items-center !px-3 sm:!px-6 !py-3">
            <Typography
              mondwest
              className="text-[0.7rem] sm:text-[0.8rem] tracking-[0.12em] opacity-60"
            >
              {t.app.footer.name}
            </Typography>
          </Cell>
          <Cell className="flex items-center justify-end !px-3 sm:!px-6 !py-3">
            <Typography
              mondwest
              className="text-[0.6rem] sm:text-[0.7rem] tracking-[0.15em] text-midground blend-lighter"
            >
              {t.app.footer.org}
            </Typography>
          </Cell>
        </Grid>
      </footer>
      </div>
    </SpaceLayout>
  );
}

function PageLoading() {
  return (
    <div className="flex h-64 items-center justify-center text-sm tracking-[0.12em] opacity-70">
      Loading...
    </div>
  );
}

interface NavItem {
  icon: React.ComponentType<{ className?: string }>;
  label: string;
  labelKey?: string;
  path: string;
}
