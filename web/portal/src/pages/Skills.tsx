import { useEffect, useState, useCallback, useMemo } from "react";
import { portalApi } from "@/api/portal";
import { GlassCard } from "@/components/GlassCard";
import { AuroraBackground } from "@/components/AuroraBackground";
import { ErrorCard } from "@/components/ErrorCard";
import { useI18n } from "@/i18n";
import { useWorkspace } from "@/hooks/useWorkspace";
import { cn } from "@hermes/ui";
import { Search, Wrench, Check, X } from "lucide-react";

interface Skill {
  id: string;
  name: string;
  description: string;
  scope: string;
  type: string;
  enabled: boolean;
  currentVersion: number;
  versions: number;
  updatedAt: string | null;
}

const SCOPE_COLORS: Record<string, string> = {
  private: "bg-[oklch(0.70_0.16_280_/_0.18)] text-[oklch(0.78_0.14_280)]",
  installed: "bg-[oklch(0.72_0.14_145_/_0.18)] text-[oklch(0.78_0.12_145)]",
  shared: "bg-[oklch(0.78_0.16_85_/_0.18)] text-[oklch(0.85_0.14_85)]",
  system: "bg-[oklch(0.30_0.02_50_/_0.6)] text-[var(--color-text-secondary)]",
};

const CATEGORIES = ["all", "private", "installed", "shared", "system"];

export default function Skills() {
  const { t } = useI18n();
  const { workspaceId } = useWorkspace();
  const [skills, setSkills] = useState<Skill[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState("");
  const [category, setCategory] = useState("all");
  const [togglingId, setTogglingId] = useState<string | null>(null);

  const tenantId = workspaceId ?? "default";

  const loadSkills = useCallback(() => {
    setLoading(true);
    setError(null);
    portalApi
      .getSkills(tenantId)
      .then((res) => setSkills(res as unknown as Skill[]))
      .catch((e) => setError(String(e?.message ?? e)))
      .finally(() => setLoading(false));
  }, [tenantId]);

  useEffect(() => {
    loadSkills();
  }, [loadSkills]);

  const handleToggle = async (skill: Skill) => {
    setTogglingId(skill.id);
    try {
      // Use the portal API to toggle - we'll use fetchJSON directly via portalApi pattern
      const res = await fetch(`/api/skills/${encodeURIComponent(tenantId)}/${encodeURIComponent(skill.id)}/toggle`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ enabled: !skill.enabled }),
      });
      if (res.ok) {
        setSkills((cur) =>
          cur.map((s) => (s.id === skill.id ? { ...s, enabled: !s.enabled } : s)),
        );
      }
    } catch (e: any) {
      setError(String(e?.message ?? e));
    } finally {
      setTogglingId(null);
    }
  };

  const filtered = useMemo(() => {
    let result = skills;
    if (category !== "all") {
      result = result.filter((s) => s.scope === category);
    }
    if (searchQuery.trim()) {
      const q = searchQuery.toLowerCase();
      result = result.filter(
        (s) => s.name.toLowerCase().includes(q) || s.description.toLowerCase().includes(q),
      );
    }
    return result;
  }, [skills, category, searchQuery]);

  return (
    <AuroraBackground>
      <div className="page-in mx-auto max-w-3xl px-4 pb-24 pt-6">
        <header className="mb-5">
          <h1 className="font-display text-[28px] font-medium leading-tight text-[var(--color-text-primary)]">
            {t("skills.title")}
          </h1>
          <p className="mt-1 text-[13px] text-[var(--color-text-secondary)]">
            {t("skills.subtitle")}
          </p>
        </header>

        {/* Search */}
        <div className="mb-3 flex gap-2">
          <div className="relative flex-1">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-[var(--color-text-muted)]" />
            <input
              type="text"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder={t("skills.search")}
              className="w-full rounded-xl bg-white/10 py-2.5 pl-9 pr-4 text-[13px] text-[var(--color-text-primary)] placeholder:text-[var(--color-text-muted)] outline-none focus:ring-1 focus:ring-[var(--color-accent)]"
            />
          </div>
        </div>

        {/* Category chips */}
        <div className="mb-4 flex gap-2 overflow-x-auto pb-1">
          {CATEGORIES.map((cat) => (
            <button
              key={cat}
              onClick={() => setCategory(cat)}
              className={cn(
                "rounded-full px-3 py-1.5 text-[12px] font-medium whitespace-nowrap transition active:scale-95",
                category === cat
                  ? "bg-[oklch(0.78_0.16_70_/_0.2)] text-[oklch(0.88_0.12_70)]"
                  : "bg-white/5 text-[var(--color-text-muted)]",
              )}
            >
              {cat === "all" ? t("sessions.all") : t(`skills.scope.${cat}`)}
            </button>
          ))}
        </div>

        {error && <ErrorCard message={error} onRetry={loadSkills} />}

        {loading ? (
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            {[0, 1, 2, 3].map((i) => (
              <div key={i} className="shimmer h-28 rounded-2xl" />
            ))}
          </div>
        ) : filtered.length === 0 ? (
          <GlassCard className="flex flex-col items-center gap-2 py-10 text-center">
            <Wrench className="h-6 w-6 text-[var(--color-text-muted)]" />
            <p className="text-[13px] text-[var(--color-text-secondary)]">{t("skills.empty")}</p>
          </GlassCard>
        ) : (
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            {filtered.map((skill) => (
              <GlassCard key={skill.id} padding="sm">
                <div className="flex items-start justify-between gap-2">
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      <h3 className="truncate text-[14px] font-semibold text-[var(--color-text-primary)]">
                        {skill.name}
                      </h3>
                      <span
                        className={cn(
                          "shrink-0 rounded-full px-2 py-0.5 text-[10px] font-medium",
                          SCOPE_COLORS[skill.scope] ?? SCOPE_COLORS.system,
                        )}
                      >
                        {t(`skills.scope.${skill.scope}`) ?? skill.scope}
                      </span>
                    </div>
                    <p className="mt-1 line-clamp-2 text-[12px] leading-relaxed text-[var(--color-text-secondary)]">
                      {skill.description}
                    </p>
                    <div className="mt-2 flex items-center gap-2 text-[11px] text-[var(--color-text-muted)]">
                      <span>{skill.type}</span>
                      <span>·</span>
                      <span>v{skill.currentVersion}</span>
                      {skill.versions > 1 && (
                        <>
                          <span>·</span>
                          <span>{skill.versions} versions</span>
                        </>
                      )}
                    </div>
                  </div>
                  {/* Toggle */}
                  <button
                    onClick={() => handleToggle(skill)}
                    disabled={togglingId === skill.id}
                    className={cn(
                      "flex h-7 w-12 shrink-0 items-center rounded-full p-0.5 transition active:scale-95",
                      skill.enabled
                        ? "bg-[oklch(0.72_0.14_145_/_0.6)]"
                        : "bg-[oklch(0.30_0.02_50_/_0.6)]",
                      togglingId === skill.id && "opacity-60",
                    )}
                    aria-label={skill.enabled ? t("memory.enabled") : t("memory.disabled")}
                  >
                    <span
                      className={cn(
                        "flex h-6 w-6 items-center justify-center rounded-full bg-white text-[10px] transition",
                        skill.enabled ? "translate-x-5" : "translate-x-0",
                      )}
                    >
                      {skill.enabled ? (
                        <Check className="h-3 w-3 text-[oklch(0.72_0.14_145)]" />
                      ) : (
                        <X className="h-3 w-3 text-[var(--color-text-muted)]" />
                      )}
                    </span>
                  </button>
                </div>
              </GlassCard>
            ))}
          </div>
        )}
      </div>
    </AuroraBackground>
  );
}
