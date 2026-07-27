# Hermes Agent Java

**AI 驱动的业务自动化平台 —— 多租户、多模型、可观测、可编排**

Hermes Agent Java 是一个面向企业级云端部署的 AI Agent harness 底座，支持业务系统通过标准化 API 接入，实现 AI 驱动的业务流程自动化。

## 核心能力

### 🏢 多租户隔离

每个租户拥有独立的模型配置、API Key、配额、计费和审计日志。

| 能力 | 说明 |
|------|------|
| 模型配置隔离 | 每租户独立 provider / model / base_url / temperature |
| 多 Provider API Key | 同时配置 OpenAI + Anthropic + DeepSeek 等多个 Key |
| BROK 混合密钥 | `key_source: tenant`（自带）/ `platform`（代付）/ `hybrid`（默认） |
| model_routes 混合路由 | 平台预定义别名（fast/smart/cheap）+ 租户可覆盖 |
| ProviderCatalog 白名单 | 8 个内置 provider，平台控制可用列表 |
| 配额 + 限流 | 请求次数 / token 用量 / 每秒速率，三层防护 |
| 计费记录 | JSONL / MySQL 双模式，含 model / tokens / cost / sessionId |
| 配置校验 | `validateModelConfig()` 一步检查 provider / key / key_source / model |

### 🌐 中心化配置存储

MySQL 持久化 + 30s TTL 热更新，多实例部署配置实时同步。

| 组件 | 说明 |
|------|------|
| ConfigRepository | 接口抽象（LocalConfigRepository + MysqlConfigRepository） |
| ConfigCache | 30s TTL 缓存 + invalidate + 轮询 `updated_at` |
| DataSourceFactory | HikariCP 连接池（`-Ddb.url` / `-Ddb.username` / `-Ddb.password`） |
| Admin API | 24 个 HTTP 端点管理租户配置（不需要 SSH 改文件） |
| SecretStore | 接口抽象（FileSecretStore / InMemorySecretStore / VaultSecretStore / MysqlSecretStore） |
| BillingRepository | 接口抽象（JsonlBillingRepository / MysqlBillingRepository） |

### 🔌 业务系统接入

标准化的 REST API + Java SDK，业务系统 3 行代码接入。

```java
HermesClient client = HermesClient.builder()
    .baseUrl("http://hermes:8080")
    .apiKey("ak_xxx")
    .build();

// 同步发消息
String reply = client.sendMessage("agent-1", "查询今天的订单").reply();

// 异步任务
String taskId = client.submitTask("agent-1", "生成月度报告").taskId();
TaskStatus status = client.getTask(taskId);
```

**Integration Gateway API（12 端点）：**

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/v1/agents/{id}/messages` | POST | 发消息给 Agent |
| `/api/v1/agents` | GET | 列出可用 Agent |
| `/api/v1/agents/{id}/sessions` | GET | 列出会话 |
| `/api/v1/tasks` | POST | 提交异步任务 |
| `/api/v1/tasks/{id}` | GET | 查任务状态 |
| `/api/v1/tasks/{id}/cancel` | POST | 取消任务（DB + 中断 chain） |
| `/api/v1/tasks/{id}/interrupt` | POST | 中断运行中的 chain |
| `/api/v1/tasks/{id}/status` | GET | 查 chain 运行状态 |
| `/api/v1/tenants/{id}/usage` | GET | 查用量 |
| `/api/v1/tenants/{id}/billing` | GET | 查计费 |
| `/api/v1/webhooks` | POST/GET | 注册/列出回调 |
| `/api/v1/systems` | POST | 注册业务系统 |
| `/api/v1/health` | GET | 健康检查 |

**认证方式：**
- API Key（`Bearer ak_xxx`）—— 业务系统
- JWT（`Bearer jwt_xxx`）—— 人类用户（HS256 无状态）
- sessionToken —— 管理员（兼容模式）

**RBAC 4 角色：**
| 角色 | 读 | 写 | 管理 | Portal UI |
|------|---|---|------|-----------|
| ADMIN | ✅ | ✅ | ✅ | ✅ |
| OPERATOR | ✅ | ✅ | ❌ | ✅ |
| VIEWER | ✅ | ❌ | ❌ | ✅ |
| API_ONLY | ✅ | ✅ | ❌ | ❌ |

### ⚡ 执行引擎

| 能力 | 说明 |
|------|------|
| TenantAwareAIAgent | 多租户感知的 Agent 运行时 |
| TenantAwareToolDispatcher | 8 关卡安全执行流水线（Hook → Permission → Prelude → Approval → Negotiator → Dispatch → PostHook → Transform） |
| ApprovalSystem | 4 模式（AUTO/PROMPT/REQUIRE/DENY）+ IM/Portal/控制台三通道 |
| HookEngine | 17 个 HookType 覆盖工具/LLM/API/会话/子 Agent |
| AsyncTaskQueue | 4 worker 线程池 + MySQL 持久化 + FOR UPDATE SKIP LOCKED |
| ModelChain | 多模型编排（plan → execute → review） |
| ModelRoutingPolicy | 角色 → 模型别名映射（planner→smart, executor→fast） |
| WebhookDispatcher | HMAC-SHA256 签名 + 3 次指数退避重试 + 自动禁用 |

### 🔗 Connector 生态

3 个内置 Connector，通过 Connector 接口可自定义扩展：

| Connector | 操作 | 说明 |
|-----------|------|------|
| HttpConnector | GET/POST/PUT/DELETE | 通用 HTTP API，支持 auth header + API Key |
| SqlConnector | query/execute/tables | JDBC 数据库，DDL 阻断 + SELECT-only 查询 |
| WebhookConnector | send/notify | 发出 webhook，HMAC 签名 |

### 📊 可观测性

| 能力 | 说明 |
|------|------|
| BusinessMetricsCollector | 4 维度指标（tenant / model / agent / system） |
| Prometheus 导出 | `/metrics` 端点，带 label 维度 |
| ExecutionTrace | 执行链路追踪（span + attributes + duration） |
| TraceStore | 10k 内存 ring buffer + API 查询 |
| TenantAuditLogger | 审计日志（合规用） |

### 🔄 版本管理 + 灰度发布

| 能力 | 说明 |
|------|------|
| ConfigVersionService | 配置快照 + 回滚 + 审计轨迹 |
| CanaryDeploymentManager | 三策略灰度（IMMEDIATE / PERCENTAGE / EXPLICIT） |
| 会话粘性 | 同 sessionId 始终走同一配置（hash-based） |
| 渐进发布 | promote（10% → 50% → 100%）+ autoRollback |

## 快速开始

### 1. 构建

```bash
mvn clean package -DskipTests
```

### 2. 启动（LOCAL 模式，单实例）

```bash
java -jar target/hermes-agent-java.jar
```

### 3. 启动（CLUSTER 模式，多实例 + MySQL）

```bash
java -Dhermes.profile=cluster \
     -Ddb.url=jdbc:mysql://localhost:3306/hermes \
     -Ddb.username=hermes \
     -Ddb.password=secret \
     -Djwt.secret=your-jwt-secret \
     -jar target/hermes-agent-java.jar
```

### 4. 初始化数据库

```bash
mysql -u root -p hermes < src/main/resources/sql/schema.sql
```

### 5. 注册业务系统

```bash
curl -X POST http://localhost:8080/api/v1/systems \
  -H "Content-Type: application/json" \
  -d '{"systemId":"erp","displayName":"ERP System","tenantId":"default"}'

# 返回：{"systemId":"erp","apiKey":"ak_xxx","displayName":"ERP System"}
```

### 6. 发消息给 Agent

```bash
curl -X POST http://localhost:8080/api/v1/agents/agent-1/messages \
  -H "Authorization: Bearer ak_xxx" \
  -H "Content-Type: application/json" \
  -d '{"message":"查询今天的订单"}'
```

### 7. 提交异步任务

```bash
curl -X POST http://localhost:8080/api/v1/tasks \
  -H "Authorization: Bearer ak_xxx" \
  -H "Content-Type: application/json" \
  -d '{"agentId":"agent-1","input":"生成月度报告","priority":1}'
```

### 8. 注册 Webhook 回调

```bash
curl -X POST http://localhost:8080/api/v1/webhooks \
  -H "Authorization: Bearer ak_xxx" \
  -H "Content-Type: application/json" \
  -d '{"url":"https://erp.com/hermes-callback","events":["task.completed"],"secret":"whsec_xxx"}'
```

## 数据库表结构

| 表 | 用途 |
|---|------|
| `tenant_model_config` | 租户模型配置（provider/model/apiKey/base_url/key_source） |
| `tenant_api_key` | 租户 API Key（per-provider，明文） |
| `tenant_model_route` | 租户 model_routes（别名路由） |
| `platform_model_route` | 平台预定义 model_routes |
| `platform_provider` | Provider Catalog（8 内置 provider） |
| `platform_api_key` | 平台代付 API Key 池 |
| `tenant_quota` | 租户配额（含 onExceed/degrade） |
| `billing_record` | 计费记录（per-call） |
| `business_system` | 业务系统注册（API Key 认证） |
| `async_task` | 异步任务（PENDING→RUNNING→COMPLETED/FAILED） |
| `webhook_subscription` | Webhook 订阅 |
| `user_account` | 用户账户 |
| `workspace_member` | 工作区成员关系（RBAC） |
| `config_version` | 配置版本快照 |

## API Key 解析链

```
resolveApiKey(provider):
  1. secrets.env / tenant_api_key: {PROVIDER}_API_KEY  (e.g. OPENAI_API_KEY)
  2. secrets.env / tenant_api_key: API_KEY              (通用兜底)
  3. config.yaml: model.api_key                          (仅当 provider == 默认)
  4. Platform Key: -Dplatform.{PROVIDER}_API_KEY         (代付，hybrid 模式)
  5. null → 抛异常
```

## 四层模型配置体系

```
Layer 4: Request    | temperature / max_tokens          (单次调用)
Layer 3: Session    | ModelOverride                      (会话级，不存 api_key)
Layer 2: Tenant     | model.* + model_routes + secrets    (核心改造层)
Layer 1: Platform   | ProviderCatalog + 预定义 routes     (全局)
```

解析优先级：Request > Session ModelOverride > Tenant model_routes > Tenant model.* > Platform

## 多模型编排示例

**快速使用（默认 chain，schema-aware prompt）：**

```java
ModelChain chain = ModelChain.builder().buildDefault();
// planner(prompt=null -> schema-aware) -> executor -> reviewer

ChainResult result = chain.execute(tenantConfig, globalConfig, input, tools);
// result.output()  -> 最终输出
// result.traceId() -> 追踪 ID
// result.plan()    -> ExecutionPlan (goal/steps/successCriteria)
```

**自定义 prompt：**

```java
ModelChain chain = ModelChain.builder()
    .plan("你是规划师，将任务分解为步骤")           // "smart" 别名 (Claude)
    .execute("你是执行者，使用工具执行每个步骤")      // "fast" 别名 (GPT-4o-mini)
    .review("你是审查员，检查结果并提出改进建议")     // "smart" 别名 (Claude)
    .build();
```

**中断运行中的 chain：**

```java
// 后端
taskProcessor.interruptChain(taskId);  // 当前步骤完成后停

// API
curl -X POST http://hermes:8080/api/v1/tasks/task-123/interrupt \
  -H "Authorization: Bearer ak_xxx"
```

**触发 chain 模式：**

```bash
# 方式 1: [chain] 前缀
curl -X POST http://hermes:8080/api/v1/agents/agent-1/messages \
  -H "Authorization: Bearer ak_xxx" \
  -d '{"message":"[chain] 分析日志并总结错误"}'

# 方式 2: tenant config
# config.yaml:
# chain_mode: true
```

## 灰度发布示例

```java
// 开始灰度：10% 流量用新配置
canaryManager.startCanary("tenant-A", "ver_123",
    CanaryDeploymentManager.Strategy.PERCENTAGE, 10, true, 5);

// 提升到 50%
canaryManager.promote("tenant-A", 50);

// 全量发布
canaryManager.complete("tenant-A");

// 或回滚
canaryManager.abort("tenant-A", "error rate too high");
```

## 项目结构

```
src/main/java/com/nousresearch/hermes/
├── agent/                 # Agent 运行时
│   ├── TenantAwareAIAgent       # 多租户 Agent
│   ├── ModelChain               # 多模型编排 (F1)
│   └── ModelRoutingPolicy        # 角色→模型映射 (F1)
├── auth/                  # 用户身份与权限 (D5-D6)
│   ├── UserAccount              # 用户实体 + Role 枚举
│   ├── UserRbacService          # MySQL 用户/成员管理
│   └── JwtService               # JWT 签发/校验
├── billing/               # 计费 (B4)
│   ├── TenantUsageRecord        # 计费记录
│   ├── TenantBillingService     # 计费服务
│   └── repository/              # BillingRepository 接口 + 实现
├── business/              # 业务场景
│   └── event/                   # BusinessEventBus
├── collaboration/         # 多 Agent 协作
│   ├── ScenarioOrchestrator
│   └── AgentRuntimeProfile
├── config/                # 配置
│   ├── HermesConfig             # 全局配置
│   ├── ModelRoute               # 模型路由
│   ├── repository/              # ConfigRepository + MySQL + Cache (C1-C7)
│   │   ├── ConfigRepository       # 接口
│   │   ├── LocalConfigRepository  # 文件实现
│   │   ├── MysqlConfigRepository # MySQL 实现
│   │   ├── ConfigCache           # TTL 热更新
│   │   └── DataSourceFactory     # HikariCP
│   └── versioning/              # 版本管理 + 灰度 (P3)
│       ├── ConfigVersion          # 版本快照
│       ├── ConfigVersionService   # 快照/回滚/审计
│       └── CanaryDeploymentManager # 灰度发布
├── connector/             # Connector 生态 (E3)
│   ├── Connector                 # 接口
│   ├── ConnectorRegistry
│   └── builtin/
│       ├── HttpConnector          # 通用 HTTP
│       ├── SqlConnector           # JDBC 数据库
│       └── WebhookConnector       # 发出 webhook
├── dashboard/             # Web 服务
│   ├── DashboardServer          # Javalin 路由
│   └── handlers/
│       ├── AdminConfigHandler    # Admin API (C4)
│       └── IntegrationGatewayHandler # 业务系统 API (D2)
├── gateway/               # API 网关
│   ├── OpenAICompatHandler      # OpenAI 兼容 API
│   └── integration/             # 业务系统接入 (D1-D4)
│       ├── BusinessSystem        # 业务系统实体
│       ├── BusinessSystemRegistry # API Key 认证
│       ├── AsyncTask            # 异步任务
│       ├── AsyncTaskQueue       # 线程池队列
│       ├── AgentTaskProcessor   # Agent 桥接 + chain 路由 + 中断 (E1)
│       ├── WebhookDispatcher    # 事件推送 (D4)
│       ├── IntegrationGatewayHandler # REST API
│       └── IntegrationBootstrap  # 启动初始化
├── model/                 # 模型客户端
│   ├── ModelClient              # LLM API 客户端
│   ├── ChatCompletionResponse
│   └── ModelMessage
├── observability/         # 可观测性 (F2)
│   ├── BusinessMetricsCollector # 4 维度指标
│   ├── ExecutionTrace           # 执行链路
│   └── TraceStore               # ring buffer
├── platform/              # 平台级
│   ├── ProviderCatalog          # Provider 白名单 (B3)
│   └── secret/                  # SecretStore 接口 (B7)
│       ├── SecretStore            # 接口
│       ├── FileSecretStore        # 文件实现
│       ├── InMemorySecretStore   # 内存实现
│       ├── MysqlSecretStore       # MySQL 实现 (C5)
│       └── VaultSecretStore       # Vault stub
├── sdk/                   # Java SDK (D7)
│   └── HermesClient             # 3 行代码接入
├── tenant/                # 多租户
│   ├── core/
│   │   ├── TenantConfig         # 租户配置（model/routes/quota/secrets）
│   │   ├── TenantContext        # 租户上下文
│   │   └── TenantManager       # 租户管理器
│   └── quota/                   # 配额管理
│       ├── TenantQuota          # 配额实体
│       └── TenantQuotaManager  # 配额检查
└── tools/                # 工具系统
    ├── ToolRegistry
    ├── TenantAwareToolDispatcher # 8 关卡安全流水线
    └── ToolCallPrelude           # 工具调用前奏
```

## 技术栈

| 组件 | 技术 |
|------|------|
| 语言 | Java 21 |
| Web 框架 | Javalin 6 |
| 数据库 | MySQL 8.x（兼容 H2 测试） |
| 连接池 | HikariCP 5.1 |
| JSON | FastJSON2 |
| 模板 | Pebble |
| 构建 | Maven |
| 测试 | JUnit 5 + H2（MySQL 兼容模式） |

## 测试

```bash
# 全量测试
mvn test

# 测试统计：853 tests, 0 failures, 0 errors
```

## 部署拓扑

```
                    ┌─────────────────────────┐
                    │    Load Balancer (NLB)   │
                    └────────────┬────────────┘
                                 │
              ┌──────────────────┼──────────────────┐
              │                  │                  │
     ┌────────┴───────┐ ┌───────┴────────┐ ┌──────┴────────┐
     │ Hermes Node 1  │ │ Hermes Node 2   │ │ Hermes Node 3  │
     │ (CLUSTER mode) │ │ (CLUSTER mode)  │ │ (CLUSTER mode) │
     │ - API + Web    │ │ - API + Web    │ │ - API + Web    │
     │ - Portal SPA   │ │ - Portal SPA   │ │ - Portal SPA   │
     │ - Ops SPA      │ │ - Ops SPA      │ │ - Ops SPA      │
     │ - NOC SPA      │ │ - NOC SPA      │ │ - NOC SPA      │
     └───────┬────────┘ └───────┬─────────┘ └───────┬────────┘
             │                  │                   │
             │     ┌───────────┴──────────────┐    │
             │     │  MySQL (config + billing)│    │
             │     │  + HikariCP pool         │    │
             │     └──────────────────────────┘    │
             │                                       │
     ┌───────┴───────────────────────────────────────┘
     │
     │     ┌──────────────────────────┐
     │     │  业务系统 A (ERP)        │
     │     │  HermesClient SDK        │
     │     │  -> ak_xxx 认证          │
     │     └──────────────────────────┘
     │
     │     ┌──────────────────────────┐
     │     │  业务系统 B (电商平台)    │
     │     │  HermesClient SDK        │
     │     │  -> ak_yyy 认证          │
     │     └──────────────────────────┘
```

## 前端 SPA

三个独立 SPA + 一个超薄 hub，各自独立 build 产物：

| SPA | 端口 | 路径 | 视觉风格 | 说明 |
|-----|------|------|---------|------|
| Hub | 5174 | `web/` | 极简卡片 | 三产品入口 |
| Portal | 5175 | `web/portal/` | oklch 暖色 H5 | 业务前店（数字员工、任务、审批） |
| Ops | 5176 | `web/ops/` | dark teal | 控制台（租户、模型、对比） |
| NOC | 5177 | `web/noc/` | amber 告警风 | 运维控制中心（SLA、DLQ、Trace） |

```bash
# 构建
npm run build:all

# 开发
npm run dev   # concurrently 跑 4 个端口
```

**编排可视化页面：**
- Portal `/runs/:ws/:id` -> ChainPlanCard 组件（目标、步骤、完成标准、阶段追踪）
- NOC `/traces/:traceId` -> span 时间线（planner/executor/reviewer/retry 分组）

## License

Copyright © 2024-2026 NousResearch. All rights reserved.
