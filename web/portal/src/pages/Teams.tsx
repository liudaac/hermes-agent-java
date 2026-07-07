import { useEffect, useState } from "react";
import { portalApi } from "@/api/portal";
import type { BusinessTeamCard } from "@/api/types-portal";
import { EmployeeCard } from "@/components/EmployeeCard";
import { GlassCard } from "@/components/GlassCard";
import { AuroraBackground } from "@/components/AuroraBackground";
import { useI18n } from "@/i18n";
import { Users } from "lucide-react";

export default function Teams() {
  const { t } = useI18n();
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
          </GlassCard>
        ) : (
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            {teams.map((team) => (
              <EmployeeCard key={team.teamId} team={team} />
            ))}
          </div>
        )}
      </div>
    </AuroraBackground>
  );
}
