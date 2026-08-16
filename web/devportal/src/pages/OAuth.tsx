import { ShieldCheck } from "lucide-react";
import { OAuthProvidersCard } from "@/components/OAuthProvidersCard";
import { useToast } from "@/hooks/useToast";
import { useI18n } from "@/i18n";

export default function OAuth() {
  const { showToast } = useToast();
  const { t } = useI18n();

  return (
    <div className="flex flex-col gap-6">
      <div>
        <div className="flex items-center gap-2">
          <ShieldCheck className="h-5 w-5 text-muted-foreground" />
          <h1 className="text-xl font-bold tracking-tight text-foreground">{t.oauth.title}</h1>
        </div>
        <p className="mt-1 text-sm text-muted-foreground">{t.oauth.description.replace("{connected}", "0").replace("{total}", "0")}</p>
      </div>

      <OAuthProvidersCard
        onError={(msg) => showToast(msg, "error")}
        onSuccess={(msg) => showToast(msg, "success")}
      />
    </div>
  );
}
