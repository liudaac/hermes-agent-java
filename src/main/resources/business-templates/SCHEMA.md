# Business Templates · Schema 规范 v1

> 角色模板和场景模板的 YAML 规范。所有文件在启动时由 `AgentTemplateLoader` / `ScenarioTemplateLoader` 自动扫描加载。

## 目录约定

```
business-templates/
├── SCHEMA.md                          ← 本文件
├── agents/
│   ├── hr/
│   ├── finance/
│   ├── assets/
│   └── logistics/
└── scenarios/
    ├── *.yaml                          ← 单领域场景
    └── cross-domain/*.yaml             ← 跨域旗舰场景
```

## 1. 角色模板 schema (`agents/<category>/<id>.yaml`)

```yaml
# 必填字段
template_id: hr-talent-sourcer            # 唯一 ID（kebab-case，建议 <category>-<role>）
name: 招聘官                              # 业务化名称
role: Talent Sourcer                      # 英文 role
category: hr                              # hr | finance | assets | logistics | general
status: STABLE                            # STABLE | BETA | EXPERIMENTAL

# UI 展示
icon: user-search                         # lucide-react icon name
color: orange                             # orange | green | blue | purple | yellow | red | teal | pink
mission: 高效完成 JD 撰写、简历筛选与初筛  # 一句话使命
description: |                            # 多行描述，业务化语言
  你的专属招聘伙伴。从写一份能吸引人的 JD 开始……

# 能力清单
skills:                                   # 业务化技能列表（5-8 项）
  - JD 撰写与优化
  - 简历多维筛选

metrics:                                  # 关键指标（3 项）
  - label: 简历处理量
    value: "500+"
    unit: 份/日
  - label: 初筛通过率
    value: "32%"

# 运行时绑定（落到 TeamBlueprint）
allowed_tools: []                         # 允许调用的 tool id
allowed_skills: []                        # 允许调用的 skill id
instructions: |                           # 系统提示词（注入到 prompt asset）
  你是一位资深 HR 招聘官……

handoff_policy:                           # 交接规则（可选）
  default_target: hr-interview-coordinator
  triggers:
    - condition: "candidate.score >= 70"
      target: hr-interview-coordinator

# 风险分级
risk_policy:
  high: ["对外发布 JD", "批量沟通 > 50 人"]
  medium: ["向单个候选人发邀约"]
  low: ["简历筛选", "知识库答疑"]

# H5 风格的演示工作流
demo_workflow:
  - step: 1
    actor: 招聘官
    action: 解析 JD 与岗位画像
    duration: 2s
  - step: 2
    actor: 招聘官
    action: 从人才池匹配 50 份简历
    duration: 5s
```

## 2. 场景模板 schema (`scenarios/<id>.yaml`)

```yaml
template_id: hr-onboarding-7day
name: 7 天入职闭环
category: hr                              # 或 cross-domain
status: STABLE
industry_tag: 人力资源
icon: user-plus
color: orange

summary: 从合同电签到首日工作就绪，7 天端到端自动驱动
description: |
  覆盖入职前 / 入职日 / 第一周三阶段……

# 量化收益（用于模板卡片）
metrics:
  - label: 入职周期
    value: "3d → 30min"
  - label: 错单率
    value: "↓ 87%"

# 涉及的角色模板（按出场顺序）
involved_agents:
  - template_id: hr-lifecycle-officer
    role_in_scenario: 流程驱动
  - template_id: hr-talent-sourcer
    role_in_scenario: 候选人对接

# 克隆时要生成的对象
clone_blueprint:
  team:
    name: 入职闭环团队
    description: 7 天入职流程驱动团队
  prompt_assets:                          # 自动创建的 prompt assets
    - asset_id: onboarding-checklist
      name: 入职清单
      content: "新员工 7 天必备事项……"
  scenario:
    name: 7 天入职闭环
    description: 端到端入职流程
    success_criteria:
      - 试用期目标 100% 设定
      - 高风险动作（合同/薪酬）走审批

# 时间线（用于 Run 故事化展示）
workflow_timeline:
  - t: T+0
    actor: HR
    action: 录入新员工基本信息
  - t: T+5min
    actor: 入离调转专员
    action: 启动入职流程、生成检查清单
  - t: T+10min
    actor: 入离调转专员
    action: 触发合同电签 → 待签
  - t: T+30min
    actor: 入离调转专员
    action: 通知 IT 准备资产、HR 安排培训
```

## 3. 颜色映射

为前端 `AgentRoleCard` 提供一致配色：

| color | OKLCH 主色 | 用途 |
|---|---|---|
| orange | `oklch(0.78 0.16 70)` | HR / 内容总监 |
| green | `oklch(0.72 0.14 145)` | 财务 / 数据 |
| blue | `oklch(0.70 0.14 210)` | 物流 / 研发 |
| purple | `oklch(0.70 0.16 280)` | 设计 / 创意 |
| yellow | `oklch(0.78 0.16 85)` | 固资 / 客服 |
| red | `oklch(0.65 0.18 25)` | 风控 / 异常 |
| teal | `oklch(0.72 0.14 190)` | 分析 / 洞察 |
| pink | `oklch(0.72 0.16 350)` | 营销 / 公关 |

## 4. 校验规则

启动时校验：
- `template_id` 唯一
- `category` ∈ 枚举
- `status` ∈ 枚举
- `skills` ≥ 3 项
- `metrics` ≥ 3 项
- 场景模板的 `involved_agents.template_id` 必须能解析到已加载的角色

## 5. 热加载

支持 `POST /api/v1/business/agent-templates/reload` 触发重新扫描（dev 模式）。
