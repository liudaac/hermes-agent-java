# Hermes Agent Java：业务智能体团队平台方案

> 一句话：让业务人员像“搭建一个小团队”一样创建智能体团队；让系统像“有经验的业务主管”一样自动分工、跟进结果、发现问题、提出改进，但所有高风险动作都可审计、可审批、可回滚。

---

## 0. 阅读指南：不同角色怎么看这份方案

这份文档同时服务三类读者：

| 读者 | 建议重点阅读 | 你会得到什么 |
|---|---|---|
| 业务负责人 / 运营 / 客服主管 / 非技术同学 | 1、2、3、4、16 | 看懂平台能解决什么问题、怎么使用、为什么安全可靠 |
| 产品 / 解决方案 / 售前 | 1、2、4、5、6、8、16 | 看懂产品卖点、业务场景、智能体验和边界 |
| 技术 / 架构 / 开发 | 3、5、9、10、11、12、13、14、15 | 看懂对象模型、API、MVP 范围和开发路线 |

文档表达约定：

```text
业务空间 = 某个业务部门/项目自己的工作区
智能体团队 = 一组有分工的 AI 数字员工
场景 = 一个具体业务任务，例如“售后工单处理”
运行记录 = 一次任务从输入到结果的完整过程
进化提案 = 系统发现问题后提出的优化建议
```

---

## 1. 背景与目标：从“会聊天的 AI”到“会做事的业务团队”

很多业务团队并不缺 AI 聊天工具，真正缺的是：

```text
能理解业务目标
能按流程协作
能调用业务系统
能留下过程记录
能遇到风险时请求审批
能根据反馈持续变好
```

Hermes Agent Java 已经具备多租户、Agent 团队、Org Control Center、Skill、BrowserBridge、Delegated Task、安全审计等工程基础。下一阶段要做的不是再堆更多技术模块，而是把这些能力包装成业务人员能理解、能配置、敢上线的“智能体团队平台”。

业务方最终应该能完成这些动作：

1. 创建一个属于自己部门或项目的 **业务空间**；
2. 像搭建岗位一样创建 **智能体团队**，例如分类员、政策专家、质检员、审批员；
3. 给每个智能体配置职责、知识、可用工具、审批边界；
4. 通过页面或 API 把真实业务任务交给团队处理；
5. 查看每次任务的处理过程、依据、成本和风险；
6. 根据系统自动生成的优化建议，审批新版本并灰度上线。

愿景：

> 业务定义目标和边界，系统自动组织智能体团队完成任务，并在治理约束下持续自我改进。

这不是“AI 替人拍脑袋”，而是“AI 进入业务组织，成为可管理、可协作、可复盘的数字员工团队”。

---

## 2. 总体定位：智能组织操作系统

平台不只是一个 Agent 执行器，而是一个面向业务的 **智能组织操作系统**。

如果用业务语言描述，它分成三层：

| 层级 | 业务理解 | 平台能力 |
|---|---|---|
| 业务层 | 我有什么业务、目标、规则和权限边界 | Workspace / Scenario / 目标 / 权限 / 数据源 |
| 协作层 | 谁来做、怎么分工、什么时候交接 | Agent Team / Orchestrator / Workflow / Tool & Skill Routing |
| 进化层 | 做得好不好、哪里错了、怎么变好 | Eval / Trace / Feedback / Evolution Proposal / Versioning / Canary |

核心原则：

```text
业务方定义：场景、目标、成功标准、权限边界、审批规则
系统负责：自动拆解任务、选择智能体、调用工具、记录过程、生成结果
进化系统负责：发现问题、生成优化建议、跑评估、提出版本草案
人或治理策略负责：审批高风险变更、灰度发布、回滚
```

平台坚决不做：

```text
AI 自己直接改生产智能体
AI 自己直接扩大权限
AI 自己直接发布高风险变更
AI 在没有记录的情况下执行关键动作
```

推荐模式：

```text
AI 发现问题
  ↓
生成进化提案
  ↓
评估验证
  ↓
安全检查
  ↓
人工/策略审批
  ↓
版本化发布
```

---

## 2.1 给业务人员的一分钟例子

以“售后工单处理”为例，业务人员看到的是：

```text
我创建一个“客服业务空间”
  ↓
选择“售后工单处理”模板
  ↓
平台生成一个团队草案：
- 工单分类智能体
- 订单查询智能体
- 售后政策智能体
- 回复文案智能体
- 风险审批智能体
  ↓
我补充公司售后政策、退款规则、审批金额
  ↓
点击发布 v1
  ↓
业务系统把工单发进来
  ↓
智能体团队自动处理，并把高风险事项交给人审批
```

业务人员不需要理解 Tenant、API、Trace、Eval 这些技术词。页面应该呈现为：

```text
创建空间 → 选择场景 → 配置团队 → 设置边界 → 试运行 → 发布 → 查看效果 → 审批优化
```

---

## 2.2 领先智感：平台应该呈现出来的“聪明”

所谓“智感”不是炫技动画，而是让业务人员明显感觉：系统懂业务、会追问、能解释、可托付。

### 2.2.1 创建时：像顾问一样帮业务补全方案

当业务人员输入：

```text
我想做一个售后工单处理团队
```

平台不应该只给空表单，而应该自动建议：

```text
推荐团队角色：分类、订单查询、政策判断、回复生成、风险审批
推荐成功标准：分类准确率、退款规则命中率、人工纠正率、平均处理时长
推荐审批规则：退款金额 > 1000 元必须人工审批
推荐知识库：售后政策、商品类目规则、物流异常规则、历史优秀回复
```

### 2.2.2 配置时：把技术配置翻译成业务语言

不要让业务方直接面对：

```text
tools / skills / route / trace / eval / tenant policy
```

应该翻译为：

```text
这个团队能查哪些系统？
哪些事情可以自动做？
哪些事情必须请人确认？
回答必须遵守哪些规则？
怎样算完成得好？
出了问题找谁处理？
```

### 2.2.3 运行时：像项目经理一样给出可读过程

一次任务完成后，业务方不只看到最终结果，还能看到：

```text
工单被判断为：退款问题
判断依据：用户提到“收到货三天，想退货”
查询结果：订单已签收 3 天，商品未标记特殊类目
政策匹配：符合 7 天无理由退款初步条件
风险判断：退款金额 89 元，无需人工审批
最终建议：引导用户提交退货申请
```

### 2.2.4 复盘时：像业务分析师一样发现问题

平台应该主动提示：

```text
最近 7 天有 12 条工单被人工纠正
主要集中在“生鲜商品退款”场景
建议新增一个“特殊类目政策判断”步骤
是否生成团队 v2 草案？
```

### 2.2.5 发布时：像风控系统一样稳

所有重要变化都应该让业务方放心：

```text
新版本改了什么
为什么改
用哪些历史案例测试过
预计影响哪些场景
先给 5% 流量试运行
异常时自动回到旧版本
```

这就是平台的领先感：不是“AI 很能聊”，而是“AI 懂组织、懂流程、懂风险、懂迭代”。

---

## 2.3 业务友好术语表

| 技术词 | 业务说法 | 解释 |
|---|---|---|
| Tenant | 业务空间 | 某个部门/项目独立使用的一块空间，数据和权限隔离 |
| Agent | 智能体 / 数字员工 | 承担某个明确职责的 AI 角色 |
| Agent Team | 智能体团队 | 多个智能体按分工协作完成任务 |
| Blueprint | 团队蓝图 | 团队岗位、职责、规则、工具和知识范围的配置方案 |
| Version | 团队版本 | 每次发布后的团队配置快照，可回滚 |
| Scenario | 业务场景 | 一个具体业务任务，例如售后处理、线索跟进、合同初审 |
| Run | 一次处理记录 | 某个任务从输入到输出的完整执行过程 |
| Trace | 过程轨迹 | 系统每一步怎么判断、调用了什么、依据是什么 |
| Eval | 效果评估 | 用案例集测试团队是否做得更好 |
| Evolution Proposal | 优化建议 / 进化提案 | 系统基于错误和反馈提出的改进草案 |
| Skill / Tool | 可用能力 | 查询订单、打开网页、发消息、调用内部系统等能力 |
| Approval | 人工审批 | 高风险动作必须经过人或规则确认 |
| Canary | 灰度发布 | 新版本先小范围试用，稳定后再扩大 |
| Standing Orders | 常驻任务指令 | 业务方提前授予的长期工作规则，例如每天巡检、每周复盘 |
| Prompt Asset | 提示词资产 | 可版本化、可评估、可复用的角色说明、SOP、追问模板、风控提示 |
| Task Flow | 任务流 | 多步骤任务的持久化编排，例如收集资料→生成建议→审批→发布 |
| Active Memory | 主动记忆 | 在回复或执行前主动召回相关业务背景、历史决策和用户偏好 |
| Execute-Verify-Report | 执行-验证-汇报 | 每个任务必须先执行、再验证、最后汇报结果和证据 |

---

## 3. 平台能力地图：业务方能用它做什么

对业务人员来说，平台不是一堆 Agent、Tool、Workflow，而是一套可以把业务经验沉淀成“可运行团队”的系统。

### 3.1 业务人员看到的能力

| 业务动作 | 平台表现 | 背后能力 |
|---|---|---|
| 创建一个业务空间 | 为客服、销售、法务、运营等团队创建独立空间 | 多租户隔离、权限、审计、配额 |
| 选择一个业务场景 | 售后工单、线索跟进、合同初审、内容审核等模板 | Scenario / Workflow |
| 搭建智能体团队 | 像配置岗位一样配置分类员、专家、质检员、审批员 | Agent Team Blueprint |
| 告诉团队怎么做事 | 填写规则、SOP、知识库、成功标准 | Instructions / Knowledge / Eval |
| 设置哪些事不能自动做 | 退款、外发消息、改数据库、真实账号操作等必须审批 | Approval Policy / Tool Policy |
| 试运行并看过程 | 看到每一步判断、依据、调用了什么系统 | Trace / Audit |
| 发布一个稳定版本 | v1、v2、v3 可回滚，可灰度 | Versioning / Canary |
| 让团队越做越好 | 系统根据错误和反馈提出优化草案 | Evolution Proposal |

### 3.2 业务侧使用旅程

```text
第一步：说清楚业务目标
例如：我想让 AI 帮客服处理售后退款咨询。

第二步：选择或生成团队模板
平台推荐：分类、订单查询、政策判断、回复生成、风险审批。

第三步：补充业务规则
例如：7 天无理由、生鲜不支持无理由、退款超过 1000 元要审批。

第四步：试运行
用历史工单测试，查看每一步判断是否符合业务预期。

第五步：发布
发布 v1，并设置只处理低风险工单或小流量灰度。

第六步：复盘和进化
系统发现常见错误，生成 v2 草案，业务方审核后再上线。
```

### 3.3 非技术用户的界面原则

页面不应该让业务人员填写技术字段，而应该围绕业务问题组织：

```text
你要解决什么业务问题？
这个团队需要哪些岗位？
每个岗位负责什么？
能查哪些资料和系统？
哪些动作必须人工确认？
怎样算处理得好？
上线前用哪些案例测试？
出了问题如何回退？
```

推荐页面结构：

```text
业务空间首页
├── 当前运行中的业务场景
├── 智能体团队列表
├── 今日处理量 / 成功率 / 人工介入率
├── 待审批事项
├── 系统发现的问题
└── 推荐优化建议
```

### 3.4 业务入口与技术 Dashboard 必须隔离

当前 Dashboard 更偏技术/运维/开发视角，里面天然会出现模型、配置、工具、日志、租户、网关、会话、Trace、环境变量等信息。这些能力对工程团队很重要，但不适合作为业务人员的默认入口。

原则判断：

> Dashboard 是驾驶舱和机房；Business Portal 是业务前台。两者可以共享底层能力，但不应该混成同一个产品入口。

如果业务人员从技术 Dashboard 进入，容易出现几个问题：

```text
术语门槛高：Tenant、Tool、Trace、Gateway、Session 等概念难理解
信息噪声大：业务只关心场景、团队、效果和待办，不关心底层日志
风险感强：看到模型、环境变量、工具开关，会误以为系统很“工程化”和不成熟
操作路径长：业务要绕过大量技术配置才能完成一个业务目标
责任边界不清：业务配置和运维配置混在一起，容易误操作
```

因此建议做双入口：

| 入口 | 面向用户 | 核心目标 | 典型内容 |
|---|---|---|---|
| Technical Dashboard | 开发、运维、管理员、平台团队 | 配置、诊断、观测、调试、治理 | 模型、工具、日志、Session、Gateway、Tenant、Env、Trace 原始数据 |
| Business Portal / Workspace Portal | 业务负责人、运营、客服主管、场景 owner | 创建团队、配置场景、查看效果、处理审批、推动优化 | 业务空间、场景模板、智能体团队、业务规则、运行看板、待审批、优化建议 |

### 3.4.1 Business Portal 应该呈现成熟能力，而不是暴露底层复杂度

业务入口只展示“可被业务可靠使用”的能力。实验性能力、底层开关、调试入口应该默认隐藏。

业务入口推荐导航：

```text
业务首页
业务场景
智能体团队
知识与规则
运行记录
审批中心
效果复盘
优化建议
发布管理
```

技术 Dashboard 推荐导航：

```text
系统状态
模型配置
工具与技能
租户管理
Session / Logs
Gateway 控制
环境变量
Org Control Center
Trace / Diagnostics
```

两者底层可以复用同一组服务：

```text
WorkspaceService
TeamBlueprintService
ScenarioService
RunService
ApprovalService
EvolutionService
AuditService
```

但 UI 层必须分离：

```text
Business Portal：业务语言、低信息噪声、模板化、成熟路径
Technical Dashboard：技术语言、高可观测性、完整配置、诊断能力
```

### 3.4.2 成熟度分层：业务只看“可用能力”，技术可看“全部能力”

建议每个能力标记成熟度：

```text
STABLE：业务入口可见，可正式使用
BETA：业务入口可选择开启，有提示和回滚
EXPERIMENTAL：仅技术 Dashboard 可见
INTERNAL：仅开发/运维可见
```

示例：

| 能力 | Business Portal | Technical Dashboard |
|---|---|---|
| 创建业务空间 | 可见 | 可见 |
| 创建智能体团队 | 可见 | 可见 |
| 配置模型 Provider | 不可见 | 可见 |
| 查看原始 Trace | 默认不可见，只显示业务解释 | 可见 |
| 修改环境变量 | 不可见 | 可见 |
| 技能安装/调试 | 只显示业务能力开关 | 可见完整调试 |
| 自进化提案审批 | 可见 | 可见完整执行链路 |
| Gateway 重启 | 不可见 | 可见 |

### 3.4.3 权限模型也要隔离

业务用户和技术用户的权限不应该只是菜单隐藏，而应该是后端权限隔离。

建议角色：

```text
Business Viewer：查看运行效果和记录
Business Operator：处理审批、纠正结果、触发试运行
Business Owner：创建场景、发布团队版本、审批优化建议
Platform Admin：管理模型、工具、技能、租户、安全策略
Developer / Operator：查看日志、Trace、环境变量、Gateway 状态
```

关键规则：

```text
业务用户不能直接修改模型 Provider
业务用户不能直接打开高风险工具权限
业务用户不能查看环境变量和密钥
业务用户不能重启 Gateway 或修改系统配置
业务用户看到的 Trace 必须被翻译成业务过程说明
技术用户可以进入 Dashboard 诊断，但不能绕过业务审批发布生产版本
```

### 3.4.4 Business Portal 的视觉与交互风格：时尚、动效、易操作

业务入口不能做成传统后台管理系统。它要让业务用户一打开就感到：这是一个成熟、先进、可信、愿意每天使用的智能工作台。

设计目标：

```text
高时尚感：视觉上有现代 AI 产品气质，不像传统表格后台
强动效：通过动效表达智能体正在协作、任务正在推进、风险正在被控制
易操作：业务用户不需要学习技术概念，也能按向导完成配置和发布
高可解释：所有智能判断都有业务化解释和可点击依据
低焦虑：高风险动作清晰标记，审批和回滚路径明确
移动友好：手机上也能看状态、批审批、跑试运行、处理异常
```

#### 视觉风格建议

Business Portal 应该有明显区别于 Technical Dashboard 的视觉语言。

推荐方向：

```text
深色科技底色 + 柔和渐变光效
半透明玻璃拟态卡片
流动光线表现智能体协作
卡片式业务模块而不是密集表格
大字号关键指标和状态摘要
少量高品质 3D / 粒子 / 光环元素
明确的成功、风险、待审批状态色
```

但要避免：

```text
动效过度导致页面晕眩
为了炫酷牺牲信息层级
所有东西都发光，反而没有重点
复杂图表堆满首页
把业务入口做成开发者监控大屏
```

视觉关键词：

```text
高级
轻盈
流动
可控
可信
有未来感
但不压迫
```

#### CSS 动效应该服务理解，而不是只做装饰

动效要表达业务状态，帮助用户理解系统在做什么。

推荐动效：

| 场景 | 动效表达 | 用户感知 |
|---|---|---|
| 创建团队 | 智能体岗位卡片依次生成、连线点亮 | 系统正在帮我搭建团队 |
| 任务运行 | 流程节点逐步推进，当前节点轻微脉冲 | 我知道任务进行到哪一步 |
| 智能体协作 | 多个 Agent 卡片之间有流动连线 | 团队在协作，不是单个黑盒在回答 |
| 风险拦截 | 风险卡片浮起并高亮审批按钮 | 系统知道哪里不能自动做 |
| 生成优化建议 | 问题聚类逐步收束成建议卡片 | 系统是在分析后给建议 |
| 灰度发布 | 流量比例环形进度从 5% 到 100% | 新版本正在可控扩大 |
| 自动回滚 | 状态从 v2 平滑退回 v1，并显示原因 | 系统有兜底，不会失控 |

可采用的 CSS / 前端动效手段：

```text
Framer Motion 页面与卡片转场
CSS keyframes 做轻量流光、呼吸、脉冲
SVG path animation 表现流程连线
渐变边框表现激活态和智能推荐
Skeleton + shimmer 表现 AI 生成中
微交互 hover / pressed / success feedback
数字滚动动画表现指标变化
```

动效原则：

```text
每个动效必须回答一个问题：它帮助用户理解了什么？
默认动效要克制，关键路径才增强
长时间循环动效要轻，避免干扰阅读
所有动效应支持 reduced-motion 降级
移动端优先保证性能和可读性
```

#### 易操作性：业务用户应该像填业务表单，不像配系统参数

Business Portal 的交互要尽量向导化、模板化、自然语言化。

推荐模式：

```text
一句话创建：输入“我要处理售后退款咨询”，系统生成团队草案
模板优先：客服、销售、法务、运营、内容审核等场景模板
逐步向导：目标 → 团队 → 规则 → 知识 → 审批 → 测试 → 发布
可视化编排：用流程卡片和连线表达分工，不要求用户理解 workflow DSL
业务问题表单：用“哪些情况必须人工确认？”替代 approval_policy 配置
自然语言预览：配置完成后用人话总结“这个团队会怎么工作”
一键试运行：上传/选择历史案例，立即看到处理过程
一键回滚：发布后随时回到上一个稳定版本
```

关键交互要求：

```text
默认给推荐值，不让用户从空白开始
每一步只问业务必须回答的问题
高级配置默认折叠
所有技术字段都有业务化解释
保存前展示“影响范围”和“风险提示”
发布前强制试运行或确认跳过原因
错误提示给解决建议，不只显示错误码
```

#### 首页应该像“业务智能驾驶舱”，不是系统监控页

Business Portal 首页建议使用卡片和叙事结构：

```text
顶部：一句话状态摘要
今天你的客服智能体团队已处理 128 个工单，自动完成率 75%，有 6 个事项需要你审批。

第一屏：关键业务指标
处理量 / 自动完成率 / 人工介入率 / 风险拦截 / 平均时长 / 成本估算

第二屏：正在运行的场景
售后工单处理、退款咨询、物流异常、投诉安抚

第三屏：待处理事项
待审批回复、待确认优化建议、待补充规则

第四屏：系统洞察
最近哪些问题变多了、哪里人工纠正率高、系统建议怎么改

第五屏：团队健康度
每个智能体岗位的表现、错误率、工具失败率、是否需要优化
```

#### 移动端友好：业务平台要能随时随地用

业务负责人、运营、客服主管并不总坐在电脑前。很多关键动作发生在手机上：

```text
路上看今日处理情况
会议前快速查看异常趋势
客户投诉升级时立即审批
晚上收到系统风险提醒后快速处理
临时确认一个优化建议是否可以灰度
```

因此 Business Portal 的移动端不是“缩小版后台”，而应该是一等入口。

移动端优先支持的任务：

| 移动场景 | 用户动作 | 设计要求 |
|---|---|---|
| 看状态 | 查看今日处理量、自动完成率、待审批数 | 首页首屏给一句话摘要 + 关键指标卡 |
| 批审批 | 同意/拒绝客户回复、退款建议、版本灰度 | 大按钮、明确风险、支持滑动确认 |
| 看原因 | 查看某单为什么这样处理 | Trace 转成短故事，分段折叠 |
| 处理异常 | 系统提示某类问题异常升高 | 推送卡片 + 一键查看证据 |
| 触发试运行 | 选择一条历史案例测试团队 | 模板化操作，少输入 |
| 看优化建议 | 查看 v1/v2 差异和建议是否上线 | 对比卡片 + 风险摘要 + 灰度按钮 |

移动端信息架构建议：

```text
底部 Tab：
首页 / 场景 / 审批 / 洞察 / 我的

首页：
一句话摘要、关键指标、待办卡片、异常提醒

审批：
按风险级别排序，卡片化展示，同意/拒绝/转交

洞察：
系统发现的问题、证据、建议动作

场景：
查看场景状态和团队健康度，不承载复杂配置

我的：
通知、权限、常用空间、最近操作
```

移动端交互原则：

```text
单手可操作：关键按钮放在拇指热区
卡片优先：少用宽表格，多用纵向信息卡
少输入：优先选择、确认、滑动，不要求长文本输入
短文案：每张卡只讲一个决策点
渐进展开：先给结论，再展开证据和 trace 故事
离线/弱网友好：审批卡和关键摘要可缓存
推送友好：重要审批、异常、灰度结果可通过消息触达
安全确认：高风险动作使用二次确认或滑动确认
```

移动端视觉和动效要更克制：

```text
减少大面积粒子和复杂 3D
保留轻量渐变、卡片转场、状态脉冲
动画时长更短，避免拖慢操作
优先 60fps 和低耗电
尊重系统 reduced-motion 设置
```

建议支持 PWA 能力：

```text
添加到主屏幕
移动端全屏体验
离线缓存关键页面骨架
推送通知或深链打开审批卡
保留登录态
适配 iOS Safari / Android Chrome
```

移动端不是只为了“能看”，而是为了让业务闭环不断线：审批、异常处理、效果查看、灰度确认都可以随时完成。

#### 业务解释层：把 Trace 翻译成故事

业务用户不应该直接看原始 Trace，而应该看到业务过程故事。

技术 Trace：

```text
agent=policy-agent
skill=order_query
confidence=0.82
tool_call=xxx
approval_policy=false
```

业务解释：

```text
售后政策智能体查询了订单状态，确认该订单已签收 3 天，且商品不是特殊类目。
因此系统判断它初步符合 7 天无理由退款条件。
本次退款金额 89 元，低于人工审批阈值，所以无需主管确认。
```

这层解释是业务入口的核心体验之一：让 AI 的过程从黑盒变成可理解、可纠正、可信任。

#### 组件设计建议

```text
ScenarioCard：业务场景卡片，显示目标、状态、处理量、风险
AgentRoleCard：智能体岗位卡，显示职责、工具、健康度
FlowTimeline：任务流程时间线，显示每一步状态和依据
InsightCard：系统洞察卡，显示问题、证据、建议动作
ApprovalCard：审批卡，显示风险、影响范围、同意/拒绝
VersionCompare：版本对比卡，显示 v1/v2 差异和评估结果
RunStory：业务化运行故事，把 trace 翻译成人话
TeamCanvas：团队编排画布，用卡片和连线表达协作关系
MobileApprovalSheet：移动端审批底部抽屉
MobileInsightFeed：移动端洞察信息流
StatusDigestCard：移动端一句话状态摘要卡
```

#### 技术实现提示

Business Portal 可以复用当前 React/Vite 技术栈，但应在设计系统上与技术 Dashboard 分离：

```text
独立主题 token：颜色、圆角、阴影、动效、字体层级
独立路由壳：BusinessPortalShell
独立导航：业务术语和业务流程
共享底层组件：Button、Card、Tabs、Toast、DataTable 等
新增业务组件：ScenarioCard、AgentRoleCard、FlowTimeline、ApprovalCard
动效库建议：Framer Motion + CSS keyframes + SVG animation
可访问性：reduced-motion、键盘操作、颜色对比度、移动端适配
移动端：响应式布局、PWA、底部导航、触控热区、深链审批卡
```

这部分不是“美化工作”，而是产品成败关键：业务用户对智能体团队的第一印象，往往来自界面是否高级、过程是否清楚、操作是否有把握。

### 3.4.5 产品形态建议

短期：

```text
继续复用 DashboardServer 和 API
新增 /business 或 /workspaces 前端路由
业务页面使用独立导航和术语
隐藏技术菜单，只展示业务成熟能力
移动端优先支持首页、审批、洞察、运行记录
```

中期：

```text
前端代码拆分为两个壳：Dashboard Shell 与 Business Portal Shell
共享组件库、API client、认证能力
业务入口支持模板市场、团队向导、审批中心、效果看板
支持 PWA、移动通知、深链打开审批卡
```

长期：

```text
Business Portal 可独立部署或嵌入业务系统
Technical Dashboard 只给平台团队使用
两者通过统一 API 和权限系统连接
移动端 Business Portal 成为审批、异常处理、业务复盘的一等入口
```

这条隔离线很重要：Hermes 想进入业务，就不能让业务人员感觉自己在操作机房控制台。

### 3.5 领先智感的产品抓手

平台要凸显领先感，建议重点打磨这些体验：

| 智感能力 | 业务感知 | 示例 |
|---|---|---|
| 意图补全 | 我说一个目标，系统帮我补全团队方案 | “售后处理”自动生成 5 个岗位和审批规则 |
| 业务追问 | 系统知道哪些关键信息没填 | “退款超过多少金额需要人工确认？” |
| 过程解释 | 每个结论都有依据 | “因为订单签收 3 天且非特殊类目，所以建议走 7 天无理由” |
| 风险预判 | 高风险动作自动拦截 | “该操作会向客户发送消息，需要审批” |
| 效果复盘 | 主动发现失败模式 | “生鲜退款场景人工纠正率偏高” |
| 一键生成改进版 | 从问题到新版本草案 | “是否生成 v2：新增特殊类目判断？” |
| 灰度和回滚 | 敢上线、能兜底 | “先给 5% 工单试运行，异常自动回 v1” |
| 时尚动效界面 | 感觉系统成熟、先进、可理解 | “智能体协作过程像流程故事一样动态展开” |


---


### 3.6 Business Portal 信息架构：从“功能列表”变成“业务工作台”

Business Portal 不能只是把 API 包一层页面，也不能把技术 Dashboard 换一套皮肤。它应该有一套面向业务用户的稳定信息架构，让用户一进来就知道：

```text
我现在有哪些智能体团队
它们正在处理什么
哪里需要我审批
效果好不好
系统建议我怎么优化
```

推荐第一版 Business Portal 采用五个一级入口：

| 一级入口 | 业务问题 | 页面重点 | 移动端优先级 |
|---|---|---|---|
| 首页 | 今天整体是否正常？ | 关键指标、待办、异常、推荐动作 | 最高 |
| 团队 | 我有哪些数字员工团队？ | 团队列表、岗位分工、版本、权限边界 | 高 |
| 运行 | 每个任务处理得怎么样？ | 运行记录、业务故事化 Trace、失败原因 | 高 |
| 审批 | 哪些事情需要我确认？ | 高风险动作、版本发布、权限申请 | 最高 |
| 洞察 | 哪里可以变得更好？ | 失败模式、效果趋势、进化提案 | 中 |

#### 首页：业务智能驾驶舱，而不是系统监控页

首页应该回答“我要不要管”这个问题，而不是展示一堆技术指标。

推荐模块：

```text
今日概览
- 已处理任务数
- 自动完成率
- 人工介入数
- 风险拦截数
- 平均处理时长

需要我处理
- 待审批动作
- 待发布版本
- 异常运行记录
- 低置信度结果

系统建议
- 建议新增规则
- 建议更新知识
- 建议生成 v2 团队草案

团队状态
- 正常运行
- 试运行中
- 需要关注
- 已暂停
```

页面表达应尽量业务化：

```text
不要写：error_rate = 12.3%
应该写：最近 24 小时有 12 条任务需要人工纠正，主要集中在“生鲜退款”场景

不要写：tool_call_failed
应该写：订单系统查询失败，已自动转人工处理，未影响客户回复
```

#### 团队页：像管理一个业务小组，而不是配置一组 Agent

团队页的核心是让业务负责人理解：这个团队由谁组成、能做什么、不能做什么、当前上线的是哪个版本。

推荐页面结构：

```text
团队列表
  ↓
团队详情
  - 团队目标
  - 岗位分工
  - 可用知识
  - 可用系统
  - 审批边界
  - 当前版本
  - 试运行结果
  ↓
版本历史
  - v1 / v2 / v3
  - 每个版本改了什么
  - 为什么改
  - 测试结果
  - 灰度状态
```

团队详情不应该只显示 JSON 配置，而应提供“岗位卡片”：

```text
售后政策专家
职责：判断用户诉求是否符合售后政策
可查：售后政策库、商品类目规则
不可做：直接退款、直接联系客户
遇到这些情况必须请人确认：生鲜、定制品、金额超过 1000 元
```

#### 运行页：把 Trace 变成业务故事

运行页不是日志搜索页，而是业务复盘页。每条运行记录至少应该有三层视图：

```text
摘要层：一句话说明结果
过程层：按业务步骤解释判断依据
技术层：仅技术/管理员可展开查看原始 Trace、模型、工具调用、token、错误栈
```

推荐业务故事格式：

```text
任务：用户申请退款
结果：建议同意用户发起退货申请
原因：订单签收 3 天，商品非特殊类目，金额 89 元，符合 7 天无理由初步条件
系统动作：生成客服回复草稿
风险判断：无需人工审批
后续建议：若用户上传破损图片，则转入质量问题流程
```

这种表达能让非技术用户敢复盘、敢纠错，也能让平台收集高质量反馈。

#### 审批页：移动端最高优先级

审批是业务入口移动化的核心场景。审批卡片必须做到“30 秒内看懂、10 秒内操作”。

每张审批卡建议包含：

```text
这是什么事
为什么需要审批
如果同意会发生什么
如果拒绝会发生什么
系统推荐怎么做
风险等级
相关依据
操作按钮：同意 / 拒绝 / 要求补充信息 / 转交他人
```

审批动作必须避免误触：

```text
低风险：点击后二次确认
中风险：要求选择审批理由
高风险：要求输入确认短语或走多人审批
```

移动端审批卡可以通过深链打开：

```text
Hermes 通知 → 点击 → 打开具体审批卡 → 查看摘要 → 展开依据 → 操作 → 返回原业务系统
```

#### 洞察页：把“报表”变成“下一步建议”

洞察页不应该只展示图表，而应该把数据转成行动建议。

推荐表达：

```text
发现：最近 7 天人工纠正率从 6% 上升到 14%
集中场景：生鲜退款、定制商品退款
可能原因：当前团队没有特殊类目判断步骤
建议动作：生成 v2 团队草案，新增“特殊类目政策判断”岗位
预期收益：减少约 40% 人工纠正
上线方式：先用历史 100 条工单回放测试，再给 5% 新工单灰度
```

也就是说，洞察页的默认输出不是“看板”，而是：

```text
发现问题 → 解释原因 → 给出改进方案 → 生成提案 → 评估验证 → 灰度发布
```

#### 新手首次使用路径

Business Portal 第一版必须重视首次使用。业务用户第一次进入时，不应面对空白系统，而应进入一个向导：

```text
选择业务场景
  ↓
填写业务目标
  ↓
上传/选择知识来源
  ↓
确认团队岗位草案
  ↓
设置哪些动作必须审批
  ↓
用样例任务试运行
  ↓
查看系统解释
  ↓
发布试运行版本
```

空状态也要可操作：

```text
没有团队 → 创建第一个智能体团队
没有运行记录 → 用样例任务试运行
没有知识库 → 上传政策文档或连接现有知识源
没有审批 → 当前没有风险事项，可查看团队效果
没有洞察 → 至少运行 20 条任务后生成趋势分析
```

#### 产品验收标准

Business Portal 不能只按“页面是否做完”验收，而要按业务用户是否能完成闭环验收：

```text
[ ] 非技术用户能在 10 分钟内创建一个试运行团队
[ ] 非技术用户能看懂一次运行为什么得出这个结论
[ ] 非技术用户能在手机上完成一次审批
[ ] 非技术用户能知道新版本改了什么、为什么改、是否安全
[ ] 技术日志默认不暴露给业务用户，但管理员可追溯
[ ] 所有页面都提供下一步建议，而不是只展示静态数据
```


### 3.7 吸收 OpenClaw 的成功经验：从“会调用模型”到“有组织地完成任务”

OpenClaw 的成功不只来自模型能力，而来自一套把模型能力“组织起来”的产品机制：预设提示词、工作区上下文、技能触发、任务流、主动记忆、常驻任务指令、执行纪律和安全边界。

Hermes 要成为业务智能体团队平台，应该重点吸收这些机制，并把它们产品化给业务人员使用。

#### 3.7.1 OpenClaw 值得吸收的核心机制

| OpenClaw 机制 | 成功原因 | Hermes 应如何吸收 |
|---|---|---|
| 系统提示词分层 | 每次运行都有稳定的行为准则、工具规则、上下文和安全边界 | 建立业务团队级 Prompt Stack，而不是把所有说明塞进一个大 prompt |
| Workspace 文件 | `AGENTS.md / SOUL.md / TOOLS.md / MEMORY.md` 等文件让智能体有稳定人格、规则和记忆 | 给每个业务空间生成“业务团队工作区文件” |
| Skills 按需加载 | 不把所有工具说明塞满上下文，只在相关任务触发技能说明 | 建立业务技能市场和场景化 skill routing |
| Standing Orders | 把长期授权、触发条件、审批边界写成常驻规则 | 支持业务方配置“长期任务指令”，例如每日巡检、每周复盘 |
| Task Flow | 多步骤任务有持久状态、可恢复、可追踪 | 将业务流程变成可暂停、可审批、可恢复的任务流 |
| Active Memory | 回复前主动召回相关历史，让回答更自然、更懂上下文 | 在业务运行前主动召回历史案例、规则变更、用户偏好 |
| Agent Loop | intake → context → model → tool → persistence 的完整闭环 | Hermes Run 必须记录完整执行链路，而不是只保存结果 |
| Execute-Verify-Report | 防止“答应了但没做”“做了但没验证” | 所有业务任务默认遵循执行-验证-汇报 |
| Specialist Lanes | 不同 agent/lane 承担不同工作，避免混乱和阻塞 | 智能体团队内定义岗位边界、转交规则和后台任务策略 |
| Hook / Guardrail | 工具调用、消息发送、安装技能等关键点可拦截 | 对外发、写数据库、真实账号操作等建立统一风控钩子 |

#### 3.7.2 Hermes 的 Prompt Stack：提示词不再是一段文本，而是一组资产

现在很多 Agent 系统的问题是：提示词散落在代码、配置和临时对话里，业务人员无法理解，也无法版本化管理。

Hermes 应该把提示词升级为可管理资产：

```text
业务空间 Prompt
  ↓
场景 Prompt
  ↓
团队 Prompt
  ↓
智能体岗位 Prompt
  ↓
技能使用 Prompt
  ↓
审批/安全 Prompt
  ↓
运行时动态上下文
```

业务方看到的是：

```text
团队目标
岗位职责
工作步骤
回答口径
禁区规则
审批条件
成功标准
```

工程侧落地为：

```text
WorkspacePromptProfile
ScenarioPlaybook
TeamOperatingManual
AgentRolePrompt
SkillUsageGuide
ApprovalGuardPrompt
RuntimeContextPack
```

这样做的价值：

```text
可读：业务人员能看懂每个智能体为什么这么做
可改：业务方能调整职责、口径和审批边界
可测：每次提示词变化都能跑历史案例评估
可审：谁在什么时候改了什么提示词有记录
可回滚：提示词和团队版本绑定，出问题能回退
```

#### 3.7.3 业务团队工作区：把 OpenClaw Workspace 文件产品化

OpenClaw 的工作区文件很关键，因为它让智能体有稳定“家底”。Hermes 可以把这个机制业务化。

建议每个 Workspace 自动生成一组业务工作区资产：

```text
BUSINESS.md        业务目标、范围、关键指标
TEAM.md            智能体团队岗位、职责、协作方式
PLAYBOOK.md        业务 SOP、处理步骤、特殊情况
VOICE.md           对客户/内部人员的沟通口径
TOOLS.md           可用业务系统、工具、连接器说明
RISK.md            禁止事项、审批规则、升级条件
MEMORY.md          重要业务背景、长期规则、历史决策
EVAL.md            评估案例、成功标准、失败样例
STANDING_ORDERS.md 长期任务指令和周期性复盘规则
```

业务人员不需要看到文件名，可以在页面里看到对应模块：

```text
业务目标
团队岗位
工作手册
沟通口径
可用系统
风险边界
历史记忆
测试案例
长期任务
```

工程侧可以继续用文件持久化，和当前 Phase 1 的 file-backed blueprint 保持一致。

#### 3.7.4 常驻任务指令：让智能体团队不只是被动响应

OpenClaw 的 Standing Orders 让 Agent 拥有明确的长期授权。Hermes 也应该支持业务级常驻任务。

示例：客服空间的常驻任务指令：

```text
每天 09:00 汇总昨日工单处理情况
每周一 10:00 复盘人工纠正最多的 5 类问题
当人工纠正率连续 3 天超过 8% 时，生成优化建议
当某类问题没有命中任何政策时，提醒业务负责人补充规则
所有对客户外发内容，默认先进入审批队列，除非命中低风险白名单
```

一个常驻任务应该包含：

```text
任务范围：它负责什么
触发条件：按时间、事件、阈值还是人工触发
执行步骤：先做什么、后做什么
审批边界：哪些动作必须等人确认
升级规则：什么时候停止并找人
汇报方式：完成后向谁汇报、汇报什么证据
```

这会明显提升“智感”：系统不是等人每次下命令，而是在边界内主动经营业务闭环。

#### 3.7.5 任务流编排：从一次回复升级为可恢复的业务流程

OpenClaw 的 Task Flow 强调多步骤任务的持久状态。Hermes 也不能只追求一次模型回复，而要把业务任务设计为可追踪流程。

例如“生成售后政策优化建议”不是一句 prompt，而是一条任务流：

```text
1. 收集最近 7 天运行记录
2. 找出人工纠正样本
3. 聚类错误原因
4. 对照当前政策和智能体提示词
5. 生成优化建议草案
6. 用历史案例回放测试
7. 生成新旧版本对比
8. 提交业务方审批
9. 灰度发布
10. 监控指标并决定扩大或回滚
```

每一步都应该有状态：

```text
PENDING
RUNNING
WAITING_APPROVAL
SUCCEEDED
FAILED
CANCELLED
RETRYING
```

这样业务方能看到：不是“AI 神秘地想了一下”，而是“系统按流程完成了哪些步骤、卡在哪里、需要谁决策”。

#### 3.7.6 主动记忆：让系统显得真正懂业务

很多“智感”来自系统能记住上下文，而不是每次像第一次见面。

Hermes 的主动记忆应在三个时机运行：

```text
创建团队前：召回类似业务场景、历史模板、已有规则
执行任务前：召回相关客户、订单、政策、历史处理案例
复盘优化前：召回历史失败模式、旧版本变更、业务负责人偏好
```

主动记忆不应该把所有历史都塞进上下文，而应该形成短摘要：

```text
相关政策：生鲜类商品不支持 7 天无理由
历史决策：退款超过 1000 元需主管审批
近期异常：过去 7 天生鲜退款纠正率上升
负责人偏好：回复客户时必须先安抚，再说明流程
```

这能直接提升业务方感知：系统不是“临时生成”，而是在“带着业务经验工作”。

#### 3.7.7 执行-验证-汇报：让智能体团队更可信

OpenClaw 的一个关键经验是：Agent 不能只说“我会去做”，必须真的执行、验证、汇报。

Hermes 应将所有业务任务默认套用：

```text
Execute：完成实际动作
Verify：验证动作结果和业务规则
Report：汇报结果、依据、风险和下一步
```

示例：售后回复生成。

```text
Execute：生成回复建议
Verify：检查是否引用正确政策、是否触发审批、是否包含违规承诺
Report：展示回复文本、政策依据、风险等级、是否可自动发送
```

示例：进化提案生成。

```text
Execute：生成 v2 团队草案
Verify：用历史案例测试并对比 v1
Report：说明提升点、风险点、建议灰度比例、回滚条件
```

这会让业务方敢用：每个结果都有证据链，而不是黑盒输出。

#### 3.7.8 技能路由和工具纪律：不是工具越多越好，而是按场景最小授权

OpenClaw 的 Skills 机制强调按需加载和可见能力控制。Hermes 应继续这个方向：

```text
不同业务空间能启用不同技能
不同智能体岗位只能看到自己需要的技能
高风险技能默认需要审批
技能说明按场景注入，避免上下文污染
技能使用过程进入 trace 和 audit
```

业务方看到的是：

```text
这个团队能查订单
这个团队能看知识库
这个团队不能直接发短信
这个团队不能写数据库
退款超过 1000 元必须审批
```

工程侧则是：

```text
WorkspaceSkillPolicy
AgentSkillAllowlist
ToolRiskLevel
BeforeToolCallGuard
SkillUsageTrace
```

#### 3.7.9 智感提升闭环：提示词、流程、技能、记忆一起进化

Hermes 的自进化不应该只改某个 Agent instruction，而要能围绕四类资产生成优化建议：

```text
Prompt：岗位说明、沟通口径、追问策略、错误处理说明
Flow：流程步骤、分支条件、审批节点、重试策略
Skill：工具选择、技能说明、权限边界、诊断方式
Memory：长期规则、历史决策、常见错误、优秀案例
```

完整闭环：

```text
业务运行
  ↓
记录 trace / tool / approval / feedback
  ↓
主动记忆沉淀重要经验
  ↓
评估发现失败模式
  ↓
生成 Evolution Proposal
  ↓
提案明确改的是 Prompt / Flow / Skill / Memory 哪类资产
  ↓
历史案例回放测试
  ↓
业务方审批
  ↓
生成新版本
  ↓
灰度发布和自动回滚
```

这才是“自进化链路和智感提升的闭环”：不是让 AI 随便改自己，而是让业务资产在数据、评估和审批约束下持续变好。

---

## 4. 当前已有技术基础（工程读者）

这一节说明为什么 Hermes Agent Java 适合承载上面的业务体验。业务读者可以略读，技术读者需要重点看。

### 4.1 多租户基础

现有模块：

```text
TenantManager
TenantContext
TenantFileSandbox
TenantProcessSandbox
TenantNetworkSandbox
TenantQuotaManager
TenantAuditLogger
TenantSessionManager
TenantMemoryManager
TenantSkillManager
```

未来映射：

```text
Workspace = Tenant 的业务化包装
```

每个业务方/业务线/业务场景可以拥有独立 Workspace，对应底层 Tenant。

---

### 4.2 Agent 组织基础

现有模块：

```text
TeamManager
AgentRole
IntentOrchestrator
CapabilityScorer
OrgManage
OrgControlCenter
AgentTrace
Audit
```

未来演进：

```text
Agent Team Blueprint
Scenario Runtime
业务流程编排
智能体版本管理
```

---

### 4.3 Skill 与外部能力基础

近期已完成：

```text
OpenClaw skill discovery
skill_get
skill_invoke
kimi-webbridge skill-backed provider
BrowserBridge -> Kimi WebBridge daemon execution
Org Control Center WebBridge diagnostics
```

未来演进：

```text
业务方可选择启用哪些 skills
workspace 级 skill policy
tool / skill 权限治理
connector 化业务数据源
```

---

### 4.4 安全执行基础

现有：

```text
DelegatedTask
LocalPatchExecutor
PatchSandboxPlan
ParentVerificationPolicy
Org Control Center delegated task execute UI
```

未来可用于：

```text
系统级自进化提案
代码/配置变更草案
沙箱验证
人工审批
禁止自动 merge
```

---

### 4.5 两个核心闭环：会做事，也会变好

平台要同时支持业务执行闭环和智能进化闭环。

---

#### 4.5.1 业务执行闭环

业务语言版本：

```text
收到任务
  ↓
判断任务类型
  ↓
分配给合适的智能体岗位
  ↓
查询资料或业务系统
  ↓
形成处理建议或结果
  ↓
需要时请求人工审批
  ↓
交付结果并留下记录
```

工程实现版本：

```text
业务输入
  ↓
Scenario / Workflow
  ↓
IntentOrchestrator 拆解
  ↓
Agent Team 自动协作
  ↓
Tool / Skill / Connector 调用
  ↓
结果生成
  ↓
审批 / 交付 / 回写业务系统
```

示例：售后工单处理。

```text
用户投诉
  ↓
分类智能体判断为退款问题
  ↓
订单智能体查询订单状态
  ↓
政策智能体匹配售后规则
  ↓
文案智能体生成回复
  ↓
高风险金额触发人工审批
  ↓
最终回复客户
```

---

#### 4.5.2 智能进化闭环

业务语言版本：

```text
系统记录每次处理过程
  ↓
对比结果是否被人工纠正
  ↓
找出常见错误和薄弱环节
  ↓
提出一份可读的优化建议
  ↓
用历史案例测试新方案
  ↓
业务方审批
  ↓
小范围试运行
  ↓
稳定后扩大使用
```

工程实现版本：

```text
运行 Trace
  ↓
结果评价
  ↓
错误归因
  ↓
发现瓶颈
  ↓
生成 Evolution Proposal
  ↓
离线评估 / 回放测试
  ↓
人工或策略审批
  ↓
发布新版本
```

示例：退款政策智能体漏判。

```text
系统发现：
- 最近 50 条售后工单中 12 条被人工纠正
- 错误集中在“7天无理由退款”场景

系统生成优化建议：
- 建议修改“售后政策智能体”的判断说明
- 补充“特殊类目商品”的判断步骤
- 增加高风险金额审批规则
- 生成 v4 草案
- 进入历史案例测试
```

---

## 5. 核心业务对象设计

这一节开始进入系统对象设计。为了降低理解门槛，每个对象先用业务语言解释，再给工程字段。

业务对象之间的关系可以理解为：

```text
一个业务空间
  └── 可以有多个业务场景
        └── 每个场景绑定一个智能体团队
              └── 团队有多个版本
                    └── 每次运行都记录使用了哪个版本
                          └── 运行结果进入评估和优化建议
```

换成业务说法：

```text
哪个部门在用 → 用来处理什么事 → 谁来处理 → 当前用的是哪套岗位配置 → 做得怎么样 → 要不要升级团队
```

---

### 5.1 Workspace：业务空间

业务理解：Workspace 就是某个部门、项目或业务线自己的 AI 工作区。

它解决的问题：

```text
客服团队的数据不要和销售团队混在一起
不同业务线可以有不同智能体团队
不同空间有独立权限、审计、成本和配置
```

工程实现：业务方看到的是 Workspace，底层映射到 Tenant。

示例：

```json
{
  "workspace_id": "customer-support",
  "tenant_id": "customer-support",
  "name": "客服业务空间",
  "description": "售前售后智能体团队",
  "owner": "business-admin",
  "status": "ACTIVE",
  "created_at": "2026-06-12T00:00:00Z",
  "updated_at": "2026-06-12T00:00:00Z"
}
```

Workspace 负责隔离：

```text
数据
智能体
技能
工具
知识库
运行记录
权限
审计
成本
配额
```

第一版 API：

```http
GET    /api/v1/workspaces
POST   /api/v1/workspaces
GET    /api/v1/workspaces/{workspaceId}
PUT    /api/v1/workspaces/{workspaceId}
DELETE /api/v1/workspaces/{workspaceId}
```

创建 Workspace 时应自动：

```text
创建 Tenant
初始化默认目录
初始化默认安全策略
初始化默认 Agent Team Blueprint
初始化审计与配额
```

---

### 5.2 Scenario：业务场景

业务理解：Scenario 是一个可以被反复执行的业务任务。

例如：

```text
售后工单处理
销售线索初筛
合同条款初审
公众号文章质检
采购申请预审
```

它解决的问题：同一个智能体团队可以服务多个任务入口，但每个任务都有自己的目标、成功标准和风险边界。

工程实现：Scenario 是业务目标与智能体团队之间的桥。

示例：

```json
{
  "scenario_id": "after_sales_ticket",
  "workspace_id": "customer-support",
  "name": "售后工单处理",
  "description": "自动分析售后工单并生成处理建议",
  "entry_team_id": "after-sales-team",
  "entry_agent_id": "ticket-router-agent",
  "success_criteria": [
    "正确识别工单类型",
    "生成可执行处理建议",
    "高风险退款必须人工审批"
  ],
  "status": "ACTIVE"
}
```

第一版 API：

```http
GET  /api/v1/workspaces/{workspaceId}/scenarios
POST /api/v1/workspaces/{workspaceId}/scenarios
GET  /api/v1/workspaces/{workspaceId}/scenarios/{scenarioId}
PUT  /api/v1/workspaces/{workspaceId}/scenarios/{scenarioId}
```

---

### 5.3 Agent Team Blueprint：智能体团队蓝图

业务理解：Team Blueprint 就是一份“团队编制表 + 工作说明书”。

它描述：

```text
这个团队有哪些岗位
每个岗位负责什么
每个岗位能使用哪些工具和知识
遇到问题交给谁
哪些动作必须审批
当前线上使用的是哪个版本
```

业务方配置的是团队蓝图，而不是运行态临时 Agent。

示例：

```json
{
  "team_id": "after-sales-team",
  "workspace_id": "customer-support",
  "name": "售后智能体团队",
  "active_version": "v1",
  "versions": [
    {
      "version": "v1",
      "status": "ACTIVE",
      "agents": [
        {
          "agent_id": "ticket-router-agent",
          "name": "工单分类智能体",
          "role": "分类与路由",
          "mission": "判断用户问题类型并选择后续处理路径",
          "instructions": "先判断工单类型，再选择合适处理智能体。",
          "allowed_tools": [],
          "allowed_skills": [],
          "handoff_policy": {
            "default_target": "policy-agent"
          }
        },
        {
          "agent_id": "policy-agent",
          "name": "售后政策智能体",
          "role": "政策判断",
          "mission": "根据售后政策判断可执行方案",
          "instructions": "判断退款、换货、补偿等售后策略。",
          "allowed_tools": ["order_query"],
          "allowed_skills": []
        }
      ],
      "created_at": "2026-06-12T00:00:00Z",
      "created_by": "business-admin"
    }
  ]
}
```

第一版 API：

```http
GET  /api/v1/workspaces/{workspaceId}/team-blueprints
POST /api/v1/workspaces/{workspaceId}/team-blueprints
GET  /api/v1/workspaces/{workspaceId}/team-blueprints/{teamId}
POST /api/v1/workspaces/{workspaceId}/team-blueprints/{teamId}/versions
POST /api/v1/workspaces/{workspaceId}/team-blueprints/{teamId}/activate
```

关键原则：

```text
Blueprint 必须版本化
运行记录必须绑定 blueprint version
自进化只能生成 candidate version，不能直接覆盖 active version
```

---

### 5.4 Agent Blueprint：单个智能体蓝图

业务理解：Agent Blueprint 是一个数字员工的岗位说明书。

它应该回答：

```text
你是谁？
你负责什么？
你不能做什么？
你能查哪些资料？
你能调用哪些系统？
你什么时候必须找人审批？
你的结果怎样才算合格？
```

建议字段：

```json
{
  "agent_id": "policy-agent",
  "name": "售后政策智能体",
  "role": "政策判断",
  "mission": "根据售后政策判断可执行方案",
  "instructions": "...",
  "success_criteria": [
    "明确说明判断依据",
    "高风险退款触发人工审批"
  ],
  "allowed_tools": ["order_query"],
  "allowed_skills": ["kimi-webbridge"],
  "knowledge_scope": ["after-sales-policy"],
  "approval_policy": {
    "requires_human_approval_when": [
      "refund_amount > 1000",
      "external_send == true"
    ]
  },
  "handoff_policy": {
    "on_low_confidence": "human-agent",
    "on_policy_missing": "policy-admin"
  }
}
```

---

### 5.5 Run：一次业务运行

业务理解：Run 就是一张“任务处理单”。

每次业务任务进来，无论成功、失败、转人工，都要留下记录：

```text
谁提交的任务
哪个团队处理的
用的是哪个版本
处理过程是什么
最终结果是什么
有没有触发审批
耗时和成本是多少
```

工程实现：每次业务调用都生成 Run。

```json
{
  "run_id": "run_xxx",
  "workspace_id": "customer-support",
  "scenario_id": "after_sales_ticket",
  "team_id": "after-sales-team",
  "team_version": "v1",
  "status": "COMPLETED",
  "input": {
    "ticket_id": "T123",
    "message": "我想退货"
  },
  "output": {
    "reply": "..."
  },
  "trace_id": "trace_xxx",
  "created_at": "...",
  "completed_at": "..."
}
```

第一版 API：

```http
POST /api/v1/workspaces/{workspaceId}/runs
GET  /api/v1/workspaces/{workspaceId}/runs
GET  /api/v1/workspaces/{workspaceId}/runs/{runId}
POST /api/v1/workspaces/{workspaceId}/runs/{runId}/cancel
GET  /api/v1/workspaces/{workspaceId}/runs/{runId}/events
```

---

### 5.6 Eval Set：评估集

业务理解：Eval Set 是一组“标准考题”。

它解决的问题：不能凭感觉说新版本更好，必须用历史案例和标准答案验证。

例如客服场景可以准备：

```text
典型退款案例
物流异常案例
高风险赔付案例
不应承诺赔偿的案例
必须转人工的案例
```

没有评估集，自进化不可控。

示例：

```json
{
  "eval_set_id": "after-sales-basic",
  "workspace_id": "customer-support",
  "scenario_id": "after_sales_ticket",
  "cases": [
    {
      "case_id": "case_001",
      "input": {
        "message": "我收到货三天了，想退货"
      },
      "expected": {
        "must_include": ["7天无理由", "商品状态", "退货流程"],
        "must_not_include": ["直接赔付"]
      }
    }
  ]
}
```

---

### 5.7 Evolution Proposal：进化提案

业务理解：Evolution Proposal 是系统写给业务负责人的“优化建议单”。

它不直接改线上团队，而是说明：

```text
发现了什么问题
证据是什么
建议怎么改
风险有多高
用哪些案例测过
是否建议灰度上线
```

自进化不能直接改生产对象，必须先生成 Proposal。

示例：

```json
{
  "proposal_id": "evo_xxx",
  "workspace_id": "customer-support",
  "scenario_id": "after_sales_ticket",
  "target_type": "AGENT_PROFILE",
  "target_id": "policy-agent",
  "from_version": "v3",
  "to_version": "v4_candidate",
  "reason": "退款政策判断准确率偏低",
  "evidence": [
    "最近50次运行中12次被人工纠正",
    "主要错误集中在7天无理由场景"
  ],
  "changes": [
    "补充退款判断步骤",
    "增加高风险金额审批规则"
  ],
  "risk_level": "MEDIUM",
  "status": "PENDING_EVAL",
  "created_by": "system",
  "created_at": "..."
}
```

状态机：

```text
DRAFT
PENDING_EVAL
EVAL_PASSED
EVAL_FAILED
PENDING_APPROVAL
APPROVED
REJECTED
ROLLED_OUT
ROLLED_BACK
```

目标类型：

```text
AGENT_PROFILE
TEAM_BLUEPRINT
SCENARIO_WORKFLOW
SKILL_POLICY
TOOL_POLICY
SYSTEM_PATCH
```

---

## 6. 自动编排设计

自动编排分三层演进。

---

### 6.1 第一层：规则编排

初期最稳。

示例：

```text
如果是退款问题 → refund-agent
如果是物流问题 → logistics-agent
如果涉及赔付 → human-approval
```

优点：

```text
稳定
可解释
容易上线
适合业务方理解
```

---

### 6.2 第二层：模型编排

使用 IntentOrchestrator 根据任务动态选择 Agent。

可增强现有模块：

```text
IntentOrchestrator
TeamManager
AgentRole
CapabilityScorer
```

能力：

```text
根据任务意图选择 Agent
根据能力评分分配子任务
根据失败原因 reroute
根据上下文压力 delegated task
```

---

### 6.3 第三层：经验驱动编排

系统基于历史 Run / Trace / Eval 结果优化编排策略。

示例：

```text
退款金额 < 100 元：policy-agent 自动处理
退款金额 > 1000 元：policy-agent + risk-agent + human approval
用户情绪强烈：empathy-agent 先生成安抚话术
```

需要数据：

```text
成功率
人工纠正率
工具失败率
平均耗时
平均成本
用户满意度
风险事件
```

---

## 7. 自进化分级

不要一开始允许系统进化所有东西。

---

### Level 1：进化提示词和操作手册

低风险，优先做。

```text
Agent instructions
Skill usage guide
Scenario playbook
Error handling checklist
```

---

### Level 2：进化团队编排

中风险。

```text
新增 Agent
删除 Agent
改变 Agent 调用顺序
增加人工审批节点
修改 handoff policy
```

---

### Level 3：进化工具权限

高风险，必须审批。

```text
允许调用浏览器
允许发邮件
允许修改数据库
允许发布内容
```

---

### Level 4：进化代码或系统配置

最高风险。

通过现有 delegated local_patch 执行链路实现：

```text
sandbox
tests
parent verification
human approval
no auto merge
```

---

## 8. 安全阀设计

### 8.1 版本化

所有关键对象都必须版本化：

```text
Agent Blueprint
Team Blueprint
Scenario Workflow
Skill Policy
Tool Policy
```

Run 必须记录版本：

```text
team_version
scenario_version
agent_versions
```

---

### 8.2 回放测试

上线前用历史 case 回放。

```text
旧版本表现
新版本表现
差异
风险
成本变化
失败率变化
```

---

### 8.3 灰度发布

支持：

```text
5% 流量
10% 流量
50% 流量
100% 流量
仅测试 workspace
仅内部用户
```

---

### 8.4 自动回滚

触发条件：

```text
失败率上升
人工纠正率上升
用户满意度下降
成本暴涨
工具错误增加
审批拒绝率升高
```

---

### 8.5 审批

必须审批的变更：

```text
工具权限扩大
外部发送能力
数据库写入能力
浏览器真实账号操作
财务/法律/医疗建议
生产流程修改
系统代码变更
```

---

## 9. 服务器端部署目标

### 9.1 第一阶段部署形态

```text
单机 / 单服务器
JAR + systemd
Docker / docker-compose
文件存储 + SQLite 可选
Business Portal + Technical Dashboard + API
```

必须配置：

```text
server.port
data.dir
model.provider
model.api_key
database.url
storage.path
auth.enabled
admin.password
```

---

### 9.2 后续部署形态

```text
PostgreSQL
对象存储
Redis / Queue
多实例 API
独立 worker
独立 browser bridge / skill workers
监控告警
```

---

## 10. API 分层建议

### 10.1 管理 API

```http
/api/v1/workspaces
/api/v1/workspaces/{workspaceId}/members
/api/v1/workspaces/{workspaceId}/api-keys
/api/v1/workspaces/{workspaceId}/settings
```

### 10.2 智能体团队 API

```http
/api/v1/workspaces/{workspaceId}/team-blueprints
/api/v1/workspaces/{workspaceId}/team-blueprints/{teamId}/versions
/api/v1/workspaces/{workspaceId}/team-blueprints/{teamId}/activate
```

### 10.3 场景 API

```http
/api/v1/workspaces/{workspaceId}/scenarios
/api/v1/workspaces/{workspaceId}/scenarios/{scenarioId}
```

### 10.4 运行 API

```http
/api/v1/workspaces/{workspaceId}/runs
/api/v1/workspaces/{workspaceId}/runs/{runId}
/api/v1/workspaces/{workspaceId}/runs/{runId}/events
/api/v1/workspaces/{workspaceId}/runs/{runId}/cancel
```

### 10.5 进化 API

```http
/api/v1/workspaces/{workspaceId}/evolution/proposals
/api/v1/workspaces/{workspaceId}/evolution/proposals/{proposalId}
/api/v1/workspaces/{workspaceId}/evolution/proposals/{proposalId}/eval
/api/v1/workspaces/{workspaceId}/evolution/proposals/{proposalId}/approve
/api/v1/workspaces/{workspaceId}/evolution/proposals/{proposalId}/reject
/api/v1/workspaces/{workspaceId}/evolution/proposals/{proposalId}/rollout
/api/v1/workspaces/{workspaceId}/evolution/proposals/{proposalId}/rollback
```

---

## 11. 推荐开发路线

> 收敛原则：先把“业务空间 + 团队蓝图版本化”做成可运行、可测试、可复用的核心底座，再向 Scenario、Run、Skill Policy、Evolution Proposal 扩展。

### Phase 0.5：MVP 合同与边界收敛

目标：把终局蓝图压缩成第一阶段可落地的工程合同，避免一开始引入过多对象和状态机。

核心决策：

```text
1. Workspace 是 Tenant 的业务化 façade，不重新实现租户系统
2. 第一版 workspaceId == tenantId，避免双 ID 映射和生命周期漂移
3. Team Blueprint 是业务方自助创建智能体团队的唯一载体
4. Blueprint Version 是后续 Scenario / Run / Evolution 的版本锚点
5. 第一版用文件持久化，先不上数据库强依赖
6. 第一版只做 API + 测试，不做复杂 UI，但明确 Business Portal 与 Technical Dashboard 的边界
7. 第一版不做 Scenario / Run / Evolution，只为它们预留字段和模型边界
8. 第一版同步建立 Prompt Asset 的数据位置，避免后续提示词散落在代码里
9. 第一版所有任务设计默认遵循 Execute-Verify-Report
```

暂不做清单：

```text
Scenario 执行入口
Run 执行记录
Eval Set
Evolution Proposal
灰度发布
自动回滚
复杂审批流
前端复杂配置器
独立 Business Portal 完整前端
SQLite / PostgreSQL repository
```

这些能力不是不重要，而是依赖 Team Blueprint Versioning。先做会导致模型返工。

---

### Phase 1：Workspace + Team Blueprint Versioning 最小骨架

目标：业务方能创建业务空间，并在业务空间内创建、查询、版本化和激活智能体团队蓝图。

#### 11.1 Workspace MVP

Workspace 第一版只做 Tenant façade。

建议包结构：

```text
com.nousresearch.hermes.workspace
├── WorkspaceRecord.java
├── WorkspaceService.java
└── WorkspaceRoutes.java
```

对象建议：

```json
{
  "workspaceId": "customer-support",
  "tenantId": "customer-support",
  "name": "客服业务空间",
  "description": "售前售后智能体团队",
  "owner": "business-admin",
  "status": "ACTIVE",
  "createdAt": "2026-06-15T00:00:00Z",
  "updatedAt": "2026-06-15T00:00:00Z"
}
```

实现约束：

```text
workspaceId 第一版直接等于 tenantId
POST /workspaces 内部调用 TenantManager.createTenant
GET /workspaces 内部从 TenantManager.getAllTenants 映射
不要让业务 API 暴露 Tenant 的配额、安全、沙箱细节
不要为 Workspace 另建生命周期状态机
```

最小 API：

```http
GET  /api/v1/workspaces
POST /api/v1/workspaces
GET  /api/v1/workspaces/{workspaceId}
```

POST 示例：

```json
{
  "workspaceId": "customer-support",
  "name": "客服业务空间",
  "description": "售前售后智能体团队",
  "owner": "business-admin"
}
```

响应示例：

```json
{
  "ok": true,
  "workspaceId": "customer-support",
  "tenantId": "customer-support",
  "status": "ACTIVE"
}
```

---

#### 11.2 Team Blueprint MVP

建议包结构：

```text
com.nousresearch.hermes.blueprint
├── AgentBlueprintRecord.java
├── TeamBlueprintRecord.java
├── TeamBlueprintVersion.java
├── TeamBlueprintRepository.java
├── FileTeamBlueprintRepository.java
├── TeamBlueprintService.java
└── TeamBlueprintRoutes.java
```

第一版持久化路径：

```text
~/.hermes/tenants/{workspaceId}/business/team-blueprints/{teamId}.json
```

为什么放在 tenant 目录下：

```text
符合现有租户隔离模型
天然跟随 tenant 生命周期
备份/迁移边界清晰
后续可由 repository 替换为 SQLite / PostgreSQL
```

Team Blueprint 示例：

```json
{
  "teamId": "after-sales-team",
  "workspaceId": "customer-support",
  "name": "售后智能体团队",
  "description": "处理售后工单",
  "activeVersion": "v1",
  "versions": [
    {
      "version": "v1",
      "status": "ACTIVE",
      "createdAt": "2026-06-15T00:00:00Z",
      "createdBy": "business-admin",
      "agents": [
        {
          "agentId": "ticket-router-agent",
          "name": "工单分类智能体",
          "role": "分类与路由",
          "mission": "判断用户问题类型并选择后续处理路径",
          "instructions": "先判断工单类型，再选择合适处理智能体。",
          "allowedTools": [],
          "allowedSkills": [],
          "knowledgeScope": [],
          "handoffPolicy": {
            "defaultTarget": "policy-agent"
          },
          "approvalPolicy": {}
        }
      ]
    }
  ]
}
```

版本状态第一版只保留：

```text
DRAFT
ACTIVE
ARCHIVED
```

不要一开始引入 `PENDING_EVAL / EVAL_PASSED / ROLLED_OUT / ROLLED_BACK`，这些属于 Evolution 阶段。

关键不变量：

```text
1. 同一个 teamId 在同一 workspace 内唯一
2. 同一个 team blueprint 至多一个 ACTIVE version
3. activeVersion 必须指向一个 status=ACTIVE 的版本
4. ACTIVE version 不允许原地修改
5. 新变更只能创建新的 DRAFT version
6. activate(version) 会将旧 ACTIVE 改为 ARCHIVED，将目标版本改为 ACTIVE
7. 每个 version 至少包含一个 agent
8. agentId 在同一个 version 内唯一
9. allowedTools / allowedSkills 第一版只记录，不执行强校验
10. 所有写操作必须确认 workspace 存在
```

最小 API：

```http
GET  /api/v1/workspaces/{workspaceId}/team-blueprints
POST /api/v1/workspaces/{workspaceId}/team-blueprints
GET  /api/v1/workspaces/{workspaceId}/team-blueprints/{teamId}
POST /api/v1/workspaces/{workspaceId}/team-blueprints/{teamId}/versions
POST /api/v1/workspaces/{workspaceId}/team-blueprints/{teamId}/activate
```

创建 Team Blueprint 时建议自动创建 v1：

```json
{
  "teamId": "after-sales-team",
  "name": "售后智能体团队",
  "description": "处理售后工单",
  "createdBy": "business-admin",
  "agents": [
    {
      "agentId": "ticket-router-agent",
      "name": "工单分类智能体",
      "role": "分类与路由",
      "mission": "判断用户问题类型并选择后续处理路径",
      "instructions": "先判断工单类型，再选择合适处理智能体。"
    }
  ]
}
```

激活版本请求：

```json
{
  "version": "v2",
  "activatedBy": "business-admin"
}
```

---

#### 11.3 API 接入与双入口边界

参考现有：

```java
TenantDashboardIntegration.registerRoutes(app, tenantManager);
```

新增一个业务平台路由注册入口：

```java
BusinessPlatformRoutes.registerRoutes(
    app,
    tenantManager,
    workspaceService,
    teamBlueprintService
);
```

建议先集中注册，后续再按模块拆分：

```text
BusinessPlatformRoutes
├── workspace routes
└── team blueprint routes
```

原因：

```text
减少 DashboardServer 膨胀
避免 workspace / blueprint 路由各自重复解析错误响应
便于统一 /api/v1/business-platform 或 /api/v1/workspaces 前缀策略
```

同时要明确：这里的 DashboardServer 接入只是后端 API 复用，不代表业务入口直接混入技术 Dashboard。

第一版前端即使暂不做完整 UI，也要保留产品边界：

```text
/api/v1/workspaces              业务 API
/api/tenants、/api/config 等     技术 Dashboard API
/business 或 /workspaces         未来业务入口路由
/admin、/dashboard               技术运维入口
```

业务入口只能使用业务化 API 和业务化文案；技术 Dashboard 可以继续暴露完整诊断能力。

---

#### 11.4 测试策略

参考：

```text
src/test/java/com/nousresearch/hermes/dashboard/DashboardTenantRoutesTest.java
```

新增建议：

```text
src/test/java/com/nousresearch/hermes/business/BusinessPlatformRoutesTest.java
src/test/java/com/nousresearch/hermes/blueprint/TeamBlueprintServiceTest.java
```

必须覆盖：

```text
[ ] POST /api/v1/workspaces 创建 workspace，同时创建底层 tenant
[ ] GET /api/v1/workspaces 能看到刚创建的 workspace
[ ] 重复创建 workspace 返回 409
[ ] POST /team-blueprints 创建 team blueprint，并自动生成 active v1
[ ] GET /team-blueprints/{teamId} 返回 activeVersion=v1
[ ] POST /versions 创建 v2，状态为 DRAFT，不影响 activeVersion
[ ] POST /activate 激活 v2 后，v1=ARCHIVED，v2=ACTIVE
[ ] 创建 blueprint 时 workspace 不存在返回 404
[ ] 创建空 agents 的 version 返回 400
[ ] 同一 version 内重复 agentId 返回 400
```

---

#### 11.5 与后续阶段的接口预留

Phase 1 不实现 Run，但 Team Blueprint Version 必须为 Run 预留稳定引用：

```text
workspaceId
teamId
version
agentId
```

后续 RunRecord 应至少记录：

```text
workspaceId
scenarioId
teamId
teamVersion
agentVersions 或 blueprintVersionRef
traceId
input
output
status
```

Phase 1 不实现 Evolution，但版本模型必须支持 candidate 语义。第一版可以用 DRAFT 表示候选版本，后续再扩展：

```text
DRAFT -> CANDIDATE -> PENDING_EVAL -> APPROVED -> ACTIVE
```

不要在 Phase 1 提前实现复杂状态机，只保证数据结构不阻断未来扩展。

---

### Phase 2：Scenario 与 Run API

目标：业务方能通过 API 调用某个业务场景。

任务：

```text
1. 新增 ScenarioRecord / ScenarioService
2. Scenario 绑定 Team Blueprint active version
3. 新增 RunRecord / RunService
4. Run 调用 IntentOrchestrator
5. Run 记录 trace_id、team_version、status、input、output
6. 提供 run events 查询接口
7. Run 默认输出 Execute-Verify-Report 结构
8. 为后续 Task Flow 预留 step 状态和 approval wait 状态
```

---

### Phase 3：Skill / Tool Policy 产品化

目标：业务方能配置每个 Agent 的技能与工具权限。

任务：

```text
1. Workspace 级 skill registry
2. Agent Blueprint allowed_skills / allowed_tools
3. Tool policy validation
4. 高风险工具审批
5. Skill-backed capability diagnostics
6. Agent 级 skill usage guide 注入
7. before_tool_call 风险守卫
8. Skill 调用进入 trace / audit / eval 数据
```

---

### Phase 4：Evolution Proposal 最小闭环

目标：系统可以产生、评估、审批、发布进化提案。

任务：

```text
1. EvolutionProposal 数据结构
2. Proposal 状态机
3. Proposal 绑定 target_type / target_id / version
4. 人工创建 proposal
5. 从 trace 生成 proposal 草案
6. Eval stub
7. Approve / Reject
8. 生成 candidate version
9. Activate / Rollback
10. Proposal 明确 target_asset_type：Prompt / Flow / Skill / Memory / Team Blueprint
11. 从失败 trace 自动生成 Prompt/Playbook 改进草案
12. 支持历史案例回放对比 v1/v2
```

---

### Phase 5：部署与生产治理

目标：服务器端可部署、可运维、可审计。

任务：

```text
1. Dockerfile
2. docker-compose
3. systemd service 示例
4. SQLite / PostgreSQL 持久化规划
5. API Key
6. Admin auth
7. Audit dashboard
8. Cost / quota dashboard
9. Backup / restore
```

---

## 12. 下一刀建议

建议下一刀直接做：

> Workspace façade + file-backed Team Blueprint Versioning。

原因：

```text
Workspace 是业务入口，但底层应复用 TenantManager
Team Blueprint 是业务自助创建智能体团队的载体
Versioning 是 Scenario / Run / Evolution 的共同前置条件
文件持久化最快能跑通闭环，也符合现有项目风格
```

最小交付：

```text
1. WorkspaceRecord
2. WorkspaceService
3. WorkspaceRoutes
4. 创建 Workspace 自动创建 Tenant
5. TeamBlueprintRecord
6. AgentBlueprintRecord
7. TeamBlueprintVersion
8. FileTeamBlueprintRepository
9. TeamBlueprintService
10. TeamBlueprintRoutes
11. PromptAssetRecord / TeamOperatingManual 初始占位
12. DashboardServer 注册 BusinessPlatformRoutes，仅作为 API 复用
13. 明确 Business Portal 与 Technical Dashboard 的路由/权限边界
14. API 测试：workspace 创建/查询
15. API 测试：blueprint 创建/v2/激活
16. 测试：团队蓝图关联 Prompt Asset 版本
17. Business Portal 信息架构最小壳：首页 / 团队 / 运行 / 审批 / 洞察
18. 移动端审批卡最小交互
19. 文档更新
```

第一版无需做复杂 UI，但 API 的资源命名、字段和权限必须服务 Business Portal 的五个业务入口，避免后续 UI 只能展示技术对象。

验收标准：

```text
[x] mvn test 能通过新增测试
[x] POST /api/v1/workspaces 能创建底层 tenant
[x] GET /api/v1/workspaces 能返回业务化 workspace 列表
[x] POST /api/v1/workspaces/{workspaceId}/team-blueprints 能创建 v1 ACTIVE
[x] POST /versions 能创建 v2 DRAFT
[x] POST /activate 能切换 activeVersion
[ ] ACTIVE version 不允许被原地修改（当前没有原地修改接口；下一步增加 update API 时补强）
[x] 所有错误响应包含明确 workspaceId / teamId / version
[x] Business Portal 五个入口都有可返回真实数据或空状态的 API 支撑
[x] 移动端审批卡能用同一套审批 API 完成同意 / 拒绝 / 要求补充信息
[x] Business Run API 能创建、查询并展示业务故事化运行记录
[x] Business Insights API 能基于 workspace/team/run/approval 生成最小真实洞察
[x] Business Home API 能聚合 runs / approvals / insights，返回业务驾驶舱数据
[x] Dashboard UI 已新增 /business 页面壳并接入 Business Portal 五入口 API
[x] Business Portal 后端真实 smoke 已通过，并记录联调结果
[x] /business 页面完成组件拆分和缺字段防御优化
[x] /business 页面新增 demo 数据填充引导，展示 smoke 脚本命令
[x] /business 页面 Run / Approval / Insight 卡片支持详情展开
[x] /business 页面新增 Create Workspace 表单 MVP
[x] /business 页面新增 Create Team Blueprint 表单 MVP
[x] /business 页面新增 Create Run Story 表单 MVP
[x] /business 页面新增 Create Approval Card 表单 MVP
[x] /business 页面 Approval 卡片支持 Approve / Reject / Request info 操作 MVP
[x] /business 页面创建区已折叠分组，减少表单堆叠感
[x] smoke 脚本支持 APPROVAL_ACTION=all 并真实验证 approve / reject / request-info
[x] 新增 BUSINESS_PORTAL_CURRENT_STATE.md 汇总当前实现状态、限制和路线图
[x] /business 表单长文本改 textarea，审批 approve/reject 支持可编辑 reason
[x] /business Workspace/Team ID 增加客户端校验和友好错误提示
[x] /business HIGH / CRITICAL Approval 操作增加确认短语防误触
[x] Scenario 对象后端第一版落地，新增 scenarios API 和文件持久化
[x] Scenario 已绑定 Team Blueprint / Business Run / Insights 第一版
[ ] /business 页面浏览器截图检查（当前环境缺少可用浏览器/扩展连接；用户暂时不连接浏览器）
```

---

## 13. 设计约束

### 13.1 不要让自进化直接修改生产

所有自进化都必须走：

```text
Proposal → Eval → Approval → Version → Rollout
```

### 13.2 不要让业务直接面对底层 Tenant

业务方看到：

```text
Workspace
Scenario
Agent Team
```

底层才是：

```text
Tenant
ToolRegistry
IntentOrchestrator
```

### 13.3 不要把外部能力硬编码死

例如 Kimi WebBridge：

```text
核心只做 adapter / diagnostics / policy
真实能力优先通过 skill-backed / connector-backed 接入
```

### 13.4 高风险能力必须有审批和审计

包括：

```text
浏览器真实账号操作
发送消息/邮件/发帖
数据库写入
支付/财务操作
代码变更
权限扩大
```

---

## 14. 与现有模块映射表

| 业务平台概念 | 现有模块 | 后续动作 |
|---|---|---|
| Business Portal | DashboardServer / React web | 增加业务入口壳，与技术 Dashboard 隔离 |
| Workspace | TenantContext / TenantManager | 增加业务包装层 |
| Agent Team Blueprint | TeamManager / AgentRole | 增加版本化蓝图 |
| Scenario | IntentOrchestrator / ScenarioService | 已新增业务场景对象与 API；下一步绑定 Team/Run/Insights |
| Run | AgentTrace / IntentRun / BusinessRunService | 增加业务运行记录与业务故事化 Trace |
| Skill Policy | SkillManager / TenantSkillManager | 增加 workspace/agent 级策略 |
| Tool Policy | ToolRegistry / TenantAwareToolDispatcher | 增加 allowed_tools 校验 |
| Evolution Proposal | org/evolution + delegated task / BusinessInsightService | 增加 proposal 状态机；当前先输出业务洞察和建议动作 |
| Eval Set | org/eval | 增加 scenario eval set |
| Approval | BrowserApprovalQueue / DelegatedTask verification / BusinessApprovalService | 统一审批中心，Business Portal 先接业务审批卡 |
| Prompt Asset | OpenClaw system prompt / workspace bootstrap | 增加提示词资产版本化 |
| Standing Orders | AGENTS.md / HEARTBEAT.md / cron | 增加业务常驻任务指令 |
| Task Flow | OpenClaw taskflow / delegated task | 增加可恢复多步骤流程 |
| Active Memory | OpenClaw active-memory / MEMORY.md | 增加业务主动记忆召回 |
| Execute-Verify-Report | OpenClaw standing-order execution discipline | 固化任务可信执行格式 |
| Audit | TenantAuditLogger | 扩展业务事件审计 |

---

## 15. 明日继续开发 Checklist

建议明天从下面开始：

```text
[x] 新建 docs/BUSINESS_PORTAL_ITERATION_BREAKDOWN.md 并拆解 API 迭代步骤
[x] 新增 package: com.nousresearch.hermes.workspace
[x] 新增 WorkspaceRecord
[x] 新增 WorkspaceService
[x] 新增 WorkspaceDashboardIntegration
[x] DashboardServer 注册 /api/v1/workspaces
[x] POST /api/v1/workspaces 自动调用 TenantManager.createTenant
[x] GET /api/v1/workspaces 返回 workspace 列表
[x] 新增 package: com.nousresearch.hermes.blueprint
[x] 新增 TeamBlueprintRecord / AgentBlueprintRecord
[x] 新增 TeamBlueprintService
[x] API: 创建 team blueprint / 新版本 / 激活版本
[x] 测试：创建 workspace 自动创建 tenant
[x] 测试：创建 blueprint v1 并激活
```

---

## 16. 最终目标画面

业务方打开平台后，不应该感觉自己在使用“开发者控制台”，而应该像在配置一个可以上线工作的数字团队。

因此最终产品应有两个清晰入口：

```text
Business Portal：给业务用，聚焦空间、场景、团队、规则、审批、效果、优化；视觉上要时尚、有动效、低门槛，并且移动端友好
Technical Dashboard：给平台团队用，聚焦模型、工具、日志、网关、Trace、系统配置；视觉上优先信息密度和诊断效率
```


### 16.1 第一次创建团队

```text
1. 创建“客服业务空间”
2. 输入一句话目标：“我想自动处理售后退款咨询”
3. 平台自动生成售后智能体团队草案：
   - 工单分类智能体
   - 订单查询智能体
   - 售后政策智能体
   - 回复文案智能体
   - 风险审批智能体
4. 平台追问关键规则：
   - 退款超过多少钱必须审批？
   - 哪些商品不能自动承诺退款？
   - 回复客户前是否需要人工确认？
5. 业务方上传或选择售后政策知识库
6. 用历史工单试运行
7. 查看每一步判断依据
8. 点击发布 v1
```

### 16.2 日常运行

业务首页应该呈现：

```text
今日处理工单：128
自动完成：96
转人工：21
需审批：11
平均处理时长：23 秒
人工纠正率：4.8%
主要风险：生鲜退款判断不稳定
系统建议：生成特殊类目政策判断 v2 草案
```

业务人员可以点开任意一单看到：

```text
这单为什么这么处理？
用了哪些政策依据？
查了哪些系统？
有没有越权动作？
如果不满意，如何纠正？
纠正后是否沉淀为优化建议？
```

### 16.3 持续进化

```text
1. 系统发现失败模式
2. 自动生成优化建议
3. 自动用历史案例回放测试
4. 给出新旧版本对比
5. 业务方审批
6. 先给 5% 流量灰度
7. 指标稳定后扩大到 100%
8. 异常时自动回滚到旧版本
```

### 16.4 更像 OpenClaw 的高级体验

成熟后的 Hermes 不应该只是“配置一个智能体团队”，而应该具备这些 OpenClaw 式体验：

```text
业务方说一个目标，系统自动生成团队、岗位、流程和追问清单
业务方配置长期规则，系统按 standing orders 主动巡检和复盘
系统在执行前主动召回相关历史规则和失败案例
复杂任务不靠一次回复完成，而是进入可恢复的 task flow
每个智能体只看到自己该用的技能和工具
每次结果都按 Execute-Verify-Report 输出证据链
系统发现问题后，不只是建议“改 prompt”，而是指出该改 Prompt / Flow / Skill / Memory 哪类资产
所有改进都先生成候选版本，经评估、审批、灰度后再上线
```

这会形成一个真正有领先感的闭环：

```text
业务目标
  ↓
提示词资产化
  ↓
任务流编排
  ↓
技能最小授权
  ↓
主动记忆增强
  ↓
执行-验证-汇报
  ↓
运行数据沉淀
  ↓
自进化提案
  ↓
评估审批发布
  ↓
团队持续变聪明
```

最终体验：

> 业务人员只需要定义目标、规则和边界；平台负责组织智能体团队执行、复盘和进化；高风险动作始终留在人和治理规则手里。

这就是从“Agent 工具”升级为“业务智能组织平台”的路径。
