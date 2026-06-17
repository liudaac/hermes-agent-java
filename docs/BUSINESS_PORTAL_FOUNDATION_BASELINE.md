# Business Portal Foundation Baseline

Date: 2026-06-17

This document freezes the current Business Portal foundation-convergence baseline after the adapter-first iteration series.

It is a consolidation document, not a new feature spec.

---

## 1. Baseline Statement

Business Portal is now expected to integrate with Hermes foundation through explicit adapters and a single facade boundary.

```text
Business Portal presents, frames, stores projections and collects human review.
Hermes foundation owns tenant, tools, prompts, runtime teams, intent execution, traces, approvals, evolution and delegation truth.
```

Current default integration boundary:

```text
com.nousresearch.hermes.business.foundation.BusinessPortalFoundationFacade
```

Current factory/wiring boundary:

```text
com.nousresearch.hermes.business.foundation.BusinessPortalAdapterRegistry
```

Current read-only baseline check:

```text
BusinessPortalFoundationFacade.diagnostics()
```

Future product code should not manually stitch low-level foundation services together if a facade method exists.

---

## 2. Implemented Adapter Baseline

| Area | Adapter / Boundary | Status | Source of Truth |
|---|---|---|---|
| Prompt context | `PromptAssetResolver`, `PromptContext`, `FoundationPromptAssetBridge` | Implemented | PromptAssetService + Memory/Skill/OrgKnowledge foundation |
| Capability grounding | `FoundationCapabilityValidator`, `FoundationCapabilityValidationReport` | Implemented | Workspace/Tenant + ToolRegistry + tenant policy + prompt bridge |
| Team topology | `TeamBlueprintCompiler`, `TeamBlueprintCompileResult` | Implemented | `TeamManager`, `Team`, `AgentRole`, `TenantContext` |
| Scenario intent | `ScenarioIntentAdapter`, `ScenarioIntentRequest` | Implemented | `IntentOrchestrator` |
| Run story | `BusinessRunProjectionAdapter` | Implemented | `IntentRun`, `AgentTrace` |
| Approval card | `BusinessApprovalAdapter` | Implemented | `ApprovalSystem`, `ApprovalRequest`, `ApprovalResult` |
| Insight projection | `BusinessInsightProjectionAdapter` | Implemented | `AgentTrace`, `AgentEvaluation`, `SelfEvolutionEngine` summary |
| Evolution governance | `EvolutionProposalAdapter` | Implemented | `SelfEvolutionEngine`, `FailureCase`, `ApprovalSystem`, `DelegatedTaskStore` |
| Unified boundary | `BusinessPortalFoundationFacade`, `BusinessPortalAdapterRegistry` | Implemented | Adapter composition only |
| Diagnostics | `BusinessPortalFoundationDiagnostics` | Implemented | Read-only facade/adapter baseline |
| Architecture guard | `BusinessPortalFoundationArchitectureTest` | Implemented | Source-level boundary test |

---

## 3. Facade Surface

The facade currently exposes:

```text
resolvePromptContext(...)
validateTeamBlueprint(...)
compileTeamBlueprint(...)
buildScenarioIntentRequest(...)
planScenarioIntent(...)
executeScenarioIntent(...)
projectIntentRun(...)
projectFoundationInsights(...)
projectTraceInsights(...)
projectEvalInsights(...)
projectEvolutionInsights(...)
recordProposalLearning(...)
projectProposalApproval(...)
createProposalReviewTask(...)
diagnostics()
```

This is intentionally a thin adapter composition surface.

It is not:

```text
a product API
a dashboard API
a generation service
a runtime execution engine
a persistence repository
```

---

## 4. Tests Guarding the Baseline

Key tests added during convergence:

```text
FoundationCapabilityValidatorTest
TeamBlueprintCompilerTest
ScenarioIntentAdapterTest
BusinessRunProjectionAdapterTest
BusinessApprovalAdapterTest
EvolutionProposalAdapterTest
PromptAssetResolverTest
BusinessPortalAdapterChainSmokeTest
BusinessPortalFoundationFacadeTest
BusinessPortalFoundationArchitectureTest
BusinessInsightProjectionAdapterTest
BusinessPortalFoundationDiagnosticsTest
```

Important test design rule:

```text
Prefer artifact-based assertions over global-count assertions.
```

Reason:

```text
Tenant foundation may load persisted test-home state such as memories, intent runs or delegated tasks.
Tests should verify the artifact produced by the current test instead of assuming foundation stores are empty.
```

---

## 5. Current Non-goals

Still intentionally out of scope:

```text
No generation API
No Business Portal UI expansion
No new business domain objects
No direct channel notification delivery
No autonomous evolution proposal apply
No automatic runtime team execution from blueprint compile
No second approval engine
No second workflow engine
No second trace store
No second knowledge store
```

---

## 6. Future Feature Entry Rules

## 6.1 Team generation / editing

Must enter through:

```text
BusinessPortalFoundationFacade.resolvePromptContext(...)
BusinessPortalFoundationFacade.validateTeamBlueprint(...)
BusinessPortalFoundationFacade.compileTeamBlueprint(...)
```

Required behavior:

```text
Generated tool names must be grounded in ToolRegistry.
Prompt refs must resolve through PromptAssetResolver.
Team topology mutation must go through TeamBlueprintCompiler.
High-risk changes must produce approval/delegated-task artifacts.
```

Forbidden behavior:

```text
Direct TeamManager mutation from route handlers.
Trusting allowedTools strings without validation.
Creating TenantAwareAIAgent sessions from blueprint compile.
Mutating tenant policy from generated JSON without approval.
```

## 6.2 Scenario execution

Must enter through:

```text
BusinessPortalFoundationFacade.buildScenarioIntentRequest(...)
BusinessPortalFoundationFacade.planScenarioIntent(...)
BusinessPortalFoundationFacade.executeScenarioIntent(...)
```

Forbidden behavior:

```text
ScenarioService implementing task decomposition.
ScenarioService selecting teammates.
ScenarioService executing workflow steps.
ScenarioService becoming a trace store.
```

## 6.3 Run / trace display

Must preserve foundation references:

```text
technicalTraceRef = intent://<runId> or trace://<traceId>
metadata.source = foundation:intent-run / foundation:agent-trace / equivalent
```

Manual/demo run records must be clearly identified as non-foundation truth.

## 6.4 Approval UX

Approval cards must mirror foundation approval state.

Allowed:

```text
ApprovalRequest -> BusinessApprovalAdapter.fromApprovalRequest(...)
ApprovalResult -> card resolution projection
```

Forbidden:

```text
Approving foundation requests only by changing BusinessApprovalRecord status.
Creating local approval semantics that hide ApprovalRequest / ApprovalResult.
```

## 6.5 Insight and evolution

Foundation-backed insights should use:

```text
BusinessPortalFoundationFacade.projectFoundationInsights(...)
BusinessPortalFoundationFacade.projectTraceInsights(...)
BusinessPortalFoundationFacade.projectEvalInsights(...)
BusinessPortalFoundationFacade.projectEvolutionInsights(...)
```

Evolution proposals should use:

```text
BusinessPortalFoundationFacade.recordProposalLearning(...)
BusinessPortalFoundationFacade.projectProposalApproval(...)
BusinessPortalFoundationFacade.createProposalReviewTask(...)
```

Forbidden:

```text
Applying proposal runtime mutations directly from EvolutionProposalService.
Skipping SelfEvolutionEngine for learning records.
Executing delegated tasks automatically.
```

---

## 7. Architecture Guard Status

Current executable guard:

```text
BusinessPortalFoundationArchitectureTest
```

Current scope:

```text
com.nousresearch.hermes.business.*
```

Current rule:

```text
Ordinary Business Portal classes must not directly import low-level foundation packages.
Only business.foundation and explicit adapter classes may bridge to foundation packages.
```

Current thin `business.foundation` package allowlist:

```text
BusinessPortalFoundationFacade
BusinessPortalAdapterRegistry
BusinessPortalFoundationDiagnostics
```

Current explicit adapter bridges include:

```text
BusinessApprovalAdapter
BusinessRunProjectionAdapter
BusinessInsightProjectionAdapter
```

The guard intentionally does not police legacy dashboard/org handlers yet.

---

## 8. Documentation Map

Related documents:

```text
docs/BUSINESS_PORTAL_FOUNDATION_AUDIT.md
docs/BUSINESS_PORTAL_FOUNDATION_CAPABILITY_INVENTORY.md
docs/BUSINESS_PORTAL_FOUNDATION_ADAPTERS.md
docs/BUSINESS_AGENT_TEAM_PLATFORM_PLAN.md
docs/BUSINESS_PORTAL_FOUNDATION_BASELINE.md
```

How to read them:

```text
AUDIT = original overlap/risk analysis
CAPABILITY_INVENTORY = code-level source-of-truth inventory + iteration log
ADAPTERS = development contract and guardrails
PLATFORM_PLAN = product/platform plan with section 21 iteration trail
BASELINE = current frozen summary of the adapter-first foundation state
```

---

## 9. Recommended Next Phase

If continuing without expanding product surface:

```text
1. Keep hardening adapter tests.
2. Add diagnostics assertions to smoke tests if useful.
3. Consider extending architecture guard to selected dashboard integration classes only after a separate audit.
```

If starting product integration later, begin read-only:

```text
1. Expose diagnostics/reporting through an existing safe admin path.
2. Show validation reports and projection previews before any mutation endpoint.
3. Defer generation and mutation endpoints until the facade path is fully exercised.
```

Do not start the next phase with:

```text
POST /team-generation
auto-apply evolution proposal
runtime team execution from blueprint compile
new Business Portal UI tabs that bypass the facade
```

---

## 10. Baseline Acceptance Criteria

The current baseline is acceptable when:

```text
mvn -q -DskipTests compile
```

passes, and the following tests pass:

```text
BusinessPortalFoundationArchitectureTest
BusinessPortalFoundationFacadeTest
BusinessPortalFoundationDiagnosticsTest
BusinessPortalAdapterChainSmokeTest
FoundationCapabilityValidatorTest
TeamBlueprintCompilerTest
ScenarioIntentAdapterTest
BusinessRunProjectionAdapterTest
BusinessApprovalAdapterTest
BusinessInsightProjectionAdapterTest
EvolutionProposalAdapterTest
PromptAssetResolverTest
```

The architecture rule remains:

```text
Business Portal presents and governs.
Hermes foundation owns truth and execution.
```

---

## 11. Next Phase Read-only Integration Started

Date: 2026-06-17

The next phase starts with a read-only diagnostics endpoint:

```text
GET /api/v1/business/foundation/diagnostics
```

Response shape:

```text
{
  "ok": true,
  "diagnostics": BusinessPortalFoundationFacade.diagnostics().toMap()
}
```

Boundary:

```text
No request body
No mutation
No generation
No runtime execution
No UI tab
No business object creation
```

Implementation note:

```text
DashboardServer wires BusinessPortalFoundationFacade through BusinessPortalAdapterRegistry.
The endpoint only returns the read-only diagnostics projection.
```

Testing note:

```text
DashboardBusinessFoundationDiagnosticsRouteTest verifies the route returns the facade diagnostics payload.
```

During testing, the existing Dashboard auth middleware behavior was observed: in loopback tests, a 401 response set in middleware can be overwritten by downstream handlers because middleware does not abort handler execution after setting 401. This was not changed in this iteration to keep the scope limited to read-only diagnostics integration.

---

## 12. Dashboard Auth Middleware Safety Fix

Date: 2026-06-17

After adding the read-only diagnostics endpoint, the Dashboard auth middleware was tightened:

```text
DashboardServer.registerMiddleware()
```

Fix:

```text
Invalid Host header -> set 400 and ctx.skipRemainingHandlers()
Unauthorized API request -> set 401 and ctx.skipRemainingHandlers()
```

Reason:

```text
Previously, middleware could set 400/401 but downstream handlers could still run and overwrite the response.
```

Validation:

```text
DashboardBusinessFoundationDiagnosticsRouteTest now verifies unauthenticated diagnostics requests return 401.
DashboardTenantRoutesTest continues to pass for authorized tenant APIs.
```

This is a safety fix, not a Business Portal feature expansion.

---

## 13. Read-only Team Blueprint Validation Preview

Date: 2026-06-17

A read-only validation preview endpoint is now available:

```text
POST /api/v1/business/foundation/team-blueprints/validate
```

Request body:

```json
{
  "workspaceId": "customer-service",
  "teamId": "after-sales"
}
```

Response shape:

```text
{
  "ok": true,
  "workspaceId": "...",
  "teamId": "...",
  "validation": FoundationCapabilityValidationReport.toMap()
}
```

Boundary:

```text
Reads an existing TeamBlueprintRecord by workspaceId/teamId.
Validates through BusinessPortalFoundationFacade.validateTeamBlueprint(...).
Does not compile.
Does not mutate.
Does not generate.
Does not create business objects.
Does not change UI.
```

Validation preview is intentionally by reference in this first read-only step. It validates stored team blueprints instead of accepting arbitrary generated payloads.

---

## 14. Read-only Prompt Context Preview

Date: 2026-06-17

A read-only prompt context preview endpoint is now available:

```text
POST /api/v1/business/foundation/prompt-context/preview
```

Request body:

```json
{
  "workspaceId": "customer-service",
  "promptAssetRefs": ["prompt://base", "prompt://policy#v2"],
  "taskContext": "refund ticket",
  "includeFoundationContext": false
}
```

Response shape:

```text
{
  "ok": true,
  "workspaceId": "...",
  "promptContext": PromptContext.toMap(),
  "rendered": PromptContext.render()
}
```

Boundary:

```text
Resolves prompt refs through BusinessPortalFoundationFacade.resolvePromptContext(...).
Does not write PromptAsset.
Does not mutate Memory / Skill / Org Knowledge.
Does not generate content.
Does not create business objects.
Does not change UI.
```

`includeFoundationContext=true` may include read-only foundation context segments from memory, skills and organizational knowledge.

---

## 15. Read-only Scenario Intent Plan Preview

Date: 2026-06-17

A read-only scenario intent plan preview endpoint is now available:

```text
POST /api/v1/business/foundation/scenarios/plan
```

Request body:

```json
{
  "workspaceId": "customer-service",
  "scenarioId": "after-sales-ticket",
  "userInput": "refund order"
}
```

Response shape:

```text
{
  "ok": true,
  "workspaceId": "...",
  "scenarioId": "...",
  "intentRequest": ScenarioIntentRequest.toMap(),
  "plan": IntentPlan.toMap()
}
```

Boundary:

```text
Reads an existing ScenarioRecord by workspaceId/scenarioId.
Plans through BusinessPortalFoundationFacade.planScenarioIntent(...).
Does not execute.
Does not create IntentRun.
Does not create BusinessRunRecord.
Does not mutate ScenarioRecord.
Does not generate content.
Does not change UI.
```

This endpoint is intentionally by-reference. It previews how an existing scenario would map into IntentOrchestrator planning without starting execution.

---

## 16. Read-only IntentRun Projection Preview

Date: 2026-06-17

A read-only run projection preview endpoint is now available:

```text
POST /api/v1/business/foundation/runs/project
```

Request body:

```json
{
  "workspaceId": "customer-service",
  "intentRunId": "run_1",
  "scenarioId": "after-sales-ticket",
  "scenarioName": "售后工单处理"
}
```

Response shape:

```text
{
  "ok": true,
  "workspaceId": "...",
  "intentRunId": "...",
  "projection": BusinessRunRecord projection
}
```

Boundary:

```text
Reads an existing IntentRun from TenantContext.getIntentOrchestrator().getRun(...).
Projects through BusinessPortalFoundationFacade.projectIntentRun(...).
Does not execute.
Does not create IntentRun.
Does not persist BusinessRunRecord.
Does not mutate foundation or business stores.
Does not generate content.
Does not change UI.
```

This endpoint is intentionally by-reference. It does not accept arbitrary run payloads and cannot be used to create fake business run truth.
