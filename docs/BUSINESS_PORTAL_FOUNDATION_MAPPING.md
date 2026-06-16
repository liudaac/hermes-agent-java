# Business Portal Foundation Mapping

Date: 2026-06-16

This document maps the Business Portal product surface to existing Hermes foundation capabilities.

The purpose is to prevent Business Portal from becoming a second, parallel agent platform.

## 1. Core Principle

Business Portal should not create a second intelligent-agent core.

It should be:

```text
A business-facing façade over Hermes' existing tenant, orchestration, agent, tool, approval, trace and evolution foundations.
```

The product direction is:

```text
Business user describes a scenario
  ↓
Hermes foundation understands, constrains, generates and executes
  ↓
Business Portal presents the result as scenarios, teams, runs, approvals, insights and proposals
```

Therefore:

```text
LLM-first does not mean foundation-bypassing.
Business generation must be LLM-first and foundation-aware.
```

## 2. Existing Hermes Foundation Capabilities

The repository already contains several foundation layers that should be reused.

### 2.1 Tenant and isolation foundation

Relevant packages:

```text
com.nousresearch.hermes.tenant.core
com.nousresearch.hermes.tenant.persistence
com.nousresearch.hermes.tenant.quota
com.nousresearch.hermes.tenant.security
com.nousresearch.hermes.tenant.tools
```

Representative classes:

```text
TenantManager
TenantContext
TenantProvisioningRequest
TenantQuota
TenantAwareToolDispatcher
```

Business Portal implication:

```text
Workspace must remain a façade over Tenant.
Business Portal should not implement another isolation model.
```

### 2.2 Intent and orchestration foundation

Relevant packages:

```text
com.nousresearch.hermes.intent
com.nousresearch.hermes.orchestrator
```

Business Portal implication:

```text
Scenario understanding and team generation should reuse intent/orchestration concepts.
Do not build a standalone generation engine that bypasses Hermes orchestration.
```

### 2.3 Agent runtime foundation

Relevant packages:

```text
com.nousresearch.hermes.agent
com.nousresearch.hermes.agent.runtime
```

Representative classes include tenant-aware agent abstractions and runtime support.

Business Portal implication:

```text
Team Blueprint should eventually map to actual Hermes agent runtime definitions.
Agent roles in Business Portal should not remain decorative JSON.
```

### 2.4 Collaboration and bus foundation

Relevant packages:

```text
com.nousresearch.hermes.collaboration
```

Business Portal implication:

```text
Multi-agent team behavior should reuse collaboration and bus primitives.
Team Blueprint should eventually compile into collaboration topology or task flow.
```

### 2.5 Tool and skill foundation

Relevant packages:

```text
com.nousresearch.hermes.tools
```

Representative concepts:

```text
Tool registry
Tenant-aware tool dispatch
Tool permissions / quotas / performance tracking
```

Business Portal implication:

```text
Generated agent allowedTools must come from known tools/skills.
LLM output must be constrained by the actual tool registry.
Do not let generated teams invent unavailable tools.
```

### 2.6 Approval foundation

Relevant packages:

```text
com.nousresearch.hermes.approval
com.nousresearch.hermes.browser approval-related classes
com.nousresearch.hermes.business.approval
```

Representative classes:

```text
ApprovalSystem
ApprovalRequest
ApprovalResult
BusinessApprovalService
```

Business Portal implication:

```text
BusinessApprovalRecord should be a business/mobile-facing approval card.
It should not replace lower-level ApprovalSystem semantics.
High-risk generation and team publication should use the approval foundation.
```

### 2.7 Trace, run and execution foundation

Existing Business Portal classes:

```text
BusinessRunRecord
BusinessRunService
```

Likely lower-level sources:

```text
IntentRun
AgentTrace
Tool call traces
Runtime execution logs
```

Business Portal implication:

```text
BusinessRunRecord should be the business-readable projection of real execution traces.
Do not treat manually created BusinessRunRecord as the final runtime model.
```

### 2.8 Organization and evolution foundation

Relevant packages/concepts:

```text
org / evolution-related code
Delegated task
Business evolution proposal
```

Business Portal implication:

```text
EvolutionProposalRecord should align with existing org/evolution and delegated task concepts.
It should become the business-facing proposal state machine, not a separate evolution engine.
```

### 2.9 Gateway and channel foundation

Relevant packages:

```text
com.nousresearch.hermes.gateway
com.nousresearch.hermes.gateway.platforms
```

Business Portal implication:

```text
Notifications, approval delivery and external business-channel interactions should reuse Gateway adapters.
Do not implement separate delivery paths inside Business Portal.
```

## 3. Business Portal Objects and Foundation Mapping

| Business Portal Object | Current Role | Foundation It Should Reuse | Risk if Not Reused |
|---|---|---|---|
| Workspace | Business space façade | TenantManager / TenantContext | Duplicate isolation model |
| Scenario | Business scenario object | Intent / orchestration / workflow metadata | Scenario becomes static form data |
| Prompt Asset | Prompt stack asset | Prompt/memory/skill asset conventions | Isolated prompt library |
| Team Blueprint | Business team definition | Agent runtime / collaboration / org model | Decorative team JSON |
| Agent Role Card | Business-readable role | Agent runtime + tool registry | LLM invents impossible roles/tools |
| Business Run | Business-readable trace | AgentTrace / IntentRun / tool traces | Manual run log detached from execution |
| Approval Card | Business/mobile approval | ApprovalSystem / delegated task / gateway | Parallel approval system |
| Insight | Business diagnosis | Trace analytics / eval / org evolution | Static dashboard metrics |
| Evolution Proposal | Improvement proposal | Org/evolution / delegated task / team versioning | Parallel evolution engine |
| Notification | User-facing alert | Gateway adapters | One-off delivery logic |

## 4. Foundation-aware Scenario-first Team Generation

The corrected product direction is not just:

```text
Scenario-first Team Generation
```

It is:

```text
Foundation-aware Scenario-first Team Generation
```

That means:

```text
BusinessTeamGenerationService should not directly become a new agent platform.
It should orchestrate existing Hermes foundation capabilities and produce Business Portal façade objects.
```

## 5. Recommended Generation Architecture

### 5.1 Input

```json
{
  "description": "业务人员自然语言描述业务场景",
  "goal": "希望系统达成什么效果",
  "riskPolicy": "哪些动作必须审批",
  "knowledgeHints": ["可选知识来源"],
  "channelHints": ["可选业务入口"],
  "constraints": {
    "tenantId": "derived from workspace",
    "availableTools": "from tool registry",
    "approvalPolicy": "from approval foundation"
  }
}
```

### 5.2 Generation pipeline

```text
1. Resolve Workspace → TenantContext
2. Use Intent / Orchestration layer to understand the scenario
3. Ask LLM for structured generation under a strict schema
4. Validate generated tools against Tool/Skill registry
5. Validate risk actions against Approval foundation
6. Create ScenarioRecord
7. Create PromptAssetRecord and versions
8. Create TeamBlueprintRecord mapped to agent runtime concepts
9. Create sample BusinessRun suggestions
10. Return business-readable generation result
```

### 5.3 Output

```text
ScenarioRecord
PromptAssetRecord[]
TeamBlueprintRecord
Agent role cards
Approval rules
Sample run suggestions
Next actions
Warnings when requested tools/knowledge are unavailable
```

## 6. LLM-first, but Foundation-aware

The generation engine should be LLM-first.

But LLM output must be constrained by Hermes foundation capabilities.

Correct pattern:

```text
LLM proposes
Schema validates
Foundation constrains
Business Portal presents
Human approves high-risk changes
```

Incorrect pattern:

```text
LLM invents arbitrary tools, agents and workflows
Business Portal stores them as JSON
Runtime cannot execute them
```

Templates are still useful, but only as:

```text
few-shot examples
fallback fixtures
tests
demo seeds
industry starter packs
```

Templates must not define the primary capability boundary.

## 7. BusinessTeamGenerationService Should Be a Façade

Recommended module:

```text
BusinessTeamGenerationService
```

But its role should be:

```text
A façade / orchestrator over Hermes foundation capabilities.
```

It should depend on or integrate with:

```text
WorkspaceService / TenantManager
IntentOrchestrator
Tool registry / skill registry
PromptAssetService
TeamBlueprintService
BusinessApprovalService / ApprovalSystem
BusinessRunService / trace adapter
EvolutionProposalService
Gateway notification adapters when needed
```

It should not own:

```text
tenant isolation
agent execution
tool dispatch
approval semantics
trace persistence
notification delivery
```

## 8. Current Business Portal Implementation Status

Already useful as façade objects:

```text
WorkspaceRecord
ScenarioRecord
PromptAssetRecord / PromptAssetVersion
TeamBlueprintRecord / TeamBlueprintVersion
BusinessRunRecord
BusinessApprovalRecord
BusinessInsightRecord
EvolutionProposalRecord
```

But these must increasingly become projections or orchestration surfaces over Hermes foundation layers.

Current highest-risk duplicated areas:

```text
BusinessRunRecord is still mostly manual, not generated from AgentTrace.
BusinessApprovalRecord is business-facing but not fully unified with ApprovalSystem.
EvolutionProposalRecord is new and must be aligned with org/evolution/delegated task concepts.
TeamBlueprintRecord is not yet compiled into real Agent runtime/collaboration topology.
```

## 9. Corrected Next Steps

### P0: Foundation capability inventory

Before implementing generation, produce a concrete inventory:

```text
Available tools and skill names
Existing intent/orchestrator entry points
Agent runtime creation/update APIs
Trace objects that can become BusinessRunRecord
ApprovalSystem integration points
Org/evolution/delegated-task integration points
```

### P0: Foundation-aware generation design

Define:

```text
StructuredTeamGenerationDraft schema
Tool/skill validation strategy
Approval rule validation strategy
Agent role → runtime mapping strategy
Prompt asset creation strategy
Failure mode when foundation capability is missing
```

### P1: BusinessTeamGenerationService MVP

Only after P0 mapping:

```text
POST /api/v1/workspaces/{workspaceId}/team-generation
```

MVP must:

```text
Use LLM-first structured generation
Use TenantContext from Workspace
Validate tools against actual registry or explicitly mark unavailable
Create Scenario / Prompt Assets / Team Blueprint
Return warnings and next actions
```

### P1: /business main CTA

Replace object-first creation flow with:

```text
Describe business scenario → Generate intelligent team
```

Manual forms move to:

```text
Advanced editing
```

### P2: Runtime integration

```text
Team Blueprint → Agent runtime / collaboration topology
AgentTrace / IntentRun → BusinessRunRecord
BusinessRun failures → Insight
Insight → EvolutionProposal
EvolutionProposal → Team draft → Approval → Activate
```

## 10. Planning Rule Going Forward

Every future task should answer:

```text
Which Hermes foundation capability does this reuse?
How does this improve “business scenario description → generated intelligent team”?
Does this reduce or increase parallel systems?
```

If a task cannot answer those questions, it should be deprioritized.
