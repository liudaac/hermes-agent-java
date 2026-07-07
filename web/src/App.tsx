import { useEffect } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { BriefcaseBusiness, TerminalSquare, ShieldCheck, ExternalLink, type LucideIcon } from "lucide-react";
import { forwardToExternalPortal } from "@/lib/routing/spaces";
import { Typography } from "@nous-research/ui";
import { cn } from "@/lib/utils";

interface ProductEntry {
  href: string;
  title: string;
  blurb: string;
  icon: LucideIcon;
  tone: "rose" | "teal" | "amber";
}

const ENTRIES: ProductEntry[] = [
  {
    href: "/portal/index.html",
    title: "Portal",
    blurb: "业务前店 · 数字员工 · 场景模板 · 待审批",
    icon: BriefcaseBusiness,
    tone: "rose",
  },
  {
    href: "/ops/index.html",
    title: "Ops",
    blurb: "平台控制台 · 多租户 · Sessions · Logs · 配置",
    icon: TerminalSquare,
    tone: "teal",
  },
  {
    href: "/noc/index.html",
    title: "NOC",
    blurb: "Org Control Center · Workflow · DLQ · SLA · 告警",
    icon: ShieldCheck,
    tone: "amber",
  },
];

/**
 * Hub — landing page for the root URL.
 *
 * Renders a simple three-card grid: each card is a full-page link
 * to one of the three independent SPAs (Portal / Ops / NOC).
 *
 * No routes other than `/` are handled by this app. Every other path
 * is forwarded to the appropriate SPA via `forwardToExternalPortal`
 * or via the dev-server proxy in development.
 */
export default function App() {
  const location = useLocation();
  const navigate = useNavigate();

  // Forward any non-root path to the matching external SPA.
  useEffect(() => {
    if (location.pathname === "/") return;
    // Preserve query/hash on the way out.
    const suffix = location.search + location.hash;
    if (location.pathname.startsWith("/portal/") || location.pathname === "/portal"
        || location.pathname === "/business-portal" || location.pathname.startsWith("/business-portal/")
        || location.pathname === "/business") {
      const rest = location.pathname.startsWith("/portal/")
        ? location.pathname.slice("/portal".length)
        : "";
      window.location.replace(`/portal/index.html${rest}${suffix}`);
      return;
    }
    if (location.pathname.startsWith("/ops/") || location.pathname === "/ops"
        || location.pathname === "/status" || location.pathname.startsWith("/org-manage")) {
      const rest = location.pathname.startsWith("/ops/")
        ? location.pathname.slice("/ops".length)
        : location.pathname === "/ops" ? "" : location.pathname;
      window.location.replace(`/ops/index.html${rest}${suffix}`);
      return;
    }
    if (location.pathname.startsWith("/noc/") || location.pathname === "/noc"
        || location.pathname === "/org-control" || location.pathname.startsWith("/traces/")) {
      const rest = location.pathname.startsWith("/noc/")
        ? location.pathname.slice("/noc".length)
        : "";
      window.location.replace(`/noc/index.html${rest}${suffix}`);
      return;
    }
    // Unknown path — let forwardToExternalPortal handle portal/business.
    if (forwardToExternalPortal(location.pathname)) return;
    // Final fallback: send back to root.
    navigate("/", { replace: true });
  }, [location.pathname, location.search, location.hash, navigate]);

  if (location.pathname !== "/") {
    return <ForwardingScreen />;
  }

  return (
    <div className="min-h-screen bg-background text-foreground antialiased">
      <div className="relative mx-auto flex min-h-screen w-full max-w-5xl flex-col items-center justify-center px-4 py-12 sm:py-20">
        <header className="mb-12 text-center">
          <Typography
            className="font-mondwest font-bold tracking-[0.15rem] text-[0.7rem] sm:text-[0.8rem] uppercase text-midground blend-lighter opacity-70"
          >
            Hermes Agent
          </Typography>
          <h1 className="mt-3 font-display text-[32px] sm:text-[44px] font-medium leading-[1.05] text-foreground">
            三个产品 · 一个入口
          </h1>
          <p className="mt-3 text-[14px] sm:text-[15px] text-muted-foreground">
            选一个进入 — 每个入口都是独立的 Web App，跨产品跳转是整页跳。
          </p>
        </header>

        <div className="grid w-full grid-cols-1 gap-4 sm:grid-cols-3 sm:gap-5">
          {ENTRIES.map((e) => (
            <a
              key={e.href}
              href={e.href}
              className="group relative flex h-full flex-col gap-3 rounded-2xl border border-current/15 bg-card p-5 sm:p-6 transition-all hover:border-current/35 hover:translate-y-[-2px] active:scale-[0.99]"
            >
              <div
                className={cn(
                  "flex h-10 w-10 items-center justify-center rounded-xl",
                  e.tone === "rose" && "bg-[oklch(0.78_0.16_70_/_0.18)] text-[oklch(0.95_0.10_70)]",
                  e.tone === "teal" && "bg-[oklch(0.72_0.14_180_/_0.18)] text-[oklch(0.92_0.10_180)]",
                  e.tone === "amber" && "bg-[oklch(0.78_0.16_85_/_0.18)] text-[oklch(0.95_0.10_85)]",
                )}
              >
                <e.icon className="h-5 w-5" />
              </div>
              <div className="flex flex-col gap-1">
                <h2 className="font-mondwest text-[0.95rem] font-bold tracking-[0.18em] uppercase text-foreground">
                  {e.title}
                </h2>
                <p className="text-[12px] leading-relaxed text-muted-foreground">
                  {e.blurb}
                </p>
              </div>
              <div className="mt-auto inline-flex items-center gap-1 text-[11px] tracking-[0.15em] text-muted-foreground group-hover:text-foreground">
                打开
                <ExternalLink className="h-3 w-3" />
              </div>
            </a>
          ))}
        </div>

        <footer className="mt-12 text-center text-[10px] tracking-[0.18em] uppercase text-muted-foreground opacity-60">
          Hermes Agent · 三个独立 SPA · ops 5176 · noc 5177 · portal 5175
        </footer>
      </div>
    </div>
  );
}

function ForwardingScreen() {
  return (
    <div className="flex min-h-screen items-center justify-center bg-background text-foreground">
      <div className="text-center text-[12px] tracking-[0.15em] uppercase text-muted-foreground">
        Loading...
      </div>
    </div>
  );
}
