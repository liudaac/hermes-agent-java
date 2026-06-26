import {
  UserSearch,
  CalendarClock,
  Workflow,
  BadgeDollarSign,
  HeartHandshake,
  FileText,
  Receipt,
  ClipboardCheck,
  BookOpen,
  LineChart,
  PenTool,
  BarChart2,
  Headphones,
  Palette,
  Code2,
  TrendingUp,
  Briefcase,
  Users,
  type LucideIcon,
} from "lucide-react";

/** Map icon name from template YAML → lucide-react component. */
const ICON_MAP: Record<string, LucideIcon> = {
  "user-search": UserSearch,
  "calendar-clock": CalendarClock,
  workflow: Workflow,
  "badge-dollar-sign": BadgeDollarSign,
  "heart-handshake": HeartHandshake,
  "file-text": FileText,
  receipt: Receipt,
  "clipboard-check": ClipboardCheck,
  "book-open": BookOpen,
  "line-chart": LineChart,
  "pen-tool": PenTool,
  "bar-chart-2": BarChart2,
  headphones: Headphones,
  palette: Palette,
  "code-2": Code2,
  "trending-up": TrendingUp,
  briefcase: Briefcase,
  users: Users,
};

export function iconFor(name?: string): LucideIcon {
  if (!name) return Briefcase;
  return ICON_MAP[name] ?? Briefcase;
}

/** Map color name → tailwind/oklch tokens for the agent card chrome. */
export const COLOR_CLASSES: Record<
  string,
  { ring: string; bg: string; text: string; chip: string }
> = {
  orange: {
    ring: "ring-orange-500/30",
    bg: "bg-orange-500/10",
    text: "text-orange-600 dark:text-orange-400",
    chip: "bg-orange-500/10 text-orange-700 dark:text-orange-300",
  },
  green: {
    ring: "ring-emerald-500/30",
    bg: "bg-emerald-500/10",
    text: "text-emerald-600 dark:text-emerald-400",
    chip: "bg-emerald-500/10 text-emerald-700 dark:text-emerald-300",
  },
  blue: {
    ring: "ring-sky-500/30",
    bg: "bg-sky-500/10",
    text: "text-sky-600 dark:text-sky-400",
    chip: "bg-sky-500/10 text-sky-700 dark:text-sky-300",
  },
  purple: {
    ring: "ring-purple-500/30",
    bg: "bg-purple-500/10",
    text: "text-purple-600 dark:text-purple-400",
    chip: "bg-purple-500/10 text-purple-700 dark:text-purple-300",
  },
  yellow: {
    ring: "ring-amber-500/30",
    bg: "bg-amber-500/10",
    text: "text-amber-600 dark:text-amber-400",
    chip: "bg-amber-500/10 text-amber-700 dark:text-amber-300",
  },
  red: {
    ring: "ring-rose-500/30",
    bg: "bg-rose-500/10",
    text: "text-rose-600 dark:text-rose-400",
    chip: "bg-rose-500/10 text-rose-700 dark:text-rose-300",
  },
  teal: {
    ring: "ring-teal-500/30",
    bg: "bg-teal-500/10",
    text: "text-teal-600 dark:text-teal-400",
    chip: "bg-teal-500/10 text-teal-700 dark:text-teal-300",
  },
  pink: {
    ring: "ring-pink-500/30",
    bg: "bg-pink-500/10",
    text: "text-pink-600 dark:text-pink-400",
    chip: "bg-pink-500/10 text-pink-700 dark:text-pink-300",
  },
};

export function colorsFor(name?: string) {
  return COLOR_CLASSES[name ?? "orange"] ?? COLOR_CLASSES.orange;
}

/** Friendly Chinese label for a template category. */
export const CATEGORY_LABELS: Record<string, string> = {
  hr: "人力资源",
  finance: "财务",
  assets: "固定资产",
  logistics: "物流",
  general: "通用",
};

export function categoryLabel(category?: string): string {
  if (!category) return "通用";
  return CATEGORY_LABELS[category] ?? category;
}
