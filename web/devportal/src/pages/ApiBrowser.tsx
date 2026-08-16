import { useState, useMemo, useCallback } from "react";
import { Search, Copy, Check } from "lucide-react";
import { ENDPOINTS, ENDPOINT_CATEGORIES, type ApiEndpoint, type HttpMethod } from "@/lib/endpoints";
import { fetchJSON } from "@/lib/api";
import { useI18n } from "@/i18n";
import { Card, CardContent, CardHeader, CardTitle } from "@hermes/ui";
import { Button } from "@hermes/ui";
import { Input } from "@hermes/ui";
import { Badge } from "@hermes/ui";

const METHOD_COLORS: Record<HttpMethod, string> = {
  GET: "text-green-400 border-green-500/30 bg-green-500/10",
  POST: "text-blue-400 border-blue-500/30 bg-blue-500/10",
  PUT: "text-amber-400 border-amber-500/30 bg-amber-500/10",
  DELETE: "text-red-400 border-red-500/30 bg-red-500/10",
  PATCH: "text-purple-400 border-purple-500/30 bg-purple-500/10",
};

function MethodBadge({ method }: { method: HttpMethod }) {
  return (
    <span className={`inline-flex items-center px-1.5 py-0.5 text-[10px] font-mono font-bold border rounded ${METHOD_COLORS[method]}`}>
      {method}
    </span>
  );
}

export default function ApiBrowser() {
  const { t } = useI18n();
  const [selected, setSelected] = useState<ApiEndpoint | null>(null);
  const [search, setSearch] = useState("");
  const [methodFilter, setMethodFilter] = useState<HttpMethod | "ALL">("ALL");
  const [categoryFilter, setCategoryFilter] = useState<string | "ALL">("ALL");
  const [response, setResponse] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [copied, setCopied] = useState(false);
  const [bodyText, setBodyText] = useState("{}");

  const filtered = useMemo(() => {
    return ENDPOINTS.filter((ep) => {
      if (methodFilter !== "ALL" && ep.method !== methodFilter) return false;
      if (categoryFilter !== "ALL" && ep.category !== categoryFilter) return false;
      if (search) {
        const q = search.toLowerCase();
        return ep.path.toLowerCase().includes(q) || ep.description.toLowerCase().includes(q);
      }
      return true;
    });
  }, [search, methodFilter, categoryFilter]);

  const grouped = useMemo(() => {
    const map = new Map<string, ApiEndpoint[]>();
    for (const ep of filtered) {
      if (!map.has(ep.category)) map.set(ep.category, []);
      map.get(ep.category)!.push(ep);
    }
    return Array.from(map.entries());
  }, [filtered]);

  const handleTryIt = useCallback(async () => {
    if (!selected) return;
    setLoading(true);
    setResponse(null);
    try {
      let url = selected.path;
      // Replace path params with placeholder values
      url = url.replace(/\{[^}]+\}/g, "test");
      // Add query params for GET
      if (selected.method === "GET") {
        const params = selected.params?.filter((p) => p.type === "query");
        if (params && params.length > 0) {
          const qs = params.map((p) => `${p.name}=test`).join("&");
          url += `?${qs}`;
        }
      }
      const opts: RequestInit = { method: selected.method };
      if (selected.method !== "GET") {
        opts.headers = { "Content-Type": "application/json" };
        try {
          opts.body = JSON.stringify(JSON.parse(bodyText));
        } catch {
          opts.body = bodyText;
        }
      }
      const result = await fetchJSON<unknown>(url, opts);
      setResponse(JSON.stringify(result, null, 2));
    } catch (e) {
      setResponse(`Error: ${e instanceof Error ? e.message : String(e)}`);
    } finally {
      setLoading(false);
    }
  }, [selected, bodyText]);

  const handleCopyUrl = useCallback(() => {
    if (!selected) return;
    navigator.clipboard.writeText(selected.path);
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  }, [selected]);

  return (
    <div className="flex flex-col gap-4">
      <div>
        <h1 className="text-xl font-bold tracking-tight text-foreground">{t.api.title}</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          {t.api.description.replace("{count}", String(ENDPOINTS.length))}
        </p>
      </div>

      <div className="flex gap-4 h-[calc(100vh-12rem)]">
        {/* Sidebar */}
        <div className="w-80 shrink-0 flex flex-col gap-2 overflow-hidden">
          {/* Search + filters */}
          <div className="flex flex-col gap-2">
            <div className="relative">
              <Search className="absolute left-2.5 top-2 h-4 w-4 text-muted-foreground" />
              <Input
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder={t.api.filter}
                className="pl-8 h-8 text-sm"
              />
            </div>
            <div className="flex gap-2">
              <select
                value={methodFilter}
                onChange={(e) => setMethodFilter(e.target.value as HttpMethod | "ALL")}
                className="h-7 text-xs border border-border bg-surface px-2 rounded"
              >
                <option value="ALL">{t.api.allMethods}</option>
                <option value="GET">GET</option>
                <option value="POST">POST</option>
                <option value="PUT">PUT</option>
                <option value="DELETE">DELETE</option>
                <option value="PATCH">PATCH</option>
              </select>
              <select
                value={categoryFilter}
                onChange={(e) => setCategoryFilter(e.target.value)}
                className="h-7 text-xs border border-border bg-surface px-2 rounded flex-1"
              >
                <option value="ALL">All Categories</option>
                {ENDPOINT_CATEGORIES.map((cat) => (
                  <option key={cat} value={cat}>{cat}</option>
                ))}
              </select>
            </div>
          </div>

          {/* Endpoint list */}
          <div className="flex-1 overflow-y-auto border border-border rounded">
            {grouped.length === 0 && (
              <div className="p-4 text-sm text-muted-foreground text-center">{t.api.noEndpoint}</div>
            )}
            {grouped.map(([category, eps]) => (
              <div key={category}>
                <div className="sticky top-0 bg-surface px-3 py-1.5 text-[10px] font-mono font-bold text-muted-foreground uppercase tracking-wider border-b border-border">
                  {category}
                </div>
                {eps.map((ep) => (
                  <button
                    key={`${ep.method}-${ep.path}`}
                    onClick={() => { setSelected(ep); setResponse(null); setBodyText("{}"); }}
                    className={`flex w-full items-center gap-2 px-3 py-1.5 text-left hover:bg-surface-hover transition-colors border-b border-border/50 ${
                      selected?.path === ep.path && selected?.method === ep.method ? "bg-accent/10" : ""
                    }`}
                  >
                    <MethodBadge method={ep.method} />
                    <span className="font-mono text-xs truncate flex-1">{ep.path}</span>
                  </button>
                ))}
              </div>
            ))}
          </div>
        </div>

        {/* Detail panel */}
        <div className="flex-1 overflow-y-auto">
          {!selected ? (
            <div className="flex h-full items-center justify-center text-sm text-muted-foreground">
              {t.api.noEndpoint}
            </div>
          ) : (
            <Card>
              <CardHeader>
                <div className="flex items-center gap-3">
                  <MethodBadge method={selected.method} />
                  <code className="font-mono text-sm flex-1">{selected.path}</code>
                  <Button variant="ghost" size="sm" onClick={handleCopyUrl} className="h-7 text-xs">
                    {copied ? <Check className="h-3 w-3" /> : <Copy className="h-3 w-3" />}
                    {copied ? t.api.copied : t.api.copyUrl}
                  </Button>
                </div>
                <CardTitle className="text-sm font-normal text-muted-foreground mt-1">
                  {selected.description}
                </CardTitle>
              </CardHeader>
              <CardContent className="flex flex-col gap-4">
                {/* Parameters */}
                {selected.params && selected.params.length > 0 && (
                  <div>
                    <h3 className="text-xs font-mono font-bold uppercase text-muted-foreground mb-2">{t.api.parameters}</h3>
                    <div className="flex flex-col gap-1">
                      {selected.params.map((p) => (
                        <div key={p.name} className="flex items-center gap-2 text-xs border border-border/50 px-3 py-1.5">
                          <Badge variant="outline" className="text-[10px] font-mono">{p.type}</Badge>
                          <code className="font-mono text-foreground">{p.name}</code>
                          {p.required && <Badge variant="destructive" className="text-[9px]">required</Badge>}
                          <span className="text-muted-foreground flex-1">{p.description}</span>
                        </div>
                      ))}
                    </div>
                  </div>
                )}

                {/* Body input for non-GET */}
                {selected.method !== "GET" && (
                  <div>
                    <h3 className="text-xs font-mono font-bold uppercase text-muted-foreground mb-2">{t.api.body}</h3>
                    <textarea
                      value={bodyText}
                      onChange={(e) => setBodyText(e.target.value)}
                      className="w-full h-32 bg-code-bg border border-border rounded p-3 font-mono text-xs text-foreground"
                      placeholder='{"key": "value"}'
                    />
                  </div>
                )}

                {/* Try It button */}
                <div>
                  <Button onClick={handleTryIt} disabled={loading} size="sm">
                    {loading ? "..." : `${t.api.tryIt} →`}
                  </Button>
                </div>

                {/* Response */}
                {response && (
                  <div>
                    <h3 className="text-xs font-mono font-bold uppercase text-muted-foreground mb-2">{t.api.response}</h3>
                    <pre className="bg-code-bg border border-border rounded p-3 font-mono text-xs text-foreground overflow-x-auto max-h-96">
                      {response}
                    </pre>
                  </div>
                )}
              </CardContent>
            </Card>
          )}
        </div>
      </div>
    </div>
  );
}
