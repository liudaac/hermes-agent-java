import { useEffect } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { BriefcaseBusiness, TerminalSquare, Shield, Code2, ArrowRight } from "lucide-react";

const PRODUCT_FORWARDS: Array<{ from: string[]; to: string }> = [
  { from: ["/portal", "/portal/", "/business", "/business-portal", "/business-portal/"], to: "/portal/index.html" },
  { from: ["/ops", "/ops/", "/status", "/playground", "/compare",
            "/sessions", "/analytics", "/logs", "/cron",
            "/tools", "/dlq", "/workflows", "/hitl",
            "/traces/"], to: "/ops/index.html" },
  { from: ["/admin", "/admin/", "/tenants", "/spaces", "/users",
            "/org", "/org-manage", "/billing", "/audit",
            "/approvals", "/delegation", "/evolution", "/models"], to: "/admin/index.html" },
  { from: ["/devportal", "/devportal/", "/webhooks", "/oauth",
            "/env", "/integration"], to: "/devportal/index.html" },
  { from: ["/noc", "/noc/", "/org-control", "/sla"], to: "/ops/index.html" },
];

const TRACES_RE = /^\/traces\/([^/]+)$/;

function resolveForward(pathname: string): { target: string; rest: string } | null {
  for (const { from, to } of PRODUCT_FORWARDS) {
    for (const prefix of from) {
      if (pathname === prefix) return { target: to, rest: "" };
      if (prefix.endsWith("/") && pathname.startsWith(prefix)) {
        const rest = pathname.slice(prefix.length);
        if (rest.startsWith("index.html")) return null;
        return { target: to, rest: "/" + rest };
      }
    }
  }
  const m = pathname.match(TRACES_RE);
  if (m) return { target: "/ops/index.html", rest: `/traces/${m[1]}` };
  return null;
}

const PRODUCTS = [
  { href: "/portal/index.html", icon: BriefcaseBusiness, title: "业务门户", subtitle: "数字员工、场景模板、运行管理", color: "#0071e3" },
  { href: "/ops/index.html", icon: TerminalSquare, title: "控制台", subtitle: "会话、日志、追踪、SLA 监控", color: "#34c759" },
  { href: "/admin/index.html", icon: Shield, title: "组织管理", subtitle: "租户、空间、用户、模型路由", color: "#ff9500" },
  { href: "/devportal/index.html", icon: Code2, title: "开发者", subtitle: "API 浏览、环境变量、Webhook", color: "#5ac8fa" },
];

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
    navigate("/", { replace: true });
  }, [location.pathname, location.search, location.hash, navigate]);

  if (location.pathname !== "/") return <ForwardingScreen />;

  return (
    <div className="min-h-screen bg-background text-foreground">
      <header className="border-b border-border">
        <div className="mx-auto flex h-14 max-w-5xl items-center justify-between px-6">
          <span className="text-sm font-semibold tracking-tight">Hermes Agent</span>
          <div className="flex items-center gap-1.5 text-[11px] text-muted-foreground">
            <span className="h-1.5 w-1.5 rounded-full bg-success animate-pulse" />
            运行中
          </div>
        </div>
      </header>

      <main className="mx-auto max-w-5xl px-6 py-16">
        <div className="mb-12">
          <h1 className="text-[40px] font-bold leading-tight tracking-tight">
            Hermes Agent 平台
          </h1>
          <p className="mt-3 text-lg text-muted-foreground">
            选择一个入口开始
          </p>
        </div>

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          {PRODUCTS.map((p) => (
            <a
              key={p.href}
              href={p.href}
              className="group rounded-2xl border border-border bg-card p-6 transition-all hover:shadow-md hover:border-primary/30"
            >
              <div className="flex items-start justify-between">
                <div className="flex items-center gap-3">
                  <div
                    className="flex h-10 w-10 items-center justify-center rounded-xl"
                    style={{ backgroundColor: p.color + "15" }}
                  >
                    <p.icon className="h-5 w-5" style={{ color: p.color }} />
                  </div>
                  <div>
                    <h2 className="text-base font-semibold">{p.title}</h2>
                    <p className="mt-0.5 text-sm text-muted-foreground">{p.subtitle}</p>
                  </div>
                </div>
                <ArrowRight className="h-4 w-4 text-muted-foreground transition-transform group-hover:translate-x-1 group-hover:text-primary" />
              </div>
            </a>
          ))}
        </div>
      </main>
    </div>
  );
}

function ForwardingScreen() {
  return (
    <div className="flex min-h-screen items-center justify-center bg-background text-muted-foreground">
      <div className="flex flex-col items-center gap-3">
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-border border-t-primary" />
        <span className="text-[12px] tracking-[0.15em] uppercase">Loading…</span>
      </div>
    </div>
  );
}
