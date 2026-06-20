# Hermes Agent Java — 端到端 Demo Playbook

> 从 0 到 1 跑通完整链路：创建工作空间 → 定义团队 → 创建场景 → 触发执行 → 审批 → 查看结果

## 前置条件

- JDK 21+
- Maven 3.9+
- API Key（在环境变量或配置中设置）

## 快速启动

```bash
cd hermes-agent-java

# 编译
mvn package -DskipTests -q

# 启动 Dashboard（默认端口 8080）
java -jar target/hermes-agent-java-*.jar server

# 或者开发模式
mvn exec:java -Dexec.mainClass="com.nousresearch.hermes.dashboard.DashboardServer"
```

启动后获取 Session Token：
```bash
# 首次启动会在控制台打印 token，也可以从日志中找
# token = xxxxxxxx
TOKEN="你的token"
BASE="http://127.0.0.1:8080"
```

---

## Playbook 1：基础流程（无需审批）

### Step 1：创建工作空间

```bash
curl -s -X POST "$BASE/api/v1/workspaces" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"workspaceId":"demo","name":"演示空间","owner":"ops"}' | jq
```

### Step 2：创建团队蓝图

创建一个"客服团队"，包含两个 agent：分类员 + 回复员

```bash
curl -s -X POST "$BASE/api/v1/workspaces/demo/team-blueprints" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "teamId": "customer-service",
    "name": "客服团队",
    "description": "处理客户咨询和售后问题",
    "entryAgent": "classifier",
    "sopTemplate": "分类 → 回复 → 质检",
    "agents": [
      {
        "agentId": "classifier",
        "displayName": "工单分类员",
        "responsibility": "对客户工单进行分类，识别问题类型和优先级",
        "allowedTools": ["read", "web_search", "memory_recall"],
        "approvalRules": []
      },
      {
        "agentId": "responder",
        "displayName": "回复撰写员",
        "responsibility": "根据工单分类撰写专业回复",
        "allowedTools": ["read", "write_file", "web_search"],
        "approvalRules": []
      }
    ]
  }' | jq
```

查看团队蓝图：
```bash
curl -s "$BASE/api/v1/workspaces/demo/team-blueprints/customer-service" \
  -H "Authorization: Bearer $TOKEN" | jq
```

### Step 3：创建业务场景

```bash
curl -s -X POST "$BASE/api/v1/workspaces/demo/scenarios" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "scenarioId": "after-sales",
    "name": "售后处理",
    "description": "处理客户的售后问题，包括退款、换货、投诉等",
    "entryTeamId": "customer-service",
    "successCriteria": [
      "正确识别客户问题类型",
      "给出专业且友好的回复",
      "所有回复符合公司政策"
    ],
    "approvalRules": [],
    "metadata": {
      "allowDelegation": true,
      "priority": "high"
    }
  }' | jq
```

查看场景列表：
```bash
curl -s "$BASE/api/v1/workspaces/demo/scenarios" \
  -H "Authorization: Bearer $TOKEN" | jq
```

### Step 4：执行场景

```bash
curl -s -X POST "$BASE/api/v1/workspaces/demo/scenarios/after-sales/execute" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"userInput":"我买的鞋子尺码不合适，想换一双大一号的，怎么办？"}' | jq
```

**响应说明：**
- `201` → 执行成功，返回 runId
- `202` → 需要审批，返回 approvalId + runId
- `500` → 执行出错（可能是模型 API 配置问题）

### Step 5：查看 Run 列表

```bash
curl -s "$BASE/api/v1/workspaces/demo/runs?limit=10" \
  -H "Authorization: Bearer $TOKEN" | jq
```

### Step 6：实时查看 Run 进度（SSE）

```bash
# 替换为你的 runId
RUN_ID="run-xxxxxxxxxx"

curl -N -H "Authorization: Bearer $TOKEN" \
  "$BASE/api/v1/workspaces/demo/runs/$RUN_ID/stream"
```

你会看到实时事件流：
- `run.state` — 当前状态
- `run.started` — 执行开始
- `step.started` / `step.completed` — 步骤进展
- `run.completed` / `run.failed` — 执行结束

### Step 7：查看 Run 详情

```bash
curl -s "$BASE/api/v1/workspaces/demo/runs/$RUN_ID" \
  -H "Authorization: Bearer $TOKEN" | jq
```

---

## Playbook 2：审批流程

### Step 1：创建需要审批的团队

修改团队，让回复员的所有操作都需要审批：

```bash
curl -s -X POST "$BASE/api/v1/workspaces/demo/team-blueprints" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "teamId": "finance-team",
    "name": "财务团队",
    "description": "处理退款、支付等高风险操作",
    "entryAgent": "refund-specialist",
    "sopTemplate": "审核 → 审批 → 执行",
    "agents": [
      {
        "agentId": "refund-specialist",
        "displayName": "退款专员",
        "responsibility": "处理客户退款申请",
        "allowedTools": ["read", "write_file", "web_search"],
        "approvalRules": ["always"]
      }
    ]
  }' | jq
```

### Step 2：创建高风险场景

```bash
curl -s -X POST "$BASE/api/v1/workspaces/demo/scenarios" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "scenarioId": "refund-processing",
    "name": "退款处理",
    "description": "处理客户的退款申请，高风险场景需要审批",
    "entryTeamId": "finance-team",
    "successCriteria": [
      "正确核实退款条件",
      "计算正确的退款金额",
      "生成退款凭证"
    ],
    "approvalRules": ["high-risk"],
    "metadata": {
      "allowDelegation": false
    }
  }' | jq
```

### Step 3：触发执行（会被审批拦截）

```bash
curl -s -X POST "$BASE/api/v1/workspaces/demo/scenarios/refund-processing/execute" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"userInput":"客户订单 #12345 申请全额退款 299 元，原因是商品质量问题"}' | jq
```

预期返回 `202 Accepted`，包含 `approvalId` 和 `runId`。

### Step 4：查看待审批列表

```bash
curl -s "$BASE/api/v1/workspaces/demo/approvals?status=PENDING" \
  -H "Authorization: Bearer $TOKEN" | jq
```

### Step 5：查看审批详情 + 时间线

```bash
# 替换为你的 approvalId
APV_ID="apv-xxxxxxxxxx"

curl -s "$BASE/api/v1/workspaces/demo/approvals/$APV_ID" \
  -H "Authorization: Bearer $TOKEN" | jq
```

查看 timeline 字段，可以看到完整的审批时间线。

### Step 6：监听审批事件（SSE）

```bash
curl -N -H "Authorization: Bearer $TOKEN" \
  "$BASE/api/v1/workspaces/demo/approvals/stream"
```

### Step 7：审批通过（自动恢复执行）

```bash
curl -s -X POST "$BASE/api/v1/workspaces/demo/approvals/$APV_ID/approve" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"actor":"manager","reason":"核实无误，同意退款"}' | jq
```

**响应说明：**
- `201` → 审批通过 + 自动恢复执行成功，返回 `runId`
- `autoResumed: true` → 标记了自动恢复
- 关联的 Run 状态从 `NEEDS_APPROVAL` 变为执行中

### Step 8：拒绝审批（自动标记 Run 失败）

可以用另一个审批测试拒绝：

```bash
curl -s -X POST "$BASE/api/v1/workspaces/demo/approvals/$APV_ID/reject" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"actor":"manager","reason":"退款条件不满足，客户已使用超过 30 天"}' | jq
```

拒绝后，关联的 Run 会自动标记为 `FAILED`。

---

## Playbook 3：工具权限控制

### Step 1：设置工作区级工具权限

工作区级策略对整个 workspace 的所有 agent 生效：

```bash
curl -s -X PUT "$BASE/api/v1/workspaces/demo/policy" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "allowedTools": ["read", "write_file", "web_search", "memory_recall"],
    "deniedTools": ["exec"]
  }' | jq
```

### Step 2：查看 agent 生效权限

```bash
curl -s "$BASE/api/v1/workspaces/demo/teams/customer-service/agents/classifier/allowed-tools" \
  -H "Authorization: Bearer $TOKEN" | jq
```

Agent 实际能使用的工具 = workspace 白名单 ∩ agent 蓝图白名单 - 全局黑名单

举个例子：如果 workspace 允许 4 个工具，classifier 蓝图只允许 3 个（不含 write_file），那么实际生效的就是 3 个。

### Step 3：验证执行时权限生效

执行场景后，查看 Run 详情中的工具调用记录。不在 allowedTools 里的工具会被拦截，返回 `Access denied` 错误。

---

## Playbook 4：Evolution 进化闭环

### Step 1：创建评估集

```bash
curl -s -X POST "$BASE/api/v1/workspaces/demo/eval-sets" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "evalSetId": "customer-service-basic",
    "name": "客服基础能力评估",
    "description": "测试客服团队的基础分类和回复能力",
    "scenarioId": "after-sales",
    "cases": [
      {"input":"我买的鞋码小了，想换大一号的","expected":"换货流程"},
      {"input":"收到的商品有破损，怎么处理？","expected":"破损赔偿流程"},
      {"input":"请问物流到哪了？","expected":"物流查询"}
    ]
  }' | jq
```

### Step 2：生成进化提案

```bash
curl -s -X POST "$BASE/api/v1/workspaces/demo/evolution-proposals/generate" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"teamId":"customer-service","evalSetId":"customer-service-basic"}' | jq
```

### Step 3：查看进化提案

```bash
curl -s "$BASE/api/v1/workspaces/demo/evolution-proposals" \
  -H "Authorization: Bearer $TOKEN" | jq
```

### Step 4：应用进化提案（生成新版本蓝图）

```bash
# 替换为你的 proposalId
PROP_ID="ep-xxxxxxxxxx"

curl -s -X POST "$BASE/api/v1/workspaces/demo/evolution-proposals/$PROP_ID/apply" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{}' | jq
```

### Step 5：激活新版本

```bash
curl -s -X POST "$BASE/api/v1/workspaces/demo/team-blueprints/customer-service/activate" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"version": 2}' | jq
```

---

## Playbook 5：工具级审批（断点续传）

最强大的审批模式：agent 执行到一半遇到高风险工具时暂停，审批通过后从断点继续。

### Step 1：创建带工具审批的团队

```bash
curl -s -X POST "$BASE/api/v1/workspaces/demo/team-blueprints" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "teamId": "ops-team",
    "name": "运维团队",
    "description": "处理服务器运维任务",
    "entryAgent": "ops-agent",
    "agents": [
      {
        "agentId": "ops-agent",
        "displayName": "运维助手",
        "responsibility": "诊断和处理服务器问题",
        "allowedTools": ["read", "exec", "write_file", "web_search"],
        "approvalRules": [],
        "toolApprovalRules": [
          "high-risk",
          "tool:exec",
          "contains:rm -rf"
        ]
      }
    ]
  }' | jq
```

**toolApprovalRules 语法：**
- `always` — 所有工具调用都要批
- `high-risk` — exec/delete/write/refund/email 等高风险工具
- `external` — send/post/browser/web_fetch 等对外工具
- `tool:exec` — 特定工具
- `contains:rm -rf` — 工具参数包含特定关键词

### Step 2：触发执行

```bash
curl -s -X POST "$BASE/api/v1/workspaces/demo/scenarios/ops-task/execute" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"userInput":"清理 /tmp 下大于 100MB 的文件"}' | jq
```

执行返回 201 后，agent 开始工作。当 LLM 决定调用 `exec` 工具时会被拦截。

### Step 3：查看待审批列表

```bash
curl -s "$BASE/api/v1/workspaces/demo/approvals?status=PENDING" \
  -H "Authorization: Bearer $TOKEN" | jq '.approvals[] | select(.metadata.type == "tool-call")'
```

工具级审批会有 `metadata.type == "tool-call"` 标识，evidence 里包含：
- `toolName` — 被拦截的工具名
- `toolArguments` — 工具参数
- `matchedRule` — 命中的审批规则
- `agentId` — 哪个 agent 想调用

### Step 4：审批通过（断点续传）

```bash
APV_ID="apv-xxxxxxxxxx"

curl -s -X POST "$BASE/api/v1/workspaces/demo/approvals/$APV_ID/approve" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"actor":"sre-lead","reason":"参数已审查，允许执行"}' | jq
```

响应中：
- `toolApproval: true` — 这是工具级审批
- `agentResult` — agent 从断点恢复后的最终结果
- `message` — "Tool approval approved — agent execution resumed"

**重要：agent 不会重新执行整个任务，而是从被拦截的那个工具调用继续。**

### Step 5：审批拒绝（注入拒绝错误）

```bash
curl -s -X POST "$BASE/api/v1/workspaces/demo/approvals/$APV_ID/reject" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"actor":"sre-lead","reason":"参数有风险，请改用更安全的方式"}' | jq
```

被拒绝时，agent 会收到 "Tool call rejected" 错误作为工具结果，LLM 看到后会自动调整策略（比如改用其他方式）。

---

## Playbook 6：Canary 灰度发布

新版本团队蓝图先放给少量流量，观察指标后决定全量或回滚。

### Step 1：在已有团队上创建 v2

假设你已经有 customer-service 团队（v1 active），先创建 draft v2：

```bash
curl -s -X POST "$BASE/api/v1/workspaces/demo/team-blueprints/customer-service/versions" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "changeSummary": "改进分类员的分类准确度",
    "agents": [
      {
        "agentId": "classifier",
        "displayName": "工单分类员 v2",
        "responsibility": "更精细地对客户工单进行分类，识别问题类型、优先级和情感倾向",
        "allowedTools": ["read", "web_search", "memory_recall"]
      },
      {
        "agentId": "responder",
        "displayName": "回复撰写员",
        "responsibility": "根据工单分类撰写专业回复",
        "allowedTools": ["read", "write_file", "web_search"]
      }
    ]
  }' | jq
```

### Step 2：启动灰度（10% 流量到 v2）

```bash
curl -s -X POST "$BASE/api/v1/workspaces/demo/teams/customer-service/canaries" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "toVersion": 2,
    "trafficPercent": 10,
    "metadata": {"reason": "测试新版分类员"}
  }' | jq
```

返回的 `releaseId` 用于后续操作。

### Step 3：跑流量观察

接下来执行 100 次场景，大约 10 次会路由到 v2：

```bash
for i in {1..100}; do
  curl -s -X POST "$BASE/api/v1/workspaces/demo/scenarios/after-sales/execute" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"userInput\":\"请求 $i: 退换货咨询\"}" > /dev/null
done
```

### Step 4：查看灰度指标

```bash
curl -s "$BASE/api/v1/workspaces/demo/teams/customer-service/canaries/active" \
  -H "Authorization: Bearer $TOKEN" | jq '.canary.metrics'
```

返回类似：
```json
{
  "canaryTotal": 11,
  "canarySucceeded": 10,
  "canaryFailed": 1,
  "canarySuccessRate": 0.909,
  "canaryAvgDurationMs": 2150.5,
  "canaryAvgCost": 0.045,
  "baselineTotal": 89,
  "baselineSucceeded": 84,
  "baselineFailed": 5,
  "baselineSuccessRate": 0.943,
  "baselineAvgDurationMs": 1850.2,
  "baselineAvgCost": 0.038
}
```

### Step 5：逐步放量

```bash
RELEASE_ID="canary-xxxxxxxx"

# 25%
curl -s -X POST "$BASE/api/v1/workspaces/demo/teams/customer-service/canaries/$RELEASE_ID/traffic" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"trafficPercent": 25}' | jq

# 50%
curl -s -X POST "$BASE/api/v1/workspaces/demo/teams/customer-service/canaries/$RELEASE_ID/traffic" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"trafficPercent": 50}' | jq
```

### Step 6：决策 — Promote 或 Rollback

**如果指标好（v2 更好），全量切换：**
```bash
curl -s -X POST "$BASE/api/v1/workspaces/demo/teams/customer-service/canaries/$RELEASE_ID/promote" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{}' | jq
```

**如果有问题，回滚到 v1：**
```bash
curl -s -X POST "$BASE/api/v1/workspaces/demo/teams/customer-service/canaries/$RELEASE_ID/rollback" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{}' | jq
```

---

## 常用 API 速查

### 工作空间 Workspace

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/workspaces` | 列出所有工作空间 |
| POST | `/api/v1/workspaces` | 创建工作空间 |
| GET | `/api/v1/workspaces/{id}` | 获取工作空间详情 |

### 团队蓝图 Team Blueprint

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/workspaces/{id}/team-blueprints` | 列出团队 |
| POST | `/api/v1/workspaces/{id}/team-blueprints` | 创建团队 |
| GET | `/api/v1/workspaces/{id}/team-blueprints/{teamId}` | 团队详情 |
| POST | `/api/v1/workspaces/{id}/team-blueprints/{teamId}/activate` | 激活指定版本 |

### 场景 Scenario

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/workspaces/{id}/scenarios` | 列出场景 |
| POST | `/api/v1/workspaces/{id}/scenarios` | 创建场景 |
| POST | `/api/v1/workspaces/{id}/scenarios/{sId}/execute` | 执行场景 |

### 运行 Run

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/workspaces/{id}/runs` | 列出运行记录 |
| GET | `/api/v1/workspaces/{id}/runs/{runId}` | Run 详情 |
| SSE | `/api/v1/workspaces/{id}/runs/{runId}/stream` | 实时进度流 |

### 审批 Approval

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/workspaces/{id}/approvals` | 审批列表 |
| GET | `/api/v1/workspaces/{id}/approvals/{apvId}` | 审批详情 |
| POST | `/api/v1/workspaces/{id}/approvals/{apvId}/approve` | 通过审批 |
| POST | `/api/v1/workspaces/{id}/approvals/{apvId}/reject` | 拒绝审批 |
| SSE | `/api/v1/workspaces/{id}/approvals/stream` | 审批事件流 |
| SSE | `/api/v1/workspaces/{id}/approvals/{apvId}/stream` | 单审批事件流 |

### 策略 Policy

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/workspaces/{id}/policy` | 查看 workspace 级策略 |
| PUT | `/api/v1/workspaces/{id}/policy` | 更新 workspace 级策略（allowedTools/deniedTools） |
| GET | `/api/v1/workspaces/{id}/teams/{tId}/agents/{aId}/allowed-tools` | 查看 agent 生效工具权限（workspace ∩ blueprint） |

### Canary 灰度

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/workspaces/{id}/teams/{tId}/canaries` | 列出所有灰度发布历史 |
| POST | `/api/v1/workspaces/{id}/teams/{tId}/canaries` | 启动新灰度（toVersion + trafficPercent） |
| GET | `/api/v1/workspaces/{id}/teams/{tId}/canaries/active` | 获取当前 active canary + 双版本对比 metrics |
| POST | `/api/v1/workspaces/{id}/teams/{tId}/canaries/{rid}/traffic` | 调整流量百分比 |
| POST | `/api/v1/workspaces/{id}/teams/{tId}/canaries/{rid}/promote` | 全量切换到 toVersion |
| POST | `/api/v1/workspaces/{id}/teams/{tId}/canaries/{rid}/rollback` | 回滚到 fromVersion |

### Evolution Proposal

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/workspaces/{id}/evolution-proposals` | 列出进化提案 |
| POST | `/api/v1/workspaces/{id}/evolution-proposals/generate` | 生成进化提案 |
| POST | `/api/v1/workspaces/{id}/evolution-proposals/{pid}/apply` | 应用提案（生成新版本蓝图） |

### Eval Set

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/workspaces/{id}/eval-sets` | 列出评估集 |
| POST | `/api/v1/workspaces/{id}/eval-sets` | 创建评估集 |
| POST | `/api/v1/workspaces/{id}/eval-sets/{esId}/run` | 运行评估 |

---

## 常见问题

### Q: 执行场景时返回 500 错误？
A: 大概率是模型 API 没配置。检查 `config.yaml` 中的 model 配置，确保 API Key 和 Base URL 正确。

### Q: 工具级审批和场景级审批有什么区别？

| 维度 | 场景级 | 工具级 |
|------|--------|--------|
| 配置位置 | `agent.approvalRules` | `agent.toolApprovalRules` |
| 触发时机 | scenario.execute 之前 | agent 调用具体工具时 |
| 暂停粒度 | 整个场景没开始 | 执行到一半暂停 |
| 恢复方式 | 重新执行（保留输入） | **断点续传**（保留对话状态） |
| 适用场景 | 高风险整体流程 | 高风险单个动作 |

### Q: Canary 流量路由是随机的吗？

A: **不是**，是**确定性哈希路由**。同一个 user/request 始终路由到同一版本。这样保证用户体验一致，并能稳定对比两个版本的真实表现。

### Q: 工具级审批拒绝后，agent 会怎么样？

A: agent 会收到 "Tool call rejected" 错误作为该工具的执行结果。LLM 看到错误后会自动调整策略（比如改用其他工具或换种方式）。这比直接终止任务更智能。

### Q: 审批后没有自动恢复执行？
A: 确保审批记录中有 `scenarioId` 字段（自动触发的审批会有，手动创建的审批需要手动传 scenarioId）。

### Q: 工具权限好像没生效？
A: 检查三层策略是否正确交集：
1. 租户级 SecurityPolicy
2. 工作区级 WorkspacePolicy
3. Agent 蓝图级 allowedTools

如果都是空的，默认是"全部允许"。需要至少配置一层白名单才会有限制效果。

### Q: 数据存在哪里？
A: 默认存在 `~/.hermes/business/workspaces/` 目录下，按 workspaceId 分目录，JSON 文件存储。
