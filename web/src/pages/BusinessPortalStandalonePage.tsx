import { useEffect, useState, type ReactNode } from "react";
import { useNavigate } from "react-router-dom";
import {
  ArrowLeft,
  BriefcaseBusiness,
  LayoutDashboard,
  Menu,
  X,
  Sparkles,
  Layers,
  ShieldAlert,
  Home,
  Lightbulb,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import BusinessPortalPage from "@/pages/BusinessPortalPage";
import FloatingBusinessChat from "@/components/business/FloatingBusinessChat";

/**
 * Business Portal Standalone — Independent portal page without Dashboard chrome.
 *
 * Features:
 * - Clean header with workspace identity
 * - Collapsible mobile navigation
 * - No Dashboard top-tab navigation (isolated experience)
 * - Back-link to Technical Dashboard
 */
export default function BusinessPortalStandalonePage({ children }: { children?: ReactNode }) {
  const navigate = useNavigate();
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const [scrolled, setScrolled] = useState(false);

  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 8);
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => window.removeEventListener("scroll", onScroll);
  }, []);

  return (
    <div className="min-h-screen bg-background text-foreground">
      {/* Sticky Header */}
      <header
        className={cn(
          "fixed top-0 left-0 right-0 z-50 transition-all duration-200",
          scrolled
            ? "bg-background/95 backdrop-blur-md border-b border-border shadow-sm"
            : "bg-transparent"
        )}
      >
        <div className="mx-auto flex h-14 max-w-[1400px] items-center justify-between px-4 sm:px-6">
          {/* Left: Brand + Back */}
          <div className="flex items-center gap-3">
            <Button
              variant="ghost"
              size="sm"
              className="hidden sm:flex items-center gap-1.5 text-xs tracking-wide"
              onClick={() => navigate("/")}
            >
              <ArrowLeft className="h-3.5 w-3.5" />
              Dashboard
            </Button>
            <div className="h-4 w-px bg-border hidden sm:block" />
            <div className="flex items-center gap-2">
              <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-primary/10">
                <BriefcaseBusiness className="h-4 w-4 text-primary" />
              </div>
              <div>
                <h1 className="text-sm font-semibold leading-tight tracking-tight">
                  Business Portal
                </h1>
                <p className="text-[0.65rem] text-muted-foreground leading-tight">
                  Agent Team Platform
                </p>
              </div>
            </div>
          </div>

          {/* Right: Actions */}
          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              className="hidden sm:flex items-center gap-1.5 text-xs"
              onClick={() => navigate("/")}
            >
              <LayoutDashboard className="h-3.5 w-3.5" />
              Tech Dashboard
            </Button>

            {/* Mobile menu toggle */}
            <Button
              variant="ghost"
              size="icon"
              className="sm:hidden"
              onClick={() => setMobileMenuOpen(!mobileMenuOpen)}
            >
              {mobileMenuOpen ? (
                <X className="h-5 w-5" />
              ) : (
                <Menu className="h-5 w-5" />
              )}
            </Button>
          </div>
        </div>

        {/* Mobile nav drawer */}
        {mobileMenuOpen && (
          <div className="sm:hidden border-t border-border bg-background px-4 py-3 space-y-2">
            <Button
              variant="ghost"
              size="sm"
              className="w-full justify-start gap-2"
              onClick={() => {
                navigate("/");
                setMobileMenuOpen(false);
              }}
            >
              <ArrowLeft className="h-4 w-4" />
              Back to Dashboard
            </Button>
            <Button
              variant="outline"
              size="sm"
              className="w-full justify-start gap-2"
              onClick={() => {
                navigate("/");
                setMobileMenuOpen(false);
              }}
            >
              <LayoutDashboard className="h-4 w-4" />
              Technical Dashboard
            </Button>
          </div>
        )}
      </header>

      {/* Main Content */}
      <main className="mx-auto max-w-[1400px] px-4 sm:px-6 pt-20 pb-12">
        <div className="mb-4 flex flex-wrap gap-2 border-b border-border pb-3">
          <NavTab to="/business-portal" label="首页" icon={Home} exact />
          <NavTab to="/business-portal/agents" label="数字员工" icon={Sparkles} />
          <NavTab to="/business-portal/templates" label="场景模板" icon={Layers} />
          <NavTab to="/business-portal/workspaces" label="工作台" icon={BriefcaseBusiness} />
          <NavTab to="/business-portal/approvals" label="待审批" icon={ShieldAlert} />
          <NavTab to="/business-portal/risk-policy" label="风险策略" icon={ShieldAlert} />
          <NavTab to="/business-portal/evolution" label="自进化" icon={Lightbulb} />
        </div>
        {children ?? <BusinessPortalPage />}
      </main>
      <FloatingBusinessChat />

      {/* Minimal footer */}
      <footer className="border-t border-border bg-muted/30">
        <div className="mx-auto max-w-[1400px] px-4 sm:px-6 py-4 flex items-center justify-between text-xs text-muted-foreground">
          <span>Hermes Agent — Business Portal</span>
          <Button
            variant="link"
            size="sm"
            className="h-auto p-0 text-xs text-muted-foreground hover:text-foreground"
            onClick={() => navigate("/")}
          >
            ← Back to Technical Dashboard
          </Button>
        </div>
      </footer>
    </div>
  );
}

function NavTab({ to, label, icon: Icon, exact }: { to: string; label: string; icon: typeof BriefcaseBusiness; exact?: boolean }) {
  const navigate = useNavigate();
  const path = typeof window !== "undefined" ? window.location.pathname : "";
  const active = exact ? path === to : path === to || path.startsWith(to + "/");
  return (
    <button
      onClick={() => navigate(to)}
      className={cn(
        "inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-medium transition-colors",
        active
          ? "bg-foreground text-background"
          : "bg-muted/40 text-muted-foreground hover:text-foreground hover:bg-muted",
      )}
    >
      <Icon className="h-3.5 w-3.5" />
      {label}
    </button>
  );
}
