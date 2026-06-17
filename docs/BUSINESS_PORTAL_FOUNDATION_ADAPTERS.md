# Business Portal Foundation Adapters

Date: 2026-06-17

This document is the development contract for Business Portal code that touches Hermes foundation capabilities.

It follows:

```text
docs/BUSINESS_PORTAL_FOUNDATION_AUDIT.md
docs/BUSINESS_PORTAL_FOUNDATION_CAPABILITY_INVENTORY.md
docs/BUSINESS_AGENT_TEAM_PLATFORM_PLAN.md
```

---

## 1. Core Rule

Business Portal code must not become a second Hermes platform.

```text
Business Portal presents, frames, stores projections and collects human review.
Hermes foundation owns tenant, tools, prompts, runtime teams, intent execution, traces, approvals, evolution and delegation truth.
```

For future API/UI/generation work, the default integration boundary is:

```text
com.nousresearch.hermes.business.foundation.BusinessPortalFoundationFacade
```

The factory/wiring boundary is:

```text
com.nousresearch.hermes.business.foundation.BusinessPortalAdapterRegistry
```

Product code should not manually stitch foundation services together when a facade method exists.

---

## 2. Current Adapter Map

| Adapter | Package | Owns | Does Not Own |
|---|---|---|---|
| `PromptAssetResolver` | `prompt` | `prompt://` ref resolution and prompt context projection | Memory/skill/org knowledge truth |
| `FoundationCapabilityValidator` | `blueprint` | blueprint grounding report | tool registration, tenant policy mutation, execution permission grant |
| `TeamBlueprintCompiler` | `blueprint` | design-time blueprint -> `Team` / `AgentRole` topology | agent runtime/session execution |
| `ScenarioIntentAdapter` | `scenario` | `ScenarioRecord` -> `IntentOrchestrator` request/plan/execute | task decomposition/workflow logic |
| `BusinessRunProjectionAdapter` | `business.run` | `IntentRun` / `AgentTrace` -> `BusinessRunRecord` projection | trace persistence/execution truth |
| `BusinessApprovalAdapter` | `business.approval` | `ApprovalRequest` / `ApprovalResult` -> approval card projection | approval engine transition |
| `EvolutionProposalAdapter` | `evolution` | proposal -> failure learning / approval card / delegated review task | runtime apply/evolution engine |
| `BusinessPortalFoundationFacade` | `business.foundation` | thin adapter composition boundary | product features, UI, routes, generation |

---

## 3. Source-of-truth Contract

| Business Portal artifact | Source of truth | Allowed Business Portal role |
|---|---|---|
| `WorkspaceRecord` | `TenantManager` / `TenantContext` | business metadata façade |
| `ScenarioRecord` | `IntentOrchestrator` / workflow foundation | business framing |
| `PromptAssetRecord` | Prompt asset service + Memory/Skill/Org Knowledge foundations | business-managed prompt/SOP asset |
| `TeamBlueprintRecord` | `TeamManager` / `Team` / `AgentRole` | design-time versioned spec |
| `AgentBlueprintRecord` | `AgentRole` + tool/skill registry | design-time role card |
| `BusinessRunRecord` | `IntentRun` / `AgentTrace` / `AgentObservability` | business-readable projection |
| `BusinessApprovalRecord` | `ApprovalSystem` / `ApprovalRequest` / `ApprovalResult` | approval card projection |
| `EvolutionProposalRecord` | `SelfEvolutionEngine` / `FailureCase` / `DelegatedTaskStore` / versioning | review/proposal surface |

---

## 4. Mandatory Flow for Future Features

## 4.1 Team generation or team editing

Before any team generation/editing feature can create or publish a business team:

```text
1. Resolve prompt refs through BusinessPortalFoundationFacade.resolvePromptContext(...)
2. Validate capability grounding through validateTeamBlueprint(...)
3. Refuse or surface warnings for validation ERROR/WARNING findings
4. Compile only through compileTeamBlueprint(...)
5. High-risk tool/prompt/topology changes must create approval/delegated review artifacts
```

Do not:

```text
write agent roles directly from route handlers
trust allowedTools strings without FoundationCapabilityValidator
invent tool names outside ToolRegistry
mutate tenant tool policy from generated JSON
create TenantAwareAIAgent sessions from TeamBlueprintCompiler
```

## 4.2 Scenario execution

Scenario execution features must use:

```text
BusinessPortalFoundationFacade.buildScenarioIntentRequest(...)
BusinessPortalFoundationFacade.planScenarioIntent(...)
BusinessPortalFoundationFacade.executeScenarioIntent(...)
```

Do not implement in `ScenarioService`:

```text
task decomposition
teammate selection
workflow step execution
retry/reroute logic
trace persistence
```

Those are foundation responsibilities.

## 4.3 Run display / audit

Run dashboards and audit screens must treat `BusinessRunRecord` as a projection.

Required reference:

```text
technicalTraceRef = intent://<runId> or trace://<traceId>
metadata.source = foundation:intent-run / foundation:agent-trace / equivalent
```

Do not use manually created business run records as execution truth unless explicitly marked as demo/manual/smoke.

## 4.4 Approval cards

Approval cards must mirror foundation approval state.

Allowed:

```text
ApprovalRequest -> BusinessApprovalAdapter.fromApprovalRequest(...)
ApprovalResult -> BusinessApprovalAdapter.withResult(...) or resolvePersisted(...)
```

Do not:

```text
create a second approval engine
hide foundation approval result behind local status
approve/reject foundation requests only by updating BusinessApprovalRecord
```

## 4.5 Evolution proposals

Evolution proposal features must use:

```text
EvolutionProposalAdapter.toFailureCase(...)
EvolutionProposalAdapter.recordFailureLearning(...)
EvolutionProposalAdapter.toBusinessApprovalCard(...)
EvolutionProposalAdapter.createDelegatedReviewTask(...)
```

Do not:

```text
apply runtime mutations directly from EvolutionProposalService
publish team blueprint versions without approval/versioning boundary
execute delegated tasks automatically
bypass SelfEvolutionEngine for learning records
```

---

## 5. Generation Guardrails

When generation is eventually implemented, generation output is not executable truth.

Allowed generation output:

```text
draft Scenario framing
draft TeamBlueprint version
draft PromptAsset content
draft EvolutionProposal
validation report
warnings and missing information questions
```

Forbidden generation output:

```text
executable tools not in ToolRegistry
tenant policy mutation without approval
runtime agent sessions
direct ApprovalSystem bypass
direct TeamManager mutation without TeamBlueprintCompiler
BusinessRunRecord pretending to be real execution without IntentRun / AgentTrace
```

Minimum generation pipeline:

```text
LLM proposes
Schema validates
PromptAssetResolver resolves context
FoundationCapabilityValidator grounds capabilities
TeamBlueprintCompiler compiles topology when allowed
ApprovalSystem / DelegatedTaskStore governs high-risk changes
Business Portal presents projection
Human approves high-risk actions
```

---

## 6. Review Checklist

Before merging future Business Portal feature code, answer yes/no:

```text
Does this feature call BusinessPortalFoundationFacade when touching foundation behavior?
Does every business run projection preserve technicalTraceRef?
Does every approval card map back to ApprovalRequest / ApprovalResult?
Are generated/declared tools checked against ToolRegistry and tenant policy?
Are prompt refs resolved through PromptAssetResolver rather than string-concatenated?
Does team topology mutation go through TeamBlueprintCompiler?
Does scenario execution go through ScenarioIntentAdapter / IntentOrchestrator?
Does evolution learning go through SelfEvolutionEngine / FailureCase?
Do high-risk changes produce approval/delegated-task artifacts?
Is the feature avoiding a second runtime, approval engine, trace store, or knowledge store?
```

If any answer is no, the feature likely bypasses the foundation boundary.

---

## 7. Current Non-goals

Still out of scope at this stage:

```text
No generation API
No new Business Portal UI tabs
No new business objects
No direct channel notification delivery
No autonomous proposal apply
No automatic runtime team execution from blueprint compile
```

---

## 8. Recommended Next Engineering Step

If continuing without expanding product surface, the next safe step is:

```text
Add lightweight architecture tests / static checks that prevent Business Portal modules from directly wiring low-level foundation services when a facade method exists.
```

If moving toward product integration later, the first product step should be read-only:

```text
Expose a read-only foundation diagnostics view/report through the facade.
```

Do not start with generation or mutation endpoints.

---

## 9. Architecture Test Guard

Date: 2026-06-17

A lightweight architecture test now protects the Business Portal foundation boundary:

```text
src/test/java/com/nousresearch/hermes/business/foundation/BusinessPortalFoundationArchitectureTest.java
```

Current scope:

```text
com.nousresearch.hermes.business.*
```

It intentionally does not police legacy dashboard/org handlers yet.

The test checks:

```text
Ordinary Business Portal classes do not import low-level foundation packages directly.
Only business.foundation and explicit adapter classes may bridge to foundation packages.
business.foundation remains a thin package containing only BusinessPortalFoundationFacade and BusinessPortalAdapterRegistry.
```

Explicitly allowed adapter classes today:

```text
BusinessApprovalAdapter
BusinessRunProjectionAdapter
BusinessPortalFoundationFacade
BusinessPortalAdapterRegistry
```

Rationale:

```text
The architecture contract is now executable.
Future Business Portal product code should go through BusinessPortalFoundationFacade or an explicit adapter, not directly to low-level foundation APIs.
```

If a future feature needs a new adapter exception, add it deliberately and update this document with the reason.

---

## 10. BusinessInsightProjectionAdapter

Date: 2026-06-17

Foundation-backed insight projection adapter:

```text
com.nousresearch.hermes.business.insight.BusinessInsightProjectionAdapter
```

Allowed inputs:

```text
AgentTrace
AgentEvaluation.EvalResult
SelfEvolutionEngine.getSummary() map
```

Allowed outputs:

```text
BusinessInsightRecord
BusinessInsightSummary
```

Ownership:

```text
Owns projection from foundation signals to business-readable insights.
Does not own trace/eval/evolution analytics truth.
Does not create evolution proposals automatically.
Does not mutate foundation state.
```

Architecture test status:

```text
Explicit bridge allowed in BusinessPortalFoundationArchitectureTest.
```

### 10.1 Facade Integration

`BusinessInsightProjectionAdapter` is now available through:

```text
BusinessPortalFoundationFacade.projectFoundationInsights(...)
BusinessPortalFoundationFacade.projectTraceInsights(...)
BusinessPortalFoundationFacade.projectEvalInsights(...)
BusinessPortalFoundationFacade.projectEvolutionInsights(...)
```

Future product code should use these facade methods instead of manually constructing the adapter.
