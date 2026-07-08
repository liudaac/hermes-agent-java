import { useEffect } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { BriefcaseBusiness, TerminalSquare, ShieldCheck, ExternalLink, type LucideIcon } from "lucide-react";
import { Typography } from "@nous-research/ui";

/**
 * Cross-product forward table. Maps a URL on the root dashboard to the
 * matching independent SPA's `index.html` + the same rest.
 *
 * The rule: any path that starts with a known product prefix (or a
 * legacy alias) is delegated to that product's SPA via a full-page
 * navigation. Anything else (the only remaining valid path is `/`)
 * renders the hub.
 */
const PRODUCT_FORWARDS: Array<{ from: string[]; to: string }> = [
  { from: ["/portal", "/portal/", "/business", "/business-portal", "/business-portal/"], to: "/portal/index.html" },
  { from: ["/ops", "/ops/", "/status", "/playground", "/compare",
            "/sessions", "/analytics", "/logs", "/cron", "/skills",
            "/tools", "/tenants", "/config", "/env", "/org",
            "/org-manage"], to: "/ops/index.html" },
  { from: ["/noc", "/noc/", "/org-control", "/workflows", "/sla", "/dlq",
            "/hitl", "/traces/"], to: "/noc/index.html" },
];

/** Match `/traces/:id` and forward to `/noc/traces/:id`. */
const TRACES_RE = /^\/traces\/([^/]+)$/;

function resolveForward(pathname: string): { target: string; rest: string } | null {
  for (const { from, to } of PRODUCT_FORWARDS) {
    for (const prefix of from) {
      if (pathname === prefix) return { target: to, rest: "" };
      if (prefix.endsWith("/") && pathname.startsWith(prefix)) {
        // Strip the prefix and the leading '/'. If the rest already
        // starts with "index.html" (which happens on reload of a deep
        // portal route, since the SPA's entry is /<product>/index.html),
        // we have to stop and not re-forward — the user is already
        // inside the right SPA. Sending them through the forwarder
        // again would append another "/index.html" segment, leading
        // to "/portal/index.html/index.html/..." on every reload.
        const rest = pathname.slice(prefix.length);
        if (rest.startsWith("index.html")) return null;
        return { target: to, rest: "/" + rest };
      }
    }
  }
  const m = pathname.match(TRACES_RE);
  if (m) return { target: "/noc/index.html", rest: `/traces/${m[1]}` };
  return null;
}

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
 * is forwarded to the matching SPA via a full-page navigation
 * (window.location.replace), preserving the rest of the path and any
 * query / hash.
 */
export default function App() {
  const location = useLocation();
  const navigate = useNavigate();

  useEffect(() => {
    if (location.pathname === "/") return;
    const fwd = resolveForward(location.pathname);
    const suffix = location.search + location.hash;
    if (fwd) {
      window.location.replace(`${fwd.target}${fwd.rest}${suffix}`);
      return;
    }
    // Unknown path on the root dashboard — send back to /.
    navigate("/", { replace: true });
  }, [location.pathname, location.search, location.hash, navigate]);

  if (location.pathname !== "/") {
    return <ForwardingScreen />;
  }

  return (
    <div className="min-h-screen bg-background text-foreground antialiased">
      <div className="relative mx-auto flex min-h-screen w-full max-w-5xl flex-col items-center justify-center px-4 py-12 sm:py-20">
        <header className="mb-12 text-center">
          <Typography className="font-mondwest font-bold tracking-[0.15rem] text-[0.7rem] sm:text-[0.8rem] uppercase text-midground blend-lighter opacity-70">
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
                className={[
                  "flex h-10 w-10 items-center justify-center rounded-xl",
                  e.tone === "rose" && "bg-[oklch(0.78_0.16_70_/_0.18)] text-[oklch(0.95_0.10_70)]",
                  e.tone === "teal" && "bg-[oklch(0.72_0.14_180_/_0.18)] text-[oklch(0.92_0.10_180)]",
                  e.tone === "amber" && "bg-[oklch(0.78_0.16_85_/_0.18)] text-[oklch(0.95_0.10_85)]",
                ].filter(Boolean).join(" ")}
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
