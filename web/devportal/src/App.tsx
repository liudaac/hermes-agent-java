import { lazy, Suspense } from "react";
import { Routes, Route, NavLink } from "react-router-dom";
import { JarvisCore } from "@hermes/jarvis";
import { ToastProvider } from "@hermes/ui";
import { I18nProvider, useI18n } from "@/i18n";

const ApiBrowser = lazy(() => import("@/pages/ApiBrowser"));
const Webhooks = lazy(() => import("@/pages/Webhooks"));
const OAuth = lazy(() => import("@/pages/OAuth"));
const Integration = lazy(() => import("@/pages/Integration"));
const EnvVars = lazy(() => import("@/pages/EnvVars"));

function Nav() {
  const { t } = useI18n();
  const NAV = [
    { path: "/", label: t.api.title, end: true },
    { path: "/webhooks", label: t.webhooks.title },
    { path: "/oauth", label: t.oauth.title },
    { path: "/integration", label: t.integration.title },
    { path: "/env", label: t.env.llmProviders },
  ];
  return (
    <nav className="flex flex-col gap-0.5 px-3">
      {NAV.map((item) => (
        <NavLink
          key={item.path}
          to={item.path}
          end={item.end}
          className={({ isActive }) =>
            `rounded-md px-3 py-1.5 text-[13px] font-mono transition-colors ${
              isActive
                ? "bg-accent/10 text-accent-foreground"
                : "text-muted hover:bg-surface-hover hover:text-foreground"
            }`
          }
        >
          {item.label}
        </NavLink>
      ))}
    </nav>
  );
}

export function DevPortalApp() {
  return (
    <I18nProvider>
      <ToastProvider>
        <div className="min-h-screen bg-background text-foreground">
          {/* Top bar */}
          <header className="sticky top-0 z-30 border-b border-border bg-surface/80 backdrop-blur-md">
            <div className="mx-auto flex h-14 w-full max-w-[1400px] items-center justify-between px-6">
              <div className="flex items-center gap-3">
                <span className="font-mono text-sm font-semibold text-accent">{"</>"}</span>
                <span className="text-sm font-semibold tracking-tight">DevPortal</span>
              </div>
              <nav className="flex items-center gap-1 text-xs">
                <a href="/portal/index.html" className="rounded-md px-3 py-1.5 text-muted transition-colors hover:bg-surface-hover hover:text-foreground">Portal</a>
                <a href="/ops/index.html" className="rounded-md px-3 py-1.5 text-muted transition-colors hover:bg-surface-hover hover:text-foreground">Ops</a>
                <a href="/admin/index.html" className="rounded-md px-3 py-1.5 text-muted transition-colors hover:bg-surface-hover hover:text-foreground">Admin</a>
              </nav>
            </div>
          </header>

          <div className="mx-auto flex w-full max-w-[1400px]">
            {/* Sidebar */}
            <aside className="sticky top-14 h-[calc(100vh-3.5rem)] w-52 shrink-0 border-r border-border bg-surface/30 py-4">
              <Nav />
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
                  <Route path="/" element={<ApiBrowser />} />
                  <Route path="/webhooks" element={<Webhooks />} />
                  <Route path="/oauth" element={<OAuth />} />
                  <Route path="/integration" element={<Integration />} />
                  <Route path="/env" element={<EnvVars />} />
                  <Route path="*" element={<ApiBrowser />} />
                </Routes>
              </Suspense>
            </main>
          </div>

          <JarvisCore />
        </div>
      </ToastProvider>
    </I18nProvider>
  );
}
