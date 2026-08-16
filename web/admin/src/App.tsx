import { lazy, Suspense } from "react";
import { Routes, Route, useLocation, NavLink } from "react-router-dom";
import { JarvisCore } from "@hermes/jarvis";
import { ADMIN_NAV, CROSS_PRODUCT_LINKS } from "@/lib/nav";

const OrgOverview = lazy(() => import("@/pages/OrgOverview"));
const Tenants = lazy(() => import("@/pages/Tenants"));
const Spaces = lazy(() => import("@/pages/Spaces"));
const Users = lazy(() => import("@/pages/Users"));
const ApprovalPolicy = lazy(() => import("@/pages/ApprovalPolicy"));
const Delegation = lazy(() => import("@/pages/Delegation"));
const Evolution = lazy(() => import("@/pages/Evolution"));
const Billing = lazy(() => import("@/pages/Billing"));
const Audit = lazy(() => import("@/pages/Audit"));
const Models = lazy(() => import("@/pages/Models"));

const GROUP_LABELS: Record<string, string> = {
  governance: "组织治理",
  control: "管控中心",
  billing: "成本与合规",
};

export function AdminApp() {
  const location = useLocation();
  const groups = ["governance", "control", "billing"] as const;

  return (
    <div className="min-h-screen bg-background text-foreground">
      {/* Top bar */}
      <header className="sticky top-0 z-30 border-b border-border bg-surface/80 backdrop-blur-md">
        <div className="mx-auto flex h-14 w-full max-w-[1600px] items-center justify-between px-6">
          <div className="flex items-center gap-3">
            <span className="text-sm font-semibold tracking-tight">Hermes Admin</span>
            <span className="text-[10px] font-medium uppercase tracking-wider text-muted">
              组织管理
            </span>
          </div>
          <nav className="flex items-center gap-1 text-xs">
            <a href={CROSS_PRODUCT_LINKS.portal} className="rounded-md px-3 py-1.5 text-muted transition-colors hover:bg-surface-hover hover:text-foreground">
              Portal
            </a>
            <a href={CROSS_PRODUCT_LINKS.ops} className="rounded-md px-3 py-1.5 text-muted transition-colors hover:bg-surface-hover hover:text-foreground">
              Ops
            </a>
            <a href={CROSS_PRODUCT_LINKS.devportal} className="rounded-md px-3 py-1.5 text-muted transition-colors hover:bg-surface-hover hover:text-foreground">
              Dev
            </a>
          </nav>
        </div>
      </header>

      <div className="mx-auto flex w-full max-w-[1600px]">
        {/* Sidebar */}
        <aside className="sticky top-14 h-[calc(100vh-3.5rem)] w-56 shrink-0 border-r border-border bg-surface/50 py-4">
          <nav className="flex flex-col gap-5 px-3">
            {groups.map((group) => (
              <div key={group}>
                <div className="mb-1.5 px-3 text-[10px] font-semibold uppercase tracking-wider text-muted">
                  {GROUP_LABELS[group]}
                </div>
                <div className="flex flex-col gap-0.5">
                  {ADMIN_NAV.filter((item) => item.group === group).map((item) => {
                    const Icon = item.icon;
                    return (
                      <NavLink
                        key={item.path}
                        to={item.path}
                        end={item.path === "/"}
                        className={({ isActive }) =>
                          `flex items-center gap-2.5 rounded-md px-3 py-1.5 text-[13px] transition-colors ${
                            isActive
                              ? "bg-accent/10 font-medium text-accent-foreground"
                              : "text-foreground/70 hover:bg-surface-hover hover:text-foreground"
                          }`
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

        {/* Content */}
        <main className="flex-1 overflow-x-hidden px-6 py-6">
          <Suspense
            fallback={
              <div className="flex h-64 items-center justify-center text-sm text-muted">
                Loading...
              </div>
            }
          >
            <Routes>
              <Route path="/" element={<OrgOverview />} />
              <Route path="/tenants" element={<Tenants />} />
              <Route path="/spaces" element={<Spaces />} />
              <Route path="/users" element={<Users />} />
              <Route path="/approvals" element={<ApprovalPolicy />} />
              <Route path="/delegation" element={<Delegation />} />
              <Route path="/evolution" element={<Evolution />} />
              <Route path="/billing" element={<Billing />} />
              <Route path="/audit" element={<Audit />} />
              <Route path="/models" element={<Models />} />
              <Route path="*" element={<OrgOverview />} />
            </Routes>
          </Suspense>
        </main>
      </div>

      <JarvisCore />
    </div>
  );
}
