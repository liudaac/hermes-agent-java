# Hermes Agent Java 前端工程优化报告

> 分析范围：`/root/hermes-agent-java/web/` 目录下的 React + Tailwind CSS 前端工程
> 分析日期：2026-05-27

---

## 一、高频重复模式（最严重）

### 1. Loading Spinner — 8+ 处完全复制粘贴

几乎每个页面都有这一段完全相同的 loading UI：

```tsx
<div className="flex items-center justify-center py-24">
  <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
</div>
```

**出现位置**：StatusPage、ConfigPage、SessionsPage、SkillsPage、LogsPage、CronPage、EnvPage、AnalyticsPage

**优化方案**：提取为 `<LoadingSpinner />` 或 `<PageLoader />` 组件。

```tsx
// components/LoadingSpinner.tsx
export function LoadingSpinner({ className }: { className?: string }) {
  return (
    <div className={cn("flex items-center justify-center py-24", className)}>
      <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
    </div>
  );
}
```

---

### 2. "Live" 脉冲 Badge — 6+ 处重复

```tsx
<Badge variant="success" className="text-[10px]">
  <span className="mr-1 inline-block h-1.5 w-1.5 animate-pulse rounded-full bg-current" />
  {t.common.live}
</Badge>
```

**出现位置**：StatusPage（多处）、SessionsPage、LogsPage、AnalyticsPage

**优化方案**：提取为 `<LiveBadge />` 组件。

```tsx
// components/LiveBadge.tsx
export function LiveBadge({ label = "live" }: { label?: string }) {
  return (
    <Badge variant="success" className="text-[10px]">
      <span className="mr-1 inline-block h-1.5 w-1.5 animate-pulse rounded-full bg-current" />
      {label}
    </Badge>
  );
}
```

---

### 3. CardHeader + Icon + Title 组合 — 几乎每个页面

```tsx
<CardHeader>
  <div className="flex items-center gap-2">
    <SomeIcon className="h-5 w-5 text-muted-foreground" />
    <CardTitle className="text-base">{title}</CardTitle>
  </div>
</CardHeader>
```

**优化方案**：扩展 `CardTitle` 或新增 `CardHeaderWithIcon`。

```tsx
// components/ui/card.tsx
export function CardHeaderIcon({
  icon: Icon,
  title,
  className,
}: {
  icon: React.ComponentType<{ className?: string }>;
  title: React.ReactNode;
  className?: string;
}) {
  return (
    <CardHeader className={className}>
      <div className="flex items-center gap-2">
        <Icon className="h-5 w-5 text-muted-foreground" />
        <CardTitle className="text-base">{title}</CardTitle>
      </div>
    </CardHeader>
  );
}
```

---

### 4. Toast 的复合使用模式 — 所有页面

每个页面都重复：
```tsx
import { useToast } from "@/hooks/useToast";
import { Toast } from "@/components/Toast";
// ...
const { toast, showToast } = useToast();
// ...
<Toast toast={toast} />
```

**优化方案**：将 Toast 提升到 App 层级（全局一次渲染），页面只调用 `showToast` hook。

```tsx
// App.tsx
import { Toast } from "@/components/Toast";
import { ToastProvider } from "@/hooks/useToast";

// 包裹在 ThemeProvider 内
<ToastProvider>
  <App />
  <GlobalToast />  {/* 全局渲染一次 */}
</ToastProvider>
```

---

## 二、样式冗余

### 5. 内联 `minHeight: "calc(100vh - 180px)"` — 3+ 处

ConfigPage、SkillsPage、LogsPage 都用了这个相同的内联样式来控制侧边栏布局高度。

**优化方案**：提取为 CSS class 或布局组件。

```css
/* index.css */
.layout-with-sidebar {
  min-height: calc(100vh - 180px);
}
```

或封装 `<SidebarLayout />` 组件。

---

### 6. Sidebar 导航项样式 — 3 个页面几乎相同

ConfigPage、SkillsPage、LogsPage 的侧边栏导航按钮样式逻辑高度相似：
- active 状态：`bg-primary/10 text-primary font-medium`
- inactive 状态：`text-muted-foreground hover:text-foreground hover:bg-muted/50`
- 都包含 ChevronRight 指示器
- 都包含计数 badge

**优化方案**：提取 `<SidebarNav />` / `<SidebarItem />` 通用组件。

---

### 7. 表格样式重复 — AnalyticsPage

`DailyTable`、`ModelTable`、`SkillTable` 三个表格的表头样式几乎完全相同：

```tsx
<thead>
  <tr className="border-b border-border text-muted-foreground text-xs">
    <th className="text-left py-2 pr-4 font-medium">...</th>
    ...
  </tr>
</thead>
```

**优化方案**：提取 `<DataTable />` 或 `<TableHeader />` 组件。

---

### 8. index.css 中定义的 class 未被充分利用

`index.css` 中定义了：
```css
.blend-lighter { mix-blend-mode: plus-lighter; }
.font-mono-ui { font-family: ui-monospace, ...; }
```

但代码中很多地方直接内联 `style={{ mixBlendMode: "plus-lighter" }}`，没有使用 class。

**优化方案**：统一改用 class。

---

## 三、硬编码颜色值（主题兼容性风险）

### 9. AnalyticsPage 图表硬编码颜色

```tsx
// TokenBarChart
<div className="h-2.5 w-2.5 bg-[#ffe6cb]" />   {/* input bar color */}
<div className="h-2.5 w-2.5 bg-emerald-500" />  {/* output bar color */}
<div className="w-full bg-[#ffe6cb]/70" />      {/* input bar */}
<div className="w-full bg-emerald-500/70" />    {/* output bar */}
<span className="text-[#ffe6cb]">...</span>     {/* input tokens */}
<span className="text-emerald-400">...</span>   {/* output tokens */}
```

这些颜色在主题切换后不会跟随变化。当用户切换到 Cyberpunk（绿色主题）或 Ember（红色主题）时，图表颜色仍然显示为 teal/cream，视觉不协调。

**优化方案**：使用 CSS 变量或主题 token。

```css
/* index.css 中添加 */
@theme inline {
  --color-chart-input: var(--midground);
  --color-chart-output: var(--color-success);
}
```

```tsx
<div className="w-full bg-chart-input/70" />
<div className="w-full bg-chart-output/70" />
```

---

## 四、代码层面的冗余

### 10. `timeAgo` 和 `isoTimeAgo` 逻辑重复

`lib/utils.ts` 中两个函数的逻辑几乎相同，只是输入格式不同。

```ts
export function timeAgo(ts: number): string { ... }
export function isoTimeAgo(iso: string): string { ... }
```

**优化方案**：`isoTimeAgo` 可以复用 `timeAgo`。

```ts
export function isoTimeAgo(iso: string): string {
  const ts = new Date(iso).getTime() / 1000;
  if (Number.isNaN(ts)) return "unknown";
  return timeAgo(ts);
}
```

---

### 11. App.tsx 中 ICON_MAP 和顶部 import 重复

```tsx
// 顶部已经单独 import 了所有图标
import { Activity, BarChart3, Clock, ... } from "lucide-react";

// 后面又重复声明 ICON_MAP
const ICON_MAP: Record<string, ...> = {
  Activity, BarChart3, Clock, ...  // 同样的列表
};
```

**优化方案**：使用动态 import 或简化映射逻辑。

```tsx
// 方案：从 lucide-react 动态获取，减少维护负担
import * as Icons from "lucide-react";

function resolveIcon(name: string) {
  const icon = (Icons as Record<string, React.ComponentType>)[name];
  return icon ?? Icons.Puzzle;
}
```

> 注意：需要 tree-shaking 友好的话，可以保持原方案但用脚本自动生成 ICON_MAP。

---

### 12. `useToast` hook 与 Toast 组件的紧耦合

当前每个页面都同时 import hook 和组件。Toast 组件用 `createPortal` 渲染到 body，这已经是全局行为，没必要每个页面都放 `<Toast toast={toast} />`。

---

### 13. EnvPage 中 `EnvVarRow` 多分支重复 JSX

`EnvVarRow` 组件有 compact/unset/set/editing 四个状态分支，很多 HTML 结构（按钮组、链接、Label 等）在不同分支中重复。

**优化方案**：将共通部分提取为子组件。

---

## 五、优化优先级总结

| 优先级 | 项目 | 影响 | 工作量 |
|--------|------|------|--------|
| 🔴 P0 | 提取 `<LoadingSpinner />` | 8+ 处重复，维护成本高 | 10 min |
| 🔴 P0 | Toast 提升到全局 | 所有页面受益，减少样板代码 | 20 min |
| 🟡 P1 | 提取 `<LiveBadge />` | 6+ 处重复 | 5 min |
| 🟡 P1 | 提取 `<CardHeaderIcon />` | 几乎每个页面 | 10 min |
| 🟡 P1 | 提取 `<SidebarLayout />` / `<SidebarItem />` | 3 个页面布局一致 | 30 min |
| 🟡 P1 | 图表硬编码颜色改为 CSS 变量 | 主题切换兼容性 | 15 min |
| 🟢 P2 | 提取 `<DataTable />` | AnalyticsPage 内 3 个表格 | 20 min |
| 🟢 P2 | `timeAgo` / `isoTimeAgo` 合并 | 代码简化 | 5 min |
| 🟢 P2 | `minHeight` 内联样式提取为 class | 代码整洁 | 5 min |
| 🔵 P3 | 统一使用 `.blend-lighter` class | 代码一致性 | 10 min |
| 🔵 P3 | App.tsx ICON_MAP 优化 | 减少维护 | 10 min |
| 🔵 P3 | EnvVarRow 分支重构 | 可读性 | 30 min |

---

## 六、推荐实施顺序

```
Phase 1 (30min): LoadingSpinner + LiveBadge + CardHeaderIcon + timeAgo 合并
Phase 2 (30min): Toast 全局化
Phase 3 (1h):     SidebarLayout 组件 + DataTable 组件
Phase 4 (30min):  图表颜色变量化 + 剩余样式清理
```

预计总计 **2-3 小时**可将代码冗余降低约 40%，并提升主题切换的视觉一致性。
