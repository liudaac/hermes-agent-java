/**
 * Portal-local i18n — minimal. Defaults to zh-CN (business users);
 * the EN copy lives next to zh for parity, not for the marketing site.
 */
import { createContext, useContext, useMemo, useState, type ReactNode } from "react";

export type Locale = "zh-CN" | "en";

type Dict = Record<string, string>;

const zhCN: Dict = {
  "app.name": "数字员工",
  "app.tagline": "我的智能团队",
  "nav.home": "首页",
  "nav.teams": "数字员工",
  "nav.templates": "场景模板",
  "nav.approvals": "待审批",
  "nav.runs": "我的运行",
  "nav.insights": "自进化",
  "home.greetingMorning": "早上好",
  "home.greetingAfternoon": "下午好",
  "home.greetingEvening": "晚上好",
  "home.heroTitle": "你的数字员工",
  "home.heroSubtitle": "今天有什么可以帮你的？",
  "home.sectionTeams": "我的数字员工",
  "home.sectionToday": "今日任务",
  "home.sectionAttention": "需要关注",
  "home.sectionApprovals": "等待你的决定",
  "home.sectionRuns": "最近的运行",
  "home.sectionTemplates": "推荐场景",
  "home.seeAll": "查看全部",
  "home.emptyTeams": "还没有数字员工",
  "home.emptyTeamsHint": "从场景模板里挑一个，三步就能拥有你的第一位数字员工",
  "home.quickAction": "新建任务",
  "teams.title": "数字员工",
  "teams.addNew": "添加员工",
  "teams.empty": "还没有数字员工，从模板里挑一个开始",
  "teams.statusOnline": "在线",
  "teams.statusBusy": "执行中",
  "teams.statusOffline": "离线",
  "teams.runs": "运行次数",
  "teams.success": "成功率",
  "teams.lastRun": "最近运行",
  "templates.title": "场景模板",
  "templates.subtitle": "挑选一个场景，三步就能拥有数字员工",
  "templates.use": "使用此模板",
  "templates.clone": "克隆",
  "templates.uses": "人用过",
  "approvals.title": "待审批",
  "approvals.empty": "没有需要审批的项",
  "approvals.approve": "批准",
  "approvals.reject": "驳回",
  "approvals.risk": "风险",
  "approvals.requestedBy": "申请人",
  "runs.title": "我的运行",
  "runs.empty": "还没有运行",
  "runs.status": "状态",
  "runs.duration": "用时",
  "runs.startedAt": "开始于",
  "runs.viewDetail": "查看详情",
  "insights.title": "自进化",
  "insights.subtitle": "Hermes 从你的运行中学习的洞察",
  "insights.empty": "暂无洞察",
  "insights.severity.high": "高优先级",
  "insights.severity.medium": "中优先级",
  "insights.severity.low": "低优先级",
  "common.loading": "加载中…",
  "common.error": "出错了",
  "common.retry": "重试",
  "common.back": "返回",
  "common.cancel": "取消",
  "common.confirm": "确认",
  "common.now": "现在",
  "common.yes": "是",
  "common.no": "否",
  "common.more": "更多",
  "status.queued": "排队中",
  "status.running": "执行中",
  "status.succeeded": "已完成",
  "status.failed": "失败",
  "status.cancelled": "已取消",
  "status.waiting_approval": "等待审批",
  "status.blocked": "已拦截",
};

const en: Dict = {
  "app.name": "Hermes Portal",
  "app.tagline": "Your digital team",
  "nav.home": "Home",
  "nav.teams": "Teams",
  "nav.templates": "Templates",
  "nav.approvals": "Approvals",
  "nav.runs": "Runs",
  "nav.insights": "Insights",
  "home.greetingMorning": "Good morning",
  "home.greetingAfternoon": "Good afternoon",
  "home.greetingEvening": "Good evening",
  "home.heroTitle": "Your digital team",
  "home.heroSubtitle": "What can we do for you today?",
  "home.sectionTeams": "My team",
  "home.sectionToday": "Today",
  "home.sectionAttention": "Needs attention",
  "home.sectionApprovals": "Awaiting your decision",
  "home.sectionRuns": "Recent runs",
  "home.sectionTemplates": "Recommended scenarios",
  "home.seeAll": "See all",
  "home.emptyTeams": "No team members yet",
  "home.emptyTeamsHint": "Pick a scenario template — three steps to your first digital employee",
  "home.quickAction": "New task",
  "teams.title": "Digital team",
  "teams.addNew": "Add member",
  "teams.empty": "No team members yet — start with a template",
  "teams.statusOnline": "Online",
  "teams.statusBusy": "Running",
  "teams.statusOffline": "Offline",
  "teams.runs": "runs",
  "teams.success": "success",
  "teams.lastRun": "last run",
  "templates.title": "Scenarios",
  "templates.subtitle": "Pick a scenario — three steps to a new team member",
  "templates.use": "Use",
  "templates.clone": "Clone",
  "templates.uses": "in use",
  "approvals.title": "Approvals",
  "approvals.empty": "Nothing waiting",
  "approvals.approve": "Approve",
  "approvals.reject": "Reject",
  "approvals.risk": "Risk",
  "approvals.requestedBy": "Requested by",
  "runs.title": "Runs",
  "runs.empty": "No runs yet",
  "runs.status": "Status",
  "runs.duration": "Duration",
  "runs.startedAt": "Started",
  "runs.viewDetail": "View",
  "insights.title": "Evolution",
  "insights.subtitle": "Insights Hermes learned from your runs",
  "insights.empty": "No insights yet",
  "insights.severity.high": "High",
  "insights.severity.medium": "Medium",
  "insights.severity.low": "Low",
  "common.loading": "Loading…",
  "common.error": "Something went wrong",
  "common.retry": "Retry",
  "common.back": "Back",
  "common.cancel": "Cancel",
  "common.confirm": "Confirm",
  "common.now": "Now",
  "common.yes": "Yes",
  "common.no": "No",
  "common.more": "More",
  "status.queued": "Queued",
  "status.running": "Running",
  "status.succeeded": "Done",
  "status.failed": "Failed",
  "status.cancelled": "Cancelled",
  "status.waiting_approval": "Awaiting approval",
  "status.blocked": "Blocked",
};

const DICTS: Record<Locale, Dict> = { "zh-CN": zhCN, en };

interface I18nContextValue {
  locale: Locale;
  setLocale: (l: Locale) => void;
  t: (key: string) => string;
}

const I18nContext = createContext<I18nContextValue | null>(null);

function resolveKey(dict: Dict, key: string): string {
  if (Object.prototype.hasOwnProperty.call(dict, key)) {
    const v = dict[key];
    if (typeof v === "string") return v;
  }
  // Fall back to dot-path lookup over the flat dict.
  return dict[key] ?? key;
}

export function I18nProvider({ children, initial }: { children: ReactNode; initial?: Locale }) {
  const [locale, setLocale] = useState<Locale>(initial ?? "zh-CN");
  const value = useMemo<I18nContextValue>(() => {
    const dict = DICTS[locale];
    return {
      locale,
      setLocale,
      t: (key: string) => resolveKey(dict, key),
    };
  }, [locale]);
  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n(): I18nContextValue {
  const ctx = useContext(I18nContext);
  if (!ctx) throw new Error("useI18n must be used inside <I18nProvider>");
  return ctx;
}
