import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { portalApi } from "@/api/portal";
import type { BusinessTeamCard } from "@/api/types-portal";
import { EmployeeCard } from "@/components/EmployeeCard";
import { GlassCard } from "@/components/GlassCard";
import { AuroraBackground } from "@/components/AuroraBackground";
import { useI18n } from "@/i18n";
import { useActiveHarnesses } from "@/hooks/useActiveHarnesses";
import { Users, Plus } from "lucide-react";

export default function Teams() {
  const { t } = useI18n();
  const { findForTeam } = useActiveHarnesses();
  const [teams, setTeams] = useState<BusinessTeamCard[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    portalApi
      .getBusinessTeams()
      .then((res) => {
        if (alive) setTeams(res.teams ?? []);
      })
      .catch((e) => {
        if (alive) setError(String(e?.message ?? e));
      });
    return () => {
      alive = false;
    };
  }, []);

  return (
    <AuroraBackground>
      <div className="page-in mx-auto max-w-3xl px-4 pb-24 pt-6">
        {/* Header with create button */}
        <div className="mb-5 flex items-center justify-between">
          <div>
            <h1 className="font-display text-[28px] font-medium leading-tight text-[var(--color-text-primary)]">
              {t("nav.teams")}
            </h1>
            <p className="mt-1 text-[13px] text-[var(--color-text-secondary)]">
              管理你的数字员工团队
            </p>
          </div>
          <Link
            to="/templates"
            className="inline-flex items-center gap-1.5 rounded-full bg-[oklch(0.78_0.16_70_/_0.2)] border border-[oklch(0.55_0.10_65_/_0.4)] px-4 py-2 text-[13px] font-medium text-[oklch(0.88_0.10_70)] hover:bg-[oklch(0.78_0.16_70_/_0.28)] active:scale-95 transition"
          >
            <Plus className="h-4 w-4" />
            创建团队
          </Link>
        </div>

        {error && (
          <GlassCard className="mb-3 border border-[oklch(0.68_0.20_25_/_0.35)]">
            <p className="text-[12px] text-[var(--color-text-secondary)]">
              {t("common.error")}：{error}
            </p>
          </GlassCard>
        )}

        {!teams ? (
          <div className="space-y-3">
            {[0, 1, 2].map((i) => (
              <div key={i} className="shimmer h-[110px] rounded-2xl" />
            ))}
          </div>
        ) : teams.length === 0 ? (
          <GlassCard tone="accent" grain className="flex flex-col items-center gap-3 py-10 text-center">
            <Users className="h-7 w-7 text-[var(--color-accent)]" />
            <p className="text-[14px] font-semibold text-[var(--color-text-primary)]">
              {t("teams.empty")}
            </p>
            <p className="text-[12px] text-[var(--color-text-secondary)]">
              从场景模板创建你的第一个数字员工
            </p>
            <Link
              to="/templates"
              className="mt-1 inline-flex items-center gap-1.5 rounded-full bg-[var(--color-text-primary)] px-4 py-2 text-[12px] font-semibold text-[var(--color-bg-0)] active:scale-95 transition"
            >
              <Plus className="h-3.5 w-3.5" />
              选个场景
            </Link>
          </GlassCard>
        ) : (
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            {teams.map((team) => (
              <EmployeeCard key={team.teamId} team={team} harness={findForTeam(team.teamId)} />
            ))}
          </div>
        )}
      </div>
    </AuroraBackground>
  );
}
