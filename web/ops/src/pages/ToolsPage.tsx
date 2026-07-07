import { useEffect, useMemo, useState } from "react";
import { Wrench, Search, X, ChevronRight, Loader2 } from "lucide-react";
import { H2 } from "@nous-research/ui";
import { api } from "@/lib/api";
import type { ToolGroup, ToolDetail } from "@/lib/api";
import { Card, CardContent } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";

export default function ToolsPage() {
  const [groups, setGroups] = useState<ToolGroup[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState("");
  const [selected, setSelected] = useState<string | null>(null);
  const [detail, setDetail] = useState<ToolDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  useEffect(() => {
    api
      .getToolGroups()
      .then((g) => setGroups(g))
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    if (!selected) {
      setDetail(null);
      return;
    }
    setDetailLoading(true);
    api
      .getToolDetail(selected)
      .then((d) => setDetail(d))
      .catch(() => setDetail(null))
      .finally(() => setDetailLoading(false));
  }, [selected]);

  const lowerSearch = search.toLowerCase().trim();
  const filtered = useMemo(() => {
    if (!lowerSearch) return groups;
    return groups
      .map((g) => ({
        ...g,
        tool_details: g.tool_details.filter(
          (t) =>
            t.name.toLowerCase().includes(lowerSearch) ||
            t.description.toLowerCase().includes(lowerSearch),
        ),
      }))
      .filter(
        (g) =>
          g.name.toLowerCase().includes(lowerSearch) ||
          g.tool_details.length > 0,
      );
  }, [groups, lowerSearch]);

  const totalTools = useMemo(
    () => groups.reduce((sum, g) => sum + g.tool_details.length, 0),
    [groups],
  );

  if (loading) {
    return (
      <div className="flex items-center justify-center py-24">
        <Loader2 className="h-6 w-6 animate-spin text-primary" />
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center justify-between gap-4">
        <div className="flex items-center gap-3">
          <Wrench className="h-5 w-5 text-muted-foreground" />
          <H2 variant="sm">Tools</H2>
          <span className="text-xs text-muted-foreground">
            {totalTools} tools / {groups.length} toolsets
          </span>
        </div>
        <div className="relative w-64 max-w-full">
          <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-muted-foreground" />
          <Input
            className="pl-8 h-8 text-xs"
            placeholder="Search tools..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
          {search && (
            <button
              type="button"
              className="absolute right-2 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
              onClick={() => setSearch("")}
            >
              <X className="h-3 w-3" />
            </button>
          )}
        </div>
      </div>

      {error && (
        <Card>
          <CardContent className="py-3 text-xs text-amber-400">
            Failed to load tools: {error}
          </CardContent>
        </Card>
      )}

      <div className="grid gap-4 lg:grid-cols-[2fr_1fr]">
        <div className="flex flex-col gap-3">
          {filtered.length === 0 && (
            <Card>
              <CardContent className="py-8 text-center text-sm text-muted-foreground">
                No tools match your search.
              </CardContent>
            </Card>
          )}
          {filtered.map((group) => (
            <Card key={group.name}>
              <CardContent className="py-4">
                <div className="flex items-center gap-2 mb-3">
                  <span className="text-lg leading-none">{group.emoji}</span>
                  <span className="font-medium text-sm">{group.name}</span>
                  <Badge
                    variant={group.available ? "success" : "outline"}
                    className="text-[10px]"
                  >
                    {group.available ? "available" : "unavailable"}
                  </Badge>
                  <span className="ml-auto text-[10px] text-muted-foreground">
                    {group.tool_details.length} tool
                    {group.tool_details.length === 1 ? "" : "s"}
                  </span>
                </div>
                <p className="text-xs text-muted-foreground mb-3">
                  {group.description}
                </p>
                <div className="flex flex-col divide-y divide-border/40">
                  {group.tool_details.map((tool) => (
                    <button
                      key={tool.name}
                      type="button"
                      onClick={() => setSelected(tool.name)}
                      className={`group flex items-center gap-2 py-2 text-left text-xs transition-colors hover:bg-accent/30 px-1 -mx-1 rounded cursor-pointer ${
                        selected === tool.name ? "bg-accent/40" : ""
                      }`}
                    >
                      <span className="text-base leading-none w-5 text-center">
                        {tool.emoji || "🔧"}
                      </span>
                      <span className="font-mono text-[11px] shrink-0">
                        {tool.name}
                      </span>
                      <span className="text-muted-foreground truncate flex-1">
                        {tool.description}
                      </span>
                      {tool.async && (
                        <Badge variant="secondary" className="text-[10px]">
                          async
                        </Badge>
                      )}
                      <ChevronRight className="h-3 w-3 text-muted-foreground opacity-0 group-hover:opacity-100" />
                    </button>
                  ))}
                </div>
              </CardContent>
            </Card>
          ))}
        </div>

        <div className="lg:sticky lg:top-[72px] h-fit">
          <Card>
            <CardContent className="py-4">
              {!selected && (
                <p className="text-xs text-muted-foreground text-center py-6">
                  Click a tool to view its schema.
                </p>
              )}
              {selected && detailLoading && (
                <div className="flex items-center justify-center py-6">
                  <Loader2 className="h-4 w-4 animate-spin text-primary" />
                </div>
              )}
              {selected && !detailLoading && detail && (
                <div className="flex flex-col gap-3">
                  <div className="flex items-center gap-2">
                    <span className="text-lg leading-none">
                      {detail.emoji || "🔧"}
                    </span>
                    <span className="font-mono text-xs">{detail.name}</span>
                    <Badge
                      variant={detail.available ? "success" : "outline"}
                      className="text-[10px] ml-auto"
                    >
                      {detail.available ? "available" : "unavailable"}
                    </Badge>
                  </div>
                  <div className="text-[10px] text-muted-foreground uppercase tracking-wide">
                    toolset · {detail.toolset}
                  </div>
                  <p className="text-xs">{detail.description}</p>
                  <div className="grid grid-cols-2 gap-2 text-[11px]">
                    <div className="flex flex-col">
                      <span className="text-muted-foreground">async</span>
                      <span>{detail.async ? "yes" : "no"}</span>
                    </div>
                    <div className="flex flex-col">
                      <span className="text-muted-foreground">params</span>
                      <span>{detail.parameter_count ?? 0}</span>
                    </div>
                    <div className="flex flex-col col-span-2">
                      <span className="text-muted-foreground">
                        requires env
                      </span>
                      <span className="font-mono">
                        {detail.requires_env && detail.requires_env.length > 0
                          ? detail.requires_env.join(", ")
                          : "—"}
                      </span>
                    </div>
                    <div className="flex flex-col col-span-2">
                      <span className="text-muted-foreground">
                        max result size (chars)
                      </span>
                      <span>{detail.max_result_size_chars ?? "—"}</span>
                    </div>
                  </div>
                  <div>
                    <div className="text-[10px] text-muted-foreground uppercase tracking-wide mb-1">
                      JSON schema
                    </div>
                    <pre className="text-[10px] bg-muted/40 p-2 rounded overflow-auto max-h-80 leading-snug">
{JSON.stringify(detail.schema, null, 2)}
                    </pre>
                  </div>
                </div>
              )}
              {selected && !detailLoading && !detail && (
                <p className="text-xs text-amber-400 text-center py-6">
                  Could not load details for {selected}.
                </p>
              )}
            </CardContent>
          </Card>
        </div>
      </div>
    </div>
  );
}
