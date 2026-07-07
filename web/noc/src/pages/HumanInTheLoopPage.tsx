import { useEffect, useState, useCallback } from "react";
import { Hand, Play, Square, MessageSquare, RotateCw, Activity } from "lucide-react";
import { H2 } from "@nous-research/ui";
import { api } from "@/lib/api";
import { Card, CardContent } from "@hermes/ui";
import { Button } from "@hermes/ui";
import { Badge } from "@hermes/ui";
import { useToast } from "@/hooks/useToast";
import { useSse } from "@/hooks/useSse";
import { cn } from "@hermes/ui";

interface TakeoverSession {
  takeoverId: string;
  runId: string;
  operatorId: string;
  teamId: string;
  status: "REQUESTED" | "ACTIVE" | "RELEASED";
  startedAt: string;
}

export default function HumanInTheLoopPage() {
  const { showToast } = useToast();
  const [sessions, setSessions] = useState<TakeoverSession[]>([]);
  const [loading, setLoading] = useState(true);
  const [acting, setActing] = useState<string | null>(null);
  const [liveEvent, setLiveEvent] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setLoading(true);
      const res = await api.getTakeovers();
      if (res.ok && Array.isArray(res.takeovers)) {
        setSessions(
          res.takeovers.map((s: any) => ({
            takeoverId: s.takeoverId,
            runId: s.runId,
            operatorId: s.operatorId,
            teamId: s.teamId,
            status: s.status,
            startedAt: s.startedAt,
          })),
        );
      } else {
        setSessions([]);
      }
    } catch (e: any) {
      showToast(e?.message || "Failed to load takeovers", "error");
    } finally {
      setLoading(false);
    }
  }, [showToast]);

  useEffect(() => {
    load();
  }, [load]);

  useSse({
    onEvent: (evt) => {
      if (["TAKEOVER_REQUESTED", "TAKEOVER_CONFIRMED", "TAKEOVER_RELEASED", "RUN_STATUS"].includes(evt.type)) {
        setLiveEvent(`${evt.type} @ ${new Date().toLocaleTimeString()}`);
        load();
      }
    },
    onError: () => {},
  });

  const confirmTakeover = async (id: string) => {
    try {
      setActing(id);
      await api.confirmTakeover(id);
      showToast("Takeover confirmed", "success");
      await load();
    } catch (e: any) {
      showToast(e?.message || "Confirm failed", "error");
    } finally {
      setActing(null);
    }
  };

  const releaseTakeover = async (id: string) => {
    try {
      setActing(id);
      await api.releaseTakeover(id);
      showToast("Takeover released", "success");
      await load();
    } catch (e: any) {
      showToast(e?.message || "Release failed", "error");
    } finally {
      setActing(null);
    }
  };

  const statusBadge = (status: string) => {
    const styles: Record<string, string> = {
      REQUESTED: "bg-amber-500/10 text-amber-500 border-amber-500/20",
      ACTIVE: "bg-green-500/10 text-green-500 border-green-500/20",
      RELEASED: "bg-gray-500/10 text-gray-500 border-gray-500/20",
    };
    return (
      <Badge variant="outline" className={cn("text-[0.6rem]", styles[status] || "")}>
        {status}
      </Badge>
    );
  };

  const activeCount = sessions.filter((s) => s.status === "ACTIVE").length;
  const requestedCount = sessions.filter((s) => s.status === "REQUESTED").length;

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <Hand className="h-5 w-5 opacity-70" />
          <H2 className="text-base tracking-[0.08em]">Human-in-the-Loop</H2>
          {liveEvent && (
            <Badge variant="outline" className="text-[0.6rem] text-green-500 border-green-500/20 animate-pulse">
              <Activity className="h-3 w-3 mr-1" />
              {liveEvent}
            </Badge>
          )}
        </div>
        <div className="flex items-center gap-3">
          <div className="flex gap-2">
            <Badge variant="outline" className="text-[0.6rem] text-green-500 border-green-500/20">
              Active {activeCount}
            </Badge>
            <Badge variant="outline" className="text-[0.6rem] text-amber-500 border-amber-500/20">
              Requested {requestedCount}
            </Badge>
          </div>
          <Button variant="outline" size="sm" onClick={load} disabled={loading} className="text-[0.65rem]">
            <RotateCw className={cn("mr-1.5 h-3.5 w-3.5", loading && "animate-spin")} />
            Refresh
          </Button>
        </div>
      </div>

      {loading ? (
        <div className="flex h-32 items-center justify-center text-sm opacity-60">
          <RotateCw className="mr-2 h-4 w-4 animate-spin" />
          Loading sessions...
        </div>
      ) : sessions.length === 0 ? (
        <Card>
          <CardContent className="p-6 text-center space-y-2">
            <Hand className="h-8 w-8 mx-auto opacity-40" />
            <div className="text-sm opacity-60">No active takeovers. Agents are running autonomously.</div>
          </CardContent>
        </Card>
      ) : (
        <div className="space-y-3">
          {sessions.map((s) => (
            <Card key={s.takeoverId} className={cn("overflow-hidden", s.status === "ACTIVE" && "border-green-500/30")}>
              <CardContent className="p-4 space-y-3">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <Hand className={cn("h-4 w-4", s.status === "ACTIVE" ? "text-green-500" : "text-amber-500")} />
                    <span className="font-medium text-sm">Takeover {s.takeoverId}</span>
                    {statusBadge(s.status)}
                  </div>
                  <span className="text-[0.65rem] opacity-50 font-mono">{s.runId}</span>
                </div>

                <div className="grid grid-cols-2 gap-2 text-[0.7rem]">
                  <div>
                    <span className="opacity-50">Operator:</span> {s.operatorId}
                  </div>
                  <div>
                    <span className="opacity-50">Team:</span> {s.teamId}
                  </div>
                </div>

                <div className="text-[0.65rem] opacity-50">
                  Started: {new Date(s.startedAt).toLocaleString()}
                </div>

                <div className="flex gap-2 pt-1">
                  {s.status === "REQUESTED" && (
                    <Button
                      size="sm"
                      className="text-[0.65rem]"
                      disabled={acting === s.takeoverId}
                      onClick={() => confirmTakeover(s.takeoverId)}
                    >
                      <Play className="h-3 w-3 mr-1" />
                      {acting === s.takeoverId ? "Confirming..." : "Confirm"}
                    </Button>
                  )}
                  {s.status === "ACTIVE" && (
                    <>
                      <Button size="sm" variant="outline" className="text-[0.65rem]">
                        <MessageSquare className="h-3 w-3 mr-1" /> Send Message
                      </Button>
                      <Button
                        size="sm"
                        variant="outline"
                        className="text-[0.65rem]"
                        disabled={acting === s.takeoverId}
                        onClick={() => releaseTakeover(s.takeoverId)}
                      >
                        <Square className="h-3 w-3 mr-1" />
                        {acting === s.takeoverId ? "Releasing..." : "Release"}
                      </Button>
                    </>
                  )}
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}
