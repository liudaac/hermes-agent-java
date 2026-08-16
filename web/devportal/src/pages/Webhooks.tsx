import { useEffect, useState, useCallback } from "react";
import { Trash2, Send, Plus, X } from "lucide-react";
import { api, type WebhookSubscription } from "@/lib/api";
import { useToast } from "@/hooks/useToast";
import { useI18n } from "@/i18n";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@hermes/ui";
import { Button } from "@hermes/ui";
import { Input } from "@hermes/ui";
import { Label } from "@hermes/ui";
import { Badge } from "@hermes/ui";
import { LoadingSpinner } from "@/components/LoadingSpinner";

export default function Webhooks() {
  const { t } = useI18n();
  const { showToast } = useToast();
  const [webhooks, setWebhooks] = useState<WebhookSubscription[] | null>(null);
  const [url, setUrl] = useState("");
  const [events, setEvents] = useState<string[]>([]);
  const [eventInput, setEventInput] = useState("");
  const [secret, setSecret] = useState("");
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(() => {
    api.getWebhooks()
      .then((resp) => setWebhooks(resp.subscriptions ?? []))
      .catch(() => setWebhooks([]));
  }, []);

  useEffect(() => { refresh(); }, [refresh]);

  const handleAddEvent = () => {
    const trimmed = eventInput.trim();
    if (trimmed && !events.includes(trimmed)) {
      setEvents([...events, trimmed]);
      setEventInput("");
    }
  };

  const handleRemoveEvent = (ev: string) => {
    setEvents(events.filter((e) => e !== ev));
  };

  const handleRegister = async () => {
    if (!url.trim() || events.length === 0) return;
    setBusy(true);
    try {
      await api.registerWebhook(url.trim(), events, secret.trim() || undefined);
      showToast("Webhook registered", "success");
      setUrl("");
      setEvents([]);
      setSecret("");
      refresh();
    } catch (e) {
      showToast(`Failed: ${e}`, "error");
    } finally {
      setBusy(false);
    }
  };

  const handleDelete = async (wh: WebhookSubscription) => {
    if (!confirm(`Delete webhook for ${wh.url}?`)) return;
    try {
      await api.deleteWebhook(wh.url);
      showToast(t.webhooks.delete + " OK", "success");
      refresh();
    } catch (e) {
      showToast(`${t.webhooks.delete} failed: ${e}`, "error");
    }
  };

  const handleTest = async (wh: WebhookSubscription) => {
    try {
      await api.testWebhook(wh.url);
      showToast(t.webhooks.testSent, "success");
    } catch (e) {
      showToast(`Test failed: ${e}`, "error");
    }
  };

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-bold tracking-tight text-foreground">{t.webhooks.title}</h1>
        <p className="mt-1 text-sm text-muted-foreground">{t.webhooks.description}</p>
      </div>

      {/* Register form */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">{t.webhooks.register}</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          <div>
            <Label className="text-xs">{t.webhooks.url}</Label>
            <Input
              value={url}
              onChange={(e) => setUrl(e.target.value)}
              placeholder={t.webhooks.urlPlaceholder}
              className="mt-1"
            />
          </div>
          <div>
            <Label className="text-xs">{t.webhooks.events}</Label>
            <div className="flex gap-2 mt-1">
              <Input
                value={eventInput}
                onChange={(e) => setEventInput(e.target.value)}
                placeholder={t.webhooks.eventsPlaceholder}
                onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); handleAddEvent(); } }}
                className="flex-1"
              />
              <Button variant="outline" size="sm" onClick={handleAddEvent}>
                <Plus className="h-3 w-3" />
              </Button>
            </div>
            {events.length > 0 && (
              <div className="flex flex-wrap gap-1 mt-2">
                {events.map((ev) => (
                  <Badge key={ev} variant="secondary" className="text-xs gap-1">
                    {ev}
                    <button onClick={() => handleRemoveEvent(ev)} className="hover:text-destructive">
                      <X className="h-2.5 w-2.5" />
                    </button>
                  </Badge>
                ))}
              </div>
            )}
          </div>
          <div>
            <Label className="text-xs">{t.webhooks.secret}</Label>
            <Input
              value={secret}
              onChange={(e) => setSecret(e.target.value)}
              placeholder={t.webhooks.secretPlaceholder}
              className="mt-1"
              type="password"
            />
          </div>
          <Button onClick={handleRegister} disabled={busy || !url.trim() || events.length === 0}>
            {busy ? "..." : t.webhooks.register}
          </Button>
        </CardContent>
      </Card>

      {/* List */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">{t.webhooks.registered}</CardTitle>
          <CardDescription>
            {webhooks ? `${webhooks.length} webhook(s)` : "..."}
          </CardDescription>
        </CardHeader>
        <CardContent>
          {webhooks === null && <LoadingSpinner size="md" padding="py-6" />}
          {webhooks && webhooks.length === 0 && (
            <p className="text-sm text-muted-foreground text-center py-6">{t.webhooks.noWebhooks}</p>
          )}
          {webhooks && webhooks.length > 0 && (
            <div className="flex flex-col gap-2">
              {webhooks.map((wh, i) => (
                <div key={i} className="border border-border p-3 rounded flex flex-col gap-2">
                  <div className="flex items-center justify-between gap-2">
                    <code className="font-mono text-xs text-foreground truncate flex-1">{wh.url}</code>
                    <div className="flex gap-1 shrink-0">
                      <Button variant="outline" size="sm" className="h-7 text-xs" onClick={() => handleTest(wh)}>
                        <Send className="h-3 w-3 mr-1" />
                        {t.webhooks.test}
                      </Button>
                      <Button variant="ghost" size="sm" className="h-7 text-xs text-destructive" onClick={() => handleDelete(wh)}>
                        <Trash2 className="h-3 w-3" />
                        {t.webhooks.delete}
                      </Button>
                    </div>
                  </div>
                  {wh.events && wh.events.length > 0 && (
                    <div className="flex flex-wrap gap-1">
                      {wh.events.map((ev) => (
                        <Badge key={ev} variant="outline" className="text-[10px]">{ev}</Badge>
                      ))}
                    </div>
                  )}
                  {wh.status && (
                    <span className="text-[10px] text-muted-foreground">Status: {wh.status}</span>
                  )}
                </div>
              ))}
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
