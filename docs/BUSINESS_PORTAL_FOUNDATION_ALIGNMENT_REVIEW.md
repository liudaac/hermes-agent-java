# Business Portal Foundation Alignment Review

Date: 2026-06-17

This document looks back at the original plan in `docs/BUSINESS_AGENT_TEAM_PLATFORM_PLAN.md` and the audit/inventory chain, and checks whether the iteration series so far has drifted.

---

## 1. What the plan says

Key intent baseline:

```text
Business Portal is a business-facing surface for an agent team platform.
Hermes foundation owns tenant, tools, prompts, runtime teams, intent execution, traces, approvals, evolution, delegation truth.
Section 18: stop expanding object-style portal; converge to scenario-driven team generation.
Section 19: must reuse Hermes foundation, do not build a second platform.
Section 20: Foundation Audit identifies overlap and source of truth.
Section 21: Foundation Capability Inventory + iteration log.
```

Self-imposed constraints:

```text
No new Business Portal UI tabs.
No new business domain objects.
No generation API.
No autonomous evolution proposal apply.
No runtime team execution from blueprint compile.
No direct channel notification delivery.
No second approval engine, trace store, workflow engine or knowledge store.
```

---

## 2. What was actually built

Iteration log condensed:

```text
Foundation convergence (iterations 1–15):
1.  FoundationCapabilityValidator + report
2.  TeamBlueprintCompiler + result
3.  ScenarioIntentAdapter + request
4.  BusinessRunProjectionAdapter
5.  BusinessApprovalAdapter
6.  EvolutionProposalAdapter
7.  PromptAssetResolver / PromptContext / Prompt bridge
8.  BusinessPortalAdapterChainSmokeTest
9.  BusinessPortalFoundationFacade + Registry
10. docs/BUSINESS_PORTAL_FOUNDATION_ADAPTERS.md (development contract)
11. BusinessPortalFoundationArchitectureTest (boundary guard)
12. BusinessInsightProjectionAdapter
13. Insight projection wired into facade
14. BusinessPortalFoundationDiagnostics (read-only projection)
15. docs/BUSINESS_PORTAL_FOUNDATION_BASELINE.md (consolidation)

Read-only product integration phase:
- DashboardServer auth middleware safety fix (skipRemainingHandlers after 401/400).
- GET  /api/v1/business/foundation/diagnostics
- POST /api/v1/business/foundation/team-blueprints/validate
- POST /api/v1/business/foundation/prompt-context/preview
- POST /api/v1/business/foundation/scenarios/plan
- POST /api/v1/business/foundation/runs/project
- POST /api/v1/business/foundation/insights/project
- POST /api/v1/business/foundation/evolution-proposals/preview
- docs/BUSINESS_PORTAL_FOUNDATION_READONLY_ENDPOINTS.md
- DashboardBusinessFoundationReadOnlyEndpointsSmokeTest
```

---

## 3. Alignment with plan sections

| Plan section | Intended outcome | Status | Comment |
|---|---|---|---|
| 18 场景驱动 | Stop object-only portal | On track | Adapters and read-only endpoints all converge on scenario/intent/run path |
| 19 复用基座 | Reuse foundation, no second platform | On track | Adapter chain + facade + architecture test enforce this |
| 20 Foundation Audit | Identify overlap and SoT | Done | Followed by capability inventory |
| 21 Capability Inventory | Code-level map | Done | Iteration log appended |
| 5.1 Workspace | Façade over Tenant | On track | Workspace remains business façade |
| 5.2 Scenario | Business framing | On track | `ScenarioIntentAdapter` routes to `IntentOrchestrator` |
| 5.3 Team Blueprint | Versioned design-time spec | On track | Compiler maps to `TeamManager` / `AgentRole` |
| 5.4 Agent Blueprint | Role card | On track | Validated against `ToolRegistry` + tenant policy |
| 5.5 Run | Business projection | On track | `BusinessRunProjectionAdapter` projects `IntentRun/AgentTrace` |
| 5.6 Eval Set | Evaluation governance | Partial | Insight adapter accepts `AgentEvaluation` results; no Business Portal EvalSet object yet (deliberate pause) |
| 5.7 Evolution Proposal | Optimization proposal | On track | Foundation governance boundaries preserved |
| 6 自动编排 | Rule / model / experience encoding | Not started | Deliberately deferred until generation phase |
| 7 自进化分级 | L1–L4 evolution | Partial | L1 (prompt / operating manual review) reachable via preview/projection; L2–L4 deferred |
| 8 安全阀 | Versioning, replay, canary, rollback, approval | Partial | Versioning + approval boundary present; replay/canary/auto-rollback not yet wired |
| 10 API 分层 | Management/team/scenario APIs | Partial | Read-only `/business/foundation/*` layer added; existing CRUD still present in parallel |
| 2.2 领先智感 | Consultant/PM/analyst feel | Partial | Run/insight/governance projections exist at API level but no Business Portal UI surface yet |
| 3.4.4 视觉与交互 | Modern Business Portal UI | Not started | Deliberate non-goal during foundation convergence |

---

## 4. Deviations worth tracking

These are small drifts not yet visible in the plan:

1. **BusinessRunRecord 双轨**
   - `BusinessRunService.createRun(...)` 仍允许创建非 foundation projection 的 run。
   - Baseline doc 把它定义为 `manual/demo/smoke`，但代码层没有强制 `metadata.source` 标记。
   - 风险：若产品 UI 直接列出所有 BusinessRun，可能混淆 foundation truth 与手工 record。

2. **BusinessInsightService 与 BusinessInsightProjectionAdapter 并存**
   - 前者基于 file-backed business records 聚合。
   - 后者基于 foundation traces / evals / evolution summary。
   - 当前不算偏差，但产品 UI 出现时必须明确两者来源差异。

3. **EvalSet 在 Business Portal 缺位**
   - 计划中 5.6 描述了 EvalSet 业务对象。
   - 当前只有 foundation `AgentEvaluation`，业务侧暂无 EvalSet/EvalSuite 业务对象。
   - 这是一种刻意留白，但应在下一阶段讨论是否补上 read-only EvalRun projection。

4. **Replay / Canary / Auto rollback 未启动**
   - 计划 8.2 / 8.3 / 8.4 暂未对接。
   - 风险较低，但在 mutation phase 之前需要明确其入场顺序。

5. **Notification 通道集成未启动**
   - `BusinessNotificationAdapter` 仍是 backlog。
   - 当前 read-only 范围不需要它，但发起 mutation/审批通知前要先做。

6. **BusinessTeamGenerationService 仍未实现**
   - 计划 18 / 19 都明确要求“先收敛再做生成”。
   - 收敛已基本达成，下阶段进入条件已写入 baseline doc 第 5 节。
   - 不算偏差，是有意识地推迟。

---

## 5. Where we are vs. plan’s “下一刀建议” (section 12)

Section 12 in the plan suggests the next concrete cuts. Cross-checking:

```text
计划 12.1 收敛 Business Portal 对象到 façade —— 已完成
计划 12.2 把基座工具/审批/进化绑定到 adapter —— 已完成（除 Notification）
计划 12.3 引入 BusinessTeamGenerationService —— 故意延后，进入条件已写明
计划 12.4 推动 Scenario-driven workflow —— 通过 ScenarioIntentAdapter 部分实现
计划 12.5 Read-only 产品集成层 —— 已完成第一波 7 个 endpoint
```

No silent expansion of business surface beyond the plan happened.

---

## 6. Risks of staying in read-only too long

```text
1. Without UI, business stakeholders cannot validate the “consultant/PM/analyst feel”.
2. Without EvalSet/EvalRun object the Level 2–4 evolution path stays abstract.
3. Without replay/canary/auto-rollback, the “safety valves” (plan 8) remain documentation only.
4. Without notification adapter, approval cards have no real escalation path.
```

These are not architectural drifts; they are pacing risks.

---

## 7. Recommended next moves (no plan rewrite needed)

If continuing read-only:

```text
- Tag BusinessRunRecord with metadata.source explicitly (foundation vs manual/demo).
- Add a thin BusinessInsight projection toggle: business-summary vs foundation-projection.
- Draft a design doc for EvalRun read-only projection (no new business object yet).
- Draft a design doc for replay/canary/auto-rollback minimal viable boundary.
- Draft BusinessNotificationAdapter contract before any mutation endpoint.
```

If preparing the next phase:

```text
1. Write design docs first; do not implement mutation endpoints inline.
2. Each mutation endpoint must follow BUSINESS_PORTAL_FOUNDATION_ADAPTERS.md PR checklist.
3. First mutation candidate is a governed compile/apply for Team Blueprint, not generation.
```

---

## 8. Conclusion

```text
The iteration series has stayed inside the plan’s constraints.
No second platform was built.
No business object was added.
No generation/mutation surface was opened.
Read-only product integration is the first time the platform exposes foundation truth, and it does so exclusively through the documented facade boundary.
```

The remaining gaps (EvalSet, replay/canary, notification adapter) are tracked here so they cannot be quietly skipped later.

---

## 9. Foundation Gap Logged: EvalResult listing

Date: 2026-06-17

While trying to add a read-only `/api/v1/business/foundation/eval-runs/preview` endpoint, we confirmed that Hermes foundation does not currently expose a per-tenant `EvalResult` store or query API.

Consequences:

```text
A by-reference EvalRun preview endpoint cannot honor the read-only/by-reference
guarantee in BUSINESS_PORTAL_FOUNDATION_READONLY_ENDPOINTS.md section 1.
The BusinessEvalRunProjectionAdapter is implemented and tested, but the dashboard
preview endpoint is intentionally deferred until foundation provides EvalResult listing.
```

This is the first explicit foundation-side gap surfaced by the convergence work. It does not change the alignment status, but it is logged so it cannot be silently skipped.

Recommended next steps:

```text
- Treat foundation EvalResultStore/list API as a prerequisite for any eval-runs HTTP endpoint.
- Continue read-only endpoint coverage where foundation already has listing APIs (traces, intent runs, evolution summary, prompt assets, team blueprints, scenarios, evolution proposals, approvals).
- Update BUSINESS_PORTAL_FOUNDATION_EVAL_RUN_DESIGN.md section 6.1 to reflect the gap.
```
