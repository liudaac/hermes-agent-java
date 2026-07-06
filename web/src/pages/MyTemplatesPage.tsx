import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  Upload, ArrowLeft, RefreshCw, Trash2, FileText, User, Sparkles,
} from "lucide-react";
import { api } from "@/lib/api";
import type { UserTemplateListItem } from "@/lib/api";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import { useToast } from "@/hooks/useToast";

const AGENT_SAMPLE = `template_id: my-custom-recruiter
name: 资深招聘官
role: Senior Sourcer
category: hr
icon: user-search
color: orange
mission: 高阶招聘漏斗管理 + 猎头网络
description: |
  专为中高端职位设计的招聘官。
skills:
  - 高管搜寻
  - 猎头网络维护
  - Offer 谈判
metrics:
  - label: 平均交付周期
    value: "30d"
allowed_tools: []
allowed_skills: []
risk_policy:
  high:
    - 跨主体调动
  medium: []
  low:
    - 候选人搜寻
`;

const SCENARIO_SAMPLE = `template_id: my-onboarding-fast-track
name: 快速入职跑道
category: hr
icon: user-plus
color: orange
summary: 5 天加速入职闭环
involved_agents:
  - template_id: hr-lifecycle-officer
    role_in_scenario: 主流程
clone_blueprint:
  team:
    name: 快速入职团队
  prompt_assets: []
  scenario:
    name: 快速入职
    description: 7→5 加速版本
    success_criteria:
      - D+5 试用期目标确认
`;

export default function MyTemplatesPage() {
  const navigate = useNavigate();
  const { showToast } = useToast();
  const [items, setItems] = useState<UserTemplateListItem[]>([]);
  const [root, setRoot] = useState("");
  const [loading, setLoading] = useState(true);
  const [type, setType] = useState<"agent" | "scenario">("agent");
  const [author, setAuthor] = useState("");
  const [yamlBody, setYamlBody] = useState(AGENT_SAMPLE);
  const [busy, setBusy] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      const res = await api.listUserTemplates();
      setItems(res.items ?? []);
      setRoot(res.root);
    } catch (e) {
      showToast(`加载失败：${String(e)}`, "error");
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => { load(); }, []);

  const upload = async () => {
    if (!yamlBody.trim()) return;
    setBusy(true);
    try {
      if (type === "agent") {
        await api.uploadUserAgentTemplate(yamlBody, author || "anonymous");
      } else {
        await api.uploadUserScenarioTemplate(yamlBody, author || "anonymous");
      }
      showToast("上传成功，模板已生效", "success");
      load();
    } catch (e) {
      showToast(`上传失败：${String(e)}`, "error");
    } finally {
      setBusy(false);
    }
  };

  const remove = async (item: UserTemplateListItem) => {
    if (!confirm(`确认删除模板 "${item.name || item.templateId}"？`)) return;
    try {
      await api.deleteUserTemplate(item.templateId);
      showToast("已删除", "success");
      load();
    } catch (e) {
      showToast(`删除失败：${String(e)}`, "error");
    }
  };

  return (
    <div className="space-y-5">
      <div className="aurora-bg flex flex-col gap-3 rounded-2xl border border-border/60 px-5 py-5 md:flex-row md:items-start md:justify-between md:px-7 md:py-6">
        <div>
          <div className="flex items-center gap-2 text-xs uppercase tracking-normal sm:tracking-[0.18em] opacity-70">
            <Sparkles className="h-4 w-4" /> 我的模板
          </div>
          <h1 className="mt-1 text-2xl font-semibold tracking-tight md:text-3xl">外部模板贡献</h1>
          <p className="mt-2 max-w-2xl text-sm text-muted-foreground">
            把你公司里的角色模板、行业场景上传到 Hermes，全公司一起用。
            上传即可生效，所有人都能在「数字员工」和「场景模板」里看到。
          </p>
          {root && <p className="mt-1 text-xs font-mono text-muted-foreground/70">存储位置：{root}</p>}
        </div>
        <div className="flex items-center gap-2">
          <Button variant="ghost" size="sm" onClick={() => navigate("/portal")}>
            <ArrowLeft className="mr-1 h-3.5 w-3.5" /> 返回 Portal
          </Button>
          <Button variant="outline" size="sm" onClick={load} disabled={loading}>
            <RefreshCw className={cn("mr-1.5 h-3.5 w-3.5", loading && "animate-spin")} /> 刷新
          </Button>
        </div>
      </div>

      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="flex items-center gap-2 text-base">
            <Upload className="h-4 w-4" /> 上传新模板
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="flex flex-wrap items-center gap-2">
            <div className="flex items-center gap-1">
              {(["agent", "scenario"] as const).map((t) => (
                <button key={t}
                  onClick={() => { setType(t); setYamlBody(t === "agent" ? AGENT_SAMPLE : SCENARIO_SAMPLE); }}
                  className={cn("rounded-full px-3 py-1 text-xs font-medium transition-colors",
                    type === t ? "bg-foreground text-background" : "bg-muted text-foreground hover:bg-muted/80")}>
                  {t === "agent" ? "数字员工角色" : "场景模板"}
                </button>
              ))}
            </div>
            <div className="h-4 w-px bg-border" />
            <div className="flex items-center gap-1">
              <User className="h-3.5 w-3.5 text-muted-foreground" />
              <input value={author} onChange={(e) => setAuthor(e.target.value)} placeholder="作者邮箱（可选）"
                className="rounded-md border border-border bg-background px-2 py-1 text-xs w-48" />
            </div>
            <div className="ml-auto">
              <Button size="sm" onClick={upload} disabled={busy || !yamlBody.trim()}>
                {busy ? "上传中…" : "上传并启用"}
              </Button>
            </div>
          </div>
          <textarea value={yamlBody} onChange={(e) => setYamlBody(e.target.value)}
            spellCheck={false} rows={12}
            className="w-full rounded-md border border-border bg-muted/30 px-3 py-2 font-mono text-xs leading-relaxed focus:outline-none focus:ring-1 focus:ring-foreground" />
          <p className="text-xs text-muted-foreground">
            提示：可参考 <code className="font-mono">resources/business-templates/SCHEMA.md</code> 中的字段规范。
            上传后会自动校验，缺字段或类型错误会被拒绝。
          </p>
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="flex items-center justify-between">
            <span className="flex items-center gap-2 text-base">
              <FileText className="h-4 w-4" /> 已上传的模板
            </span>
            <Badge variant="outline" className="text-xs">{items.length}</Badge>
          </CardTitle>
        </CardHeader>
        <CardContent>
          {items.length === 0 ? (
            <p className="py-6 text-center text-xs text-muted-foreground">
              还没有外部贡献模板。上传你的第一个吧 ✨
            </p>
          ) : (
            <ul className="space-y-2">
              {items.map((it) => (
                <li key={it.templateId} className="flex items-center gap-3 rounded-md border border-border/60 p-2.5">
                  <Badge variant="outline" className="text-xs uppercase tracking-wider">
                    {it.type}
                  </Badge>
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-sm font-medium">{it.name || it.templateId}</p>
                    <p className="truncate text-xs text-muted-foreground">
                      <code className="font-mono">{it.templateId}</code>
                      {it.category && <> · {it.category}</>}
                      {it.meta?.author && <> · {it.meta.author}</>}
                      {it.meta?.uploadedAt && <> · {new Date(it.meta.uploadedAt).toLocaleString()}</>}
                    </p>
                  </div>
                  <Button variant="ghost" size="icon" onClick={() => remove(it)}>
                    <Trash2 className="h-3.5 w-3.5 text-rose-500" />
                  </Button>
                </li>
              ))}
            </ul>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
