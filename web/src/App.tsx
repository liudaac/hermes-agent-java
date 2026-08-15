import { useEffect } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { BriefcaseBusiness, TerminalSquare, Shield, Code2, ArrowRight, Sparkles } from "lucide-react";

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
    <div className="relative min-h-screen overflow-hidden bg-[#0a0a0f] text-white antialiased">
      {/* Aurora background */}
      <div className="pointer-events-none fixed inset-0">
        <div className="absolute -top-[20%] left-[10%] h-[500px] w-[500px] rounded-full bg-[oklch(0.78_0.16_70_/_0.12)] blur-[120px]" />
        <div className="absolute top-[40%] -right-[10%] h-[400px] w-[400px] rounded-full bg-[oklch(0.72_0.14_180_/_0.10)] blur-[100px]" />
        <div className="absolute -bottom-[10%] left-[30%] h-[350px] w-[350px] rounded-full bg-[oklch(0.70_0.12_280_/_0.08)] blur-[90px]" />
      </div>

      {/* Grain texture */}
      <div
        className="pointer-events-none fixed inset-0 opacity-[0.015] mix-blend-overlay"
        style={{
          backgroundImage: `url("data:image/svg+xml,%3Csvg viewBox='0 0 200 200' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.85' numOctaves='4' /%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)' /%3E%3C/svg%3E")`,
        }}
      />

      {/* Content */}
      <div className="relative z-10 mx-auto flex min-h-screen w-full max-w-6xl flex-col px-6 py-8 sm:py-12">
        {/* Nav */}
        <nav className="flex items-center justify-between">
          <div className="flex items-center gap-2.5">
            <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-gradient-to-br from-[oklch(0.78_0.16_70)] to-[oklch(0.68_0.18_50)]">
              <Sparkles className="h-4 w-4 text-white" />
            </div>
            <span className="font-mondwest text-sm font-bold tracking-[0.2em] uppercase text-white/80">
              Hermes
            </span>
          </div>
          <div className="flex items-center gap-1 rounded-full border border-white/10 bg-white/5 px-3 py-1 text-[11px] text-white/40">
            <span className="h-1.5 w-1.5 rounded-full bg-green-400 animate-pulse" />
            Online
          </div>
        </nav>

        {/* Hero */}
        <div className="flex flex-1 flex-col justify-center py-10 sm:py-16">
          <div className="mb-2 inline-flex w-fit items-center gap-1.5 rounded-full border border-white/10 bg-white/5 px-3 py-1 text-[11px] tracking-wide text-white/50">
            <Sparkles className="h-3 w-3 text-[oklch(0.88_0.12_70)]" />
            AI Native Agent Platform
          </div>

          <h1 className="mt-4 font-display text-[40px] font-bold leading-[1.05] tracking-tight sm:text-[56px]">
            <span className="bg-gradient-to-r from-white via-white to-white/60 bg-clip-text text-transparent">
              让 AI 员工
            </span>
            <br />
            <span className="bg-gradient-to-r from-[oklch(0.88_0.12_70)] via-[oklch(0.82_0.14_60)] to-[oklch(0.78_0.16_80)] bg-clip-text text-transparent">
              为你工作
            </span>
          </h1>

          <p className="mt-5 max-w-lg text-[15px] leading-relaxed text-white/50">
            部署数字员工，自动化业务流程。从招聘到财务，从物流到客服，
            让 AI 在你的业务场景中 7×24 小候命。
          </p>

          {/* Product cards - 2x2 grid */}
          <div className="mt-10 grid w-full grid-cols-1 gap-4 sm:grid-cols-2">
            {/* Portal */}
            <ProductCard
              href="/portal/index.html"
              icon={BriefcaseBusiness}
              title="Portal"
              subtitle="业务前店"
              description="数字员工 · 场景模板 · 运行管理 · 待审批"
              tags={["HR", "Finance", "Logistics", "Customer Service"]}
              color="oklch(0.78_0.16_70)"
            />

            {/* Ops */}
            <ProductCard
              href="/ops/index.html"
              icon={TerminalSquare}
              title="Ops"
              subtitle="运维控制台"
              description="系统监控 · 会话管理 · 工具生态 · 日志分析"
              tags={["Sessions", "Analytics", "Tools", "DLQ"]}
              color="oklch(0.72_0.14_180)"
            />

            {/* Admin */}
            <ProductCard
              href="/admin/index.html"
              icon={Shield}
              title="Admin"
              subtitle="组织管理"
              description="租户管理 · 空间成员 · 模型路由 · 计费审计"
              tags={["Tenants", "Users", "Billing", "Audit"]}
              color="oklch(0.70_0.12_280)"
            />

            {/* DevPortal */}
            <ProductCard
              href="/devportal/index.html"
              icon={Code2}
              title="DevPortal"
              subtitle="开发者门户"
              description="API 文档 · Webhook · OAuth · 集成指南"
              tags={["REST", "SSE", "OAuth", "SDK"]}
              color="oklch(0.72_0.14_150)"
            />
          </div>
        </div>

        {/* Footer */}
        <footer className="flex items-center justify-between border-t border-white/5 pt-6 text-[11px] text-white/30">
          <span className="tracking-[0.15em] uppercase">Hermes Agent</span>
          <span className="flex items-center gap-3">
            <span>Portal :5175</span>
            <span className="h-1 w-1 rounded-full bg-white/20" />
            <span>Ops :5176</span>
            <span className="h-1 w-1 rounded-full bg-white/20" />
            <span>Admin :5177</span>
            <span className="h-1 w-1 rounded-full bg-white/20" />
            <span>Dev :5178</span>
          </span>
        </footer>
      </div>
    </div>
  );
}

interface ProductCardProps {
  href: string;
  icon: React.ComponentType<{ className?: string; style?: React.CSSProperties }>;
  title: string;
  subtitle: string;
  description: string;
  tags: string[];
  color: string;
}

function ProductCard({ href, icon: Icon, title, subtitle, description, tags, color }: ProductCardProps) {
  return (
    <a
      href={href}
      className="group relative overflow-hidden rounded-2xl border border-white/10 bg-white/[0.03] p-6 transition-all duration-300 hover:border-[color-mix(in_oklch,white_15%,transparent)] hover:bg-white/[0.06]"
      style={{ ["--card-glow" as string]: color }}
    >
      {/* Hover glow */}
      <div
        className="pointer-events-none absolute -right-20 -top-20 h-40 w-40 rounded-full opacity-0 blur-[60px] transition-opacity duration-500 group-hover:opacity-100"
        style={{ background: `color-mix(in oklch, ${color} 15%, transparent)` }}
      />

      <div className="relative">
        <div
          className="flex h-12 w-12 items-center justify-center rounded-xl ring-1"
          style={{
            background: `linear-gradient(135deg, color-mix(in oklch, ${color} 20%, transparent), color-mix(in oklch, ${color} 10%, transparent))`,
            ["--tw-ring-color" as string]: `color-mix(in oklch, ${color} 20%, transparent)`,
          }}
        >
          <Icon className="h-6 w-6" style={{ color: `color-mix(in oklch, ${color} 80%, white)` }} />
        </div>

        <h2 className="mt-4 text-lg font-semibold text-white">
          {title}
          <span className="ml-2 text-xs font-normal text-white/40">{subtitle}</span>
        </h2>

        <p className="mt-2 text-[13px] leading-relaxed text-white/50">{description}</p>

        <div className="mt-4 flex flex-wrap gap-1.5">
          {tags.map((tag) => (
            <span key={tag} className="rounded-full bg-white/5 px-2.5 py-0.5 text-[10px] tracking-wide text-white/40">
              {tag}
            </span>
          ))}
        </div>

        <div
          className="mt-5 inline-flex items-center gap-1 text-[13px] font-medium transition-transform duration-300 group-hover:gap-2"
          style={{ color: `color-mix(in oklch, ${color} 80%, white)` }}
        >
          进入
          <ArrowRight className="h-3.5 w-3.5" />
        </div>
      </div>
    </a>
  );
}

function ForwardingScreen() {
  return (
    <div className="flex min-h-screen items-center justify-center bg-[#0a0a0f] text-white/40">
      <div className="flex flex-col items-center gap-3">
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-white/10 border-t-[oklch(0.88_0.12_70)]" />
        <span className="text-[12px] tracking-[0.15em] uppercase">Loading…</span>
      </div>
    </div>
  );
}
