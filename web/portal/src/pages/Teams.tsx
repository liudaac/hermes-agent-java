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
            <h1 className="text-[28px] font-medium leading-tight text-foreground">
              {t("nav.teams")}
            </h1>
            <p className="mt-1 text-[13px] text-muted-foreground">
              管理你的数字员工团队
            </p>
          </div>
          <Link
            to="/templates"
            className="inline-flex items-center gap-1.5 rounded-full bg-primary/10 border border-border px-4 py-2 text-[13px] font-medium text-primary hover:bg-primary/10 active:scale-95 transition"
          >
            <Plus className="h-4 w-4" />
            创建团队
          </Link>
        </div>

        {error && (
          <GlassCard className="mb-3 border border-border">
            <p className="text-[12px] text-muted-foreground">
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
          <GlassCard grain className="flex flex-col items-center gap-3 py-10 text-center">
            <Users className="h-7 w-7 text-primary" />
            <p className="text-[14px] font-semibold text-foreground">
              {t("teams.empty")}
            </p>
            <p className="text-[12px] text-muted-foreground">
              从场景模板创建你的第一个数字员工
            </p>
            <Link
              to="/templates"
              className="mt-1 inline-flex items-center gap-1.5 rounded-full bg-primary px-4 py-2 text-[12px] font-semibold text-primary-foreground active:scale-95 transition"
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
