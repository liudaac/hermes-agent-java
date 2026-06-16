# Business Portal Foundation Audit

Date: 2026-06-16

This document audits overlap between Business Portal additions and existing Hermes foundation capabilities.

It extends `BUSINESS_PORTAL_FOUNDATION_MAPPING.md` from a mapping document into an architectural audit.

## 1. Audit Goal

Business Portal should not become a parallel agent platform.

The audit goal is to identify:

```text
Existing foundation classes / packages
Business Portal additions
Responsibility overlap
Source of truth
Façade / projection / adapter boundaries
Recommended next action
```

## 2. High-level Finding

Business Portal has created useful business-facing objects, but several of them currently own logic that likely belongs in Hermes foundation layers.

The highest-risk overlaps are:

```text
Approval semantics
Evolution proposal / org evolution
Run records vs execution traces
Team Blueprint vs Agent Runtime / Collaboration
Prompt Asset vs Prompt / Memory / Skill assets
Scenario vs Intent / Workflow
```

This does not mean the Business Portal objects are wrong.

It means they should be treated as:

```text
business-facing façade
projection
adapter
workflow entry point
```

not as independent platform cores.

## 3. Audit Matrix

| Area | Existing Foundation | Business Portal Additions | Overlap Risk | Source of Truth | Business Portal Role | Action |
|---|---|---|---|---|---|---|
| Tenant / Workspace | TenantManager, TenantContext, Tenant persistence/quota/security | WorkspaceRecord, WorkspaceService | Low | TenantManager / TenantContext | Business façade | Keep current façade; avoid adding isolation logic |
| Scenario / Intent | intent, orchestrator, workflow concepts | ScenarioRecord, ScenarioService | Medium | Intent/Orchestration for interpretation/execution | Business framing object | Add adapter from Scenario to Intent/Workflow |
| Team / Agent Runtime | agent runtime, TenantAwareAIAgent, collaboration, bus, org | TeamBlueprintRecord, AgentBlueprintRecord | High | Agent runtime / collaboration / org model | Design-time business blueprint | Add TeamBlueprintCompiler / RuntimeAdapter |
| Prompt / Memory / Skill | memory, skills, tool/prompt config conventions | PromptAssetRecord, PromptAssetVersion | Medium/High | Prompt stack / skill/memory asset conventions | Business-managed prompt asset façade | Align storage/refs with existing prompt/memory/skill mechanisms |
| Tools / Skills | tools package, tenant-aware tool dispatch | allowedTools strings in AgentBlueprintRecord | High | Tool/Skill registry and dispatcher | Selected capability view | Validate generated tools against registry |
| Run / Trace | IntentRun, AgentTrace, runtime/tool traces | BusinessRunRecord, BusinessRunService | High | Execution trace / IntentRun | Business-readable projection | Build AgentTrace → BusinessRun adapter |
| Approval | ApprovalSystem, ApprovalRequest, ApprovalResult, browser/delegated approvals | BusinessApprovalRecord, BusinessApprovalService | High | ApprovalSystem / delegated task | Mobile/business approval card | Add BusinessApprovalAdapter; reduce duplicated semantics |
| Insights | trace analytics/eval/org learning | BusinessInsightService | Medium | Trace/eval analytics | Business insight summary | Feed from real traces/evals, not only file records |
| Evolution | org/evolution/delegated task concepts | EvolutionProposalRecord, EvolutionProposalService | High | org/evolution + team versioning | Business-facing proposal state machine | Align apply/approval with existing evolution mechanisms |
| Gateway / Notification | gateway, platform adapters | future approval/proposal notifications | Medium | Gateway adapters | Delivery trigger façade | Reuse gateway, do not add direct channel delivery |

## 4. Detailed Audits

## 4.1 Tenant / Workspace

### Existing foundation

Relevant packages:

```text
com.nousresearch.hermes.tenant.core
com.nousresearch.hermes.tenant.persistence
com.nousresearch.hermes.tenant.quota
com.nousresearch.hermes.tenant.security
com.nousresearch.hermes.tenant.tools
```

Representative foundation concepts:

```text
TenantManager
TenantContext
TenantProvisioningRequest
TenantQuota
Tenant-aware tool dispatch
```

### Business Portal additions

```text
WorkspaceRecord
FileWorkspaceRepository
WorkspaceService
WorkspaceDashboardIntegration
```

### Overlap assessment

Current overlap risk is low.

Workspace currently acts as a business façade over Tenant and auto-creates the underlying tenant.

### Source of truth

```text
TenantManager / TenantContext
```

### Business Portal role

```text
Business-facing workspace façade
```

### Recommendation

Keep Workspace thin.

Do not add:

```text
quota logic
security policy
tenant lifecycle semantics
resource isolation rules
```

inside WorkspaceService.

## 4.2 Scenario / Intent / Workflow

### Existing foundation

Relevant packages:

```text
com.nousresearch.hermes.intent
com.nousresearch.hermes.orchestrator
```

### Business Portal additions

```text
ScenarioRecord
ScenarioService
ScenarioDashboardIntegration
```

### Overlap assessment

Risk is medium.

Scenario is currently a static business object. The project goal, however, requires scenario understanding and execution planning.

That likely overlaps with Intent / Orchestration.

### Source of truth

```text
Intent / Orchestration should own interpretation and execution planning.
Scenario should own business framing and metadata.
```

### Business Portal role

```text
Business scenario framing object
```

### Recommendation

Add an adapter layer:

```text
ScenarioIntentAdapter
```

Responsibilities:

```text
ScenarioRecord → intent seed
Scenario description → structured intent context
Scenario risk policy → approval constraints
Scenario knowledge hints → retrieval/tool constraints
```

Do not let ScenarioService become a workflow engine.

## 4.3 Team Blueprint / Agent Runtime / Collaboration

### Existing foundation

Relevant packages:

```text
com.nousresearch.hermes.agent
com.nousresearch.hermes.collaboration
com.nousresearch.hermes.org
```

Representative concepts:

```text
TenantAwareAIAgent
Agent runtime
TenantBus / collaboration mechanisms
Org/role abstractions
```

### Business Portal additions

```text
TeamBlueprintRecord
TeamBlueprintVersion
AgentBlueprintRecord
TeamBlueprintService
```

### Overlap assessment

Risk is high.

Team Blueprint currently describes a team, but does not yet compile into executable Hermes agent runtime / collaboration topology.

If it remains independent, it becomes decorative team JSON.

### Source of truth

```text
Agent runtime + collaboration/org model should own executable team behavior.
TeamBlueprint should own business design-time representation.
```

### Business Portal role

```text
Design-time business blueprint
```

### Recommendation

Add:

```text
TeamBlueprintCompiler
TeamRuntimeAdapter
```

Responsibilities:

```text
Validate agent roles against runtime capabilities
Map AgentBlueprintRecord to runtime agent config
Map allowedTools to actual Tool/Skill registry
Map approvalRules to ApprovalSystem policies
Create or update collaboration topology
```

Avoid duplicating runtime execution inside TeamBlueprintService.

## 4.4 Prompt Asset / Memory / Skill / Prompt Stack

### Existing foundation

Relevant packages/concepts:

```text
com.nousresearch.hermes.memory
skills / tools conventions
agent prompt/config conventions
workspace files
```

### Business Portal additions

```text
PromptAssetRecord
PromptAssetVersion
PromptAssetService
PromptAssetDashboardIntegration
```

### Overlap assessment

Risk is medium to high.

Prompt Asset is useful, but may overlap with memory, skill assets, agent config prompts, and workspace prompt files.

### Source of truth

Not yet fully identified.

Candidate source layers:

```text
Prompt stack conventions
Skill asset metadata
Memory / workspace context
Agent config prompts
```

### Business Portal role

```text
Business-managed prompt asset façade
```

### Recommendation

Before expanding Prompt Asset UI further, identify existing prompt/memory/skill asset conventions.

Next adapter candidates:

```text
PromptAssetResolver
PromptAssetRefValidator
PromptAssetToAgentPromptAdapter
```

Prompt refs should remain stable:

```text
prompt://assetId
prompt://assetId#vN
```

but resolution should eventually draw from the real prompt stack, not only file-backed PromptAssetRecord.

## 4.5 Tool / Skill Registry

### Existing foundation

Relevant packages:

```text
com.nousresearch.hermes.tools
```

Known concepts from package names and tests:

```text
Tenant-aware tool dispatch
Tool performance tracking
Tool execution policies
```

### Business Portal additions

```text
AgentBlueprintRecord.allowedTools
Generated agent role cards
```

### Overlap assessment

Risk is high.

LLM-generated team roles may invent tools that do not exist.

### Source of truth

```text
Tool/Skill registry and TenantAwareToolDispatcher
```

### Business Portal role

```text
Capability selection and business explanation
```

### Recommendation

Business team generation must validate tools against actual registry.

Generation result should include warnings:

```text
requested tool unavailable
tool exists but tenant lacks permission
tool exists but quota/policy blocks usage
```

Do not store arbitrary allowedTools as if they are executable.

## 4.6 Business Run / AgentTrace / IntentRun

### Existing foundation

Likely foundation concepts:

```text
IntentRun
AgentTrace
Tool call trace
Runtime execution logs
```

### Business Portal additions

```text
BusinessRunRecord
BusinessRunStep
BusinessRunService
BusinessRunDashboardIntegration
```

### Overlap assessment

Risk is high.

BusinessRun currently can be manually created. That is useful for demos and smoke tests, but not sufficient for real runtime.

### Source of truth

```text
AgentTrace / IntentRun / runtime trace
```

### Business Portal role

```text
Business-readable projection of execution trace
```

### Recommendation

Add:

```text
BusinessRunProjectionAdapter
```

Responsibilities:

```text
AgentTrace → taskTitle/resultSummary/conclusionReason
Tool calls → business steps
Errors → failure reason
Risk events → approval links
Trace ID → technicalTraceRef
```

Manual BusinessRun creation should remain demo/manual mode, not the primary run source.

## 4.7 Approval / ApprovalSystem / Delegated Task

### Existing foundation

Relevant package:

```text
com.nousresearch.hermes.approval
```

Representative classes:

```text
ApprovalSystem
ApprovalRequest
ApprovalResult
```

Other related concepts:

```text
Browser approval queue
Delegated task verification
Gateway notification/delivery
```

### Business Portal additions

```text
BusinessApprovalRecord
BusinessApprovalService
BusinessApprovalDashboardIntegration
```

### Overlap assessment

Risk is high.

BusinessApprovalService currently owns statuses and transitions:

```text
PENDING
APPROVED
REJECTED
INFO_REQUESTED
```

This may overlap with ApprovalSystem semantics.

### Source of truth

```text
ApprovalSystem / delegated task should own approval semantics.
```

### Business Portal role

```text
Business/mobile approval card and audit projection
```

### Recommendation

Add:

```text
BusinessApprovalAdapter
```

Responsibilities:

```text
ApprovalSystem request → BusinessApprovalRecord card
Business card action → ApprovalSystem decision
Approval result → Business record projection
Gateway notification → user delivery
```

Keep BusinessApprovalRecord as a user-facing card, not a second approval engine.

## 4.8 Insights / Analytics / Evaluation

### Existing foundation

Likely concepts:

```text
Trace analytics
Evaluation
Org learning / evolution
```

### Business Portal additions

```text
BusinessInsightRecord
BusinessInsightSummary
BusinessInsightService
```

### Overlap assessment

Risk is medium.

BusinessInsightService currently computes lightweight metrics from BusinessRun and BusinessApproval file records.

This is useful for MVP but should not become the final analytics layer.

### Source of truth

```text
Execution traces, evaluations, correction history, approval outcomes
```

### Business Portal role

```text
Business-readable insight summary
```

### Recommendation

Future insights should aggregate from:

```text
AgentTrace / IntentRun
Evaluation results
Approval outcomes
Manual corrections
Tool failures
Team version comparisons
```

## 4.9 Evolution Proposal / Org Evolution / Delegated Task

### Existing foundation

Relevant concepts/packages:

```text
org/evolution
Delegated task
Org control center
Team versioning
```

### Business Portal additions

```text
EvolutionProposalRecord
EvolutionProposalService
EvolutionProposalDashboardIntegration
```

### Overlap assessment

Risk is high.

EvolutionProposalService now owns a proposal state machine and can apply approved proposals into Team Blueprint draft versions.

This is close to org/evolution responsibility.

### Source of truth

```text
Org/evolution and team versioning should own actual organization/team changes.
```

### Business Portal role

```text
Business-facing proposal state machine and review surface
```

### Recommendation

Add:

```text
EvolutionProposalAdapter
```

Responsibilities:

```text
Business proposal → org/evolution change request
Proposal approval → delegated task / approval flow
Apply proposal → existing versioning/evolution mechanism
Applied result → Business Portal projection
```

Current direct apply-to-TeamBlueprintDraft is acceptable as skeleton, but should be treated as temporary until aligned with foundation evolution.

## 4.10 Gateway / Notifications

### Existing foundation

Relevant packages:

```text
com.nousresearch.hermes.gateway
com.nousresearch.hermes.gateway.platforms
```

### Business Portal additions

No dedicated notification system yet.

### Overlap assessment

Future risk is medium.

When approvals or proposals need user notification, Business Portal must not create direct channel-specific delivery logic.

### Source of truth

```text
Gateway platform adapters
```

### Business Portal role

```text
Trigger notification intent and provide business card payload
```

### Recommendation

Use gateway adapters for:

```text
approval notifications
proposal review notifications
run failure alerts
insight digests
```

## 5. Immediate Architectural Risks

### 5.1 Business Portal services are currently file-backed source of truth

This is acceptable for MVP, but not final.

Risk:

```text
Business Portal records become independent truth stores.
```

Mitigation:

```text
Treat them as façade/projection until mapped to runtime foundations.
```

### 5.2 Generated objects may not be executable

If generation creates arbitrary tools/agents/prompts, it may produce assets that look valid but cannot run.

Mitigation:

```text
Generation must validate against actual tool/skill/agent foundation.
```

### 5.3 Approval and evolution semantics may split

BusinessApprovalService and EvolutionProposalService now own useful MVP state machines.

Risk:

```text
They drift away from ApprovalSystem and org/evolution.
```

Mitigation:

```text
Introduce adapters before expanding semantics further.
```

## 6. Recommended Next Work

### P0: Foundation Capability Inventory

Produce code-level inventory for:

```text
IntentOrchestrator callable entry points
Tool/Skill registry APIs
Agent runtime creation/update APIs
ApprovalSystem decision APIs
AgentTrace / IntentRun data structures
Org/evolution/delegated task APIs
Gateway notification APIs
```

### P0: Adapter Design

Before more feature work, define adapters:

```text
ScenarioIntentAdapter
TeamBlueprintCompiler
PromptAssetResolver
BusinessRunProjectionAdapter
BusinessApprovalAdapter
EvolutionProposalAdapter
```

### P1: Foundation-aware generation design

Define:

```text
StructuredTeamGenerationDraft schema
LLM prompt and schema validation
Tool availability validation
Approval policy validation
Runtime mapping strategy
Fallback/error behavior
```

### P1: BusinessTeamGenerationService MVP

Only after inventory and adapter boundaries are clear.

### P2: Runtime integration

```text
Team Blueprint → executable agents/collaboration
AgentTrace → BusinessRun
Insight → EvolutionProposal
Proposal → org/evolution mechanism
```

## 7. Non-goals for the Next Stage

Avoid:

```text
More isolated Business Portal CRUD fields
More manual forms before generation flow is designed
New approval/evolution semantics not backed by foundation
New tool naming conventions detached from registry
Direct channel notification logic inside Business Portal
```

## 8. Conclusion

The Business Portal direction remains valuable, but it must now shift from object creation to foundation integration.

The correct next question is no longer:

```text
What Business Portal object should we add next?
```

It is:

```text
Which Hermes foundation capability does this business object project, constrain or orchestrate?
```

Only after that answer is clear should new product features be added.

---

## 9. Follow-up Inventory Completed

Date: 2026-06-17

The P0 Foundation Capability Inventory requested in section 6 has been completed as a separate code-level document:

```text
docs/BUSINESS_PORTAL_FOUNDATION_CAPABILITY_INVENTORY.md
```

That document records concrete classes and entry points for:

```text
TenantManager / TenantContext
IntentOrchestrator / TaskOrchestrator / WorkflowEngine
TeamManager / Team / AgentRole / TenantBus / Agent runtime
ToolRegistry / TenantToolRegistry / TenantAwareToolDispatcher
SkillManager / TenantSkillManager
PromptAssetService / Memory / Org Knowledge
IntentRun / AgentTrace / AgentObservability
ApprovalSystem / ApprovalRequest / ApprovalResult
SelfEvolutionEngine / FailureCase / AgentEvaluation
DelegatedTaskStore
Gateway PlatformAdapter implementations
```

The inventory confirms the audit conclusion:

```text
Business Portal objects should be façade/projection/design-time artifacts.
Hermes foundation classes own execution, approval, trace, tenant, tool and evolution truth.
```

Updated next step:

```text
Do not implement generation API yet.
Design adapter contracts first:
- FoundationCapabilityValidator
- ScenarioIntentAdapter
- TeamBlueprintCompiler
- PromptAssetResolver
- BusinessRunProjectionAdapter
- BusinessApprovalAdapter
- EvolutionProposalAdapter
```
