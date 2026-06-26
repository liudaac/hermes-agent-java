import { lazy, Suspense, useMemo } from "react";
import { Routes, Route, NavLink, Navigate } from "react-router-dom";
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
  Monitor,
  Users,
  Bot,
  Layout,
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

const BUILTIN_NAV: NavItem[] = [
  { path: "/", labelKey: "status", label: "Status", icon: Activity },
  {
    path: "/playground",
    labelKey: "playground",
    label: "Playground",
    icon: Bot,
  },
  {
    path: "/compare",
    labelKey: "compare",
    label: "Compare",
    icon: ArrowLeftRight,
  },
  {
    path: "/sessions",
    labelKey: "sessions",
    label: "Sessions",
    icon: MessageSquare,
  },
  {
    path: "/analytics",
    labelKey: "analytics",
    label: "Analytics",
    icon: BarChart3,
  },
  { path: "/logs", labelKey: "logs", label: "Logs", icon: FileText },
  { path: "/cron", labelKey: "cron", label: "Cron", icon: Clock },
  { path: "/skills", labelKey: "skills", label: "Skills", icon: Package },
  { path: "/tools", labelKey: "tools", label: "Tools", icon: Wrench },
  { path: "/tenants", labelKey: "tenants", label: "Tenants", icon: Users },
  { path: "/org", labelKey: "orgOverview", label: "Org Overview", icon: Layout },
  { path: "/org-control", labelKey: "orgControl", label: "Org Control", icon: Monitor },
  { path: "/config", labelKey: "config", label: "Config", icon: Settings },
  { path: "/env", labelKey: "keys", label: "Keys", icon: KeyRound },
  { path: "/workflows", label: "Workflows", icon: GitBranch },
  { path: "/sla", label: "SLA", icon: Timer },
  { path: "/dlq", label: "DLQ", icon: AlertOctagon },
  { path: "/hitl", label: "Human Loop", icon: Hand },
];

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
      const target = "/" + pos.slice(6);
      const idx = items.findIndex((i) => i.path === target);
      items.splice(idx >= 0 ? idx + 1 : items.length, 0, pluginItem);
    } else if (pos.startsWith("before:")) {
      const target = "/" + pos.slice(7);
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

  const navItems = useMemo(
    () => buildNavItems(BUILTIN_NAV, plugins),
    [plugins],
  );

  return (
    <div className="text-midground font-mondwest bg-black min-h-screen flex flex-col uppercase antialiased overflow-x-hidden">
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

              {navItems.map(({ path, label, labelKey, icon: Icon }) => (
                <Cell key={path} className="relative !p-0">
                  <NavLink
                    to={path}
                    end={path === "/"}
                    className={({ isActive }) =>
                      cn(
                        "group relative flex h-full w-full items-center gap-1.5",
                        "px-2.5 sm:px-4 py-2",
                        "font-mondwest text-[0.65rem] sm:text-[0.8rem] tracking-[0.12em]",
                        "whitespace-nowrap transition-colors cursor-pointer",
                        "focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-midground",
                        isActive
                          ? "text-midground"
                          : "opacity-60 hover:opacity-100",
                      )
                    }
                  >
                    {({ isActive }) => (
                      <>
                        <Icon className="h-3.5 w-3.5 shrink-0" />
                        <span className="hidden sm:inline">
                          {labelKey
                            ? ((t.app.nav as Record<string, string>)[
                                labelKey
                              ] ?? label)
                            : label}
                        </span>

                        <span
                          aria-hidden
                          className="absolute inset-1 bg-midground opacity-0 pointer-events-none transition-opacity duration-200 group-hover:opacity-5"
                        />

                        {isActive && (
                          <span
                            aria-hidden
                            className="absolute bottom-0 left-0 right-0 h-px bg-midground blend-lighter"
                          />
                        )}
                      </>
                    )}
                  </NavLink>
                </Cell>
              ))}
            </Grid>
          </div>

          <Grid className="h-full shrink-0 !border-t-0 !border-b-0">
            <Cell className="flex items-center gap-2 !p-0 !px-2 sm:!px-4">
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
          <Route path="/" element={<StatusPage />} />
          <Route path="/playground" element={<PlaygroundPage />} />
          <Route path="/compare" element={<ComparePage />} />
          <Route path="/sessions" element={<SessionsPage />} />
          <Route path="/analytics" element={<AnalyticsPage />} />
          <Route path="/logs" element={<LogsPage />} />
          <Route path="/cron" element={<CronPage />} />
          <Route path="/skills" element={<SkillsPage />} />
          <Route path="/tools" element={<ToolsPage />} />
          <Route path="/tenants" element={<TenantsPage />} />
          <Route path="/business" element={<Navigate to="/business-portal" replace />} />
          <Route path="/business-portal" element={<BusinessPortalStandalonePage><BusinessPortalHome /></BusinessPortalStandalonePage>} />
          <Route path="/business-portal/workspaces" element={<BusinessPortalStandalonePage />} />
          <Route path="/business-portal/agents" element={<BusinessPortalStandalonePage><AgentMarketPage /></BusinessPortalStandalonePage>} />
          <Route path="/business-portal/templates" element={<BusinessPortalStandalonePage><TemplateGalleryPage /></BusinessPortalStandalonePage>} />
          <Route path="/business-portal/approvals" element={<BusinessPortalStandalonePage><ApprovalsPage /></BusinessPortalStandalonePage>} />
          <Route path="/runs/:workspaceId/:runId" element={<RunDetailPage />} />
          <Route path="/config" element={<ConfigPage />} />
          <Route path="/env" element={<EnvPage />} />

          <Route path="/org" element={<OrgPage />} />
          <Route path="/org-manage" element={<Navigate to="/business-portal" replace />} />
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
