# Business Portal Foundation Capability Inventory

Date: 2026-06-17

This inventory follows `docs/BUSINESS_PORTAL_FOUNDATION_AUDIT.md` and turns the audit into a code-level capability map.

Scope guard for this pass:

```text
No new business objects
No UI expansion
No generation API
No runtime behavior changes
```

The goal is architectural convergence: identify the existing Hermes foundation classes Business Portal must reuse, where Business Portal currently duplicates responsibilities, and who owns the source of truth.

---

## 1. Executive Summary

Business Portal should remain a business façade over Hermes foundation capabilities.

The current Business Portal objects are useful product projections, but several have started to hold source-of-truth responsibilities that already exist in the foundation.

Most important conclusions:

| Business Portal Object | Should Reuse | Source of Truth | Current Risk |
|---|---|---|---|
| Workspace | `TenantManager`, `TenantContext` | Tenant foundation | Low; already mostly façade |
| Scenario | `IntentOrchestrator`, `WorkflowEngine`, `TaskOrchestrator` | Intent / workflow planning | Medium; currently static business JSON |
| Team Blueprint | `TeamManager`, `Team`, `AgentRole`, `TenantBus`, `TenantAwareAIAgent` | Collaboration / agent runtime | High; currently design-time only |
| Agent Blueprint | `AgentRole`, tenant tool registry, skill manager | Runtime role/capability registry | High; can describe non-executable roles/tools |
| Prompt Asset | `MemoryManager`, `TenantMemoryManager`, `SkillManager`, prompt/config conventions | Prompt/memory/skill foundation | Medium/High; useful but isolated |
| Allowed Tools | `ToolRegistry`, `TenantToolRegistry`, `TenantAwareToolDispatcher` | Tool registry + tenant policy | High; string list can drift from real tools |
| Business Run | `IntentOrchestrator.IntentRun`, `AgentTrace`, `AgentObservability` | Runtime execution traces | High; manual run records duplicate traces |
| Approval Card | `ApprovalSystem`, `ApprovalRequest`, `ApprovalResult`, browser/delegated approvals | Approval foundation | High; duplicated status machine |
| Insight | `InsightsEngine`, `AgentEvaluation`, `AgentObservability`, `SelfEvolutionEngine` | Trace/eval/evolution analytics | Medium; currently file-record aggregation |
| Evolution Proposal | `SelfEvolutionEngine`, `FailureCase`, `DelegatedTaskStore`, team versioning | Org evolution + governed change flow | High; separate proposal state machine |
| Notification | `GatewayServer`, `PlatformAdapter`, channel adapters | Gateway adapters | Medium; should not implement delivery itself |

Next architectural move should be adapters/compilers, not new product objects.

---

## 2. Existing Foundation Inventory

## 2.1 Tenant / Workspace Foundation

### Classes / packages

```text
com.nousresearch.hermes.tenant.core.TenantManager
com.nousresearch.hermes.tenant.core.TenantContext
com.nousresearch.hermes.tenant.core.TenantProvisioningRequest
com.nousresearch.hermes.tenant.core.TenantConfig
com.nousresearch.hermes.tenant.core.TenantToolRegistry
com.nousresearch.hermes.tenant.core.TenantSkillManager
com.nousresearch.hermes.tenant.core.TenantMemoryManager
com.nousresearch.hermes.tenant.quota.TenantQuotaManager
com.nousresearch.hermes.tenant.security.TenantSecurityPolicy
com.nousresearch.hermes.tenant.audit.TenantAuditLogger
com.nousresearch.hermes.tenant.sandbox.*
```

### Important entry points

```text
TenantManager.createTenant(TenantProvisioningRequest)
TenantManager.getOrCreateTenant(String, TenantProvisioningRequest)
TenantManager.getTenant(String)
TenantManager.getOrLoadTenant(String)
TenantManager.exists(String)
TenantManager.destroyTenant(String, boolean)

TenantContext.create(String, TenantProvisioningRequest)
TenantContext.load(String)
TenantContext.getTenantId()
TenantContext.getTenantDir()
TenantContext.getFileSandbox()
TenantContext.getMemoryManager()
TenantContext.getSkillManager()
TenantContext.getToolRegistry()
TenantContext.getQuotaManager()
TenantContext.getAuditLogger()
TenantContext.getSecurityPolicy()
TenantContext.getResourceMonitor()
```

### Business Portal mapping

```text
WorkspaceRecord.workspaceId == Tenant tenantId today
WorkspaceService.createWorkspace(...) calls TenantManager.createTenant(...)
```

### Source of truth

```text
TenantManager / TenantContext
```

### Required convergence

`WorkspaceService` should stay thin. It may own business-facing metadata such as display name, owner and description, but must not own:

```text
data isolation
sandbox roots
quota rules
security policy
audit semantics
lifecycle cleanup
```

Current implementation is acceptable because workspace creation provisions a tenant instead of inventing a separate isolation model.

---

## 2.2 Scenario / Intent / Workflow Foundation

### Classes / packages

```text
com.nousresearch.hermes.collaboration.IntentOrchestrator
com.nousresearch.hermes.collaboration.IntentOrchestrator.IntentPlan
com.nousresearch.hermes.collaboration.IntentOrchestrator.IntentRun
com.nousresearch.hermes.collaboration.TaskOrchestrator
com.nousresearch.hermes.collaboration.TaskOrchestrator.Pipeline
com.nousresearch.hermes.org.workflow.Workflow
com.nousresearch.hermes.org.workflow.WorkflowStep
com.nousresearch.hermes.org.workflow.WorkflowEngine
```

### Important entry points

```text
IntentOrchestrator.plan(String intent)
IntentOrchestrator.plan(String intent, String preferredTeamId)
IntentOrchestrator.plan(String intent, String preferredTeamId, boolean allowDelegation, List<?> contextSignals)
IntentOrchestrator.execute(String intent)
IntentOrchestrator.execute(String intent, String preferredTeamId)
IntentOrchestrator.execute(String intent, String preferredTeamId, boolean allowDelegation, List<?> contextSignals)
IntentOrchestrator.replayFailures(String runId)
IntentOrchestrator.reroute(String runId, String subtask, String targetAgentId)
IntentOrchestrator.getRun(String runId)
IntentOrchestrator.listRuns()

TaskOrchestrator.orchestrate(String name, List<TaskOrchestrator.Step> steps)
TaskOrchestrator.getPipeline(String id)
TaskOrchestrator.listPipelines()
```

### Business Portal mapping

```text
ScenarioRecord.description / goal / riskPolicy -> intent seed/context
ScenarioRecord.scenarioId -> preferred business context, not runtime executor
BusinessRunRecord should eventually project IntentRun / AgentTrace results
```

### Source of truth

```text
IntentOrchestrator for intent planning/execution
TaskOrchestrator / WorkflowEngine for multi-step workflow execution
ScenarioRecord for business framing only
```

### Repeated-wheel risk

`ScenarioService` could accidentally become a workflow engine if it starts interpreting tasks or routing steps.

### Required convergence

Add a future adapter, not new workflow logic:

```text
ScenarioIntentAdapter
```

Responsibilities:

```text
ScenarioRecord -> IntentOrchestrator.plan/execute input
Scenario risk policy -> approval/tool constraints
Scenario metadata -> preferredTeamId/context signals
IntentPlan/IntentRun -> business-facing plan/run projection
```

---

## 2.3 Team / Agent Runtime / Collaboration Foundation

### Classes / packages

```text
com.nousresearch.hermes.agent.AIAgent
com.nousresearch.hermes.agent.TenantAwareAIAgent
com.nousresearch.hermes.tenant.core.TenantAIAgent
com.nousresearch.hermes.tenant.core.TenantContext
com.nousresearch.hermes.collaboration.AgentRole
com.nousresearch.hermes.collaboration.Team
com.nousresearch.hermes.collaboration.TeamManager
com.nousresearch.hermes.collaboration.TenantBus
com.nousresearch.hermes.collaboration.AgentMessage
com.nousresearch.hermes.collaboration.GovernancePolicy
com.nousresearch.hermes.collaboration.Negotiator
com.nousresearch.hermes.org.identity.AgentIdentityManager
com.nousresearch.hermes.org.distributed.AgentRegistry
com.nousresearch.hermes.org.distributed.AgentRouter
```

### Important entry points

```text
TenantContext.initCollaboration()
TenantContext.registerAgentRole(String agentId, AgentRole role)
TenantContext.getAgentRole(String agentId)
TenantContext.listAgentRoles()
TenantContext.getTeamManager()
TenantContext.getTenantBus()
TenantContext.createAgent(String sessionId)
TenantContext.getOrCreateAgent(String sessionId)

TeamManager.createTeam(String teamId, String name, String mission, String createdBy)
TeamManager.getTeam(String teamId)
TeamManager.listTeams()
Team.addMember(String agentId)
Team.setLead(String agentId)

TenantBus.register(String agentId, Consumer<AgentMessage> handler)
TenantBus.sendAndWait(...)
TenantBus.listAgents()
```

### Business Portal mapping

```text
TeamBlueprintRecord -> design-time team blueprint
AgentBlueprintRecord -> design-time role card
TeamBlueprintVersion -> versioned business design artifact

Runtime equivalent:
TeamBlueprintRecord -> TeamManager.createTeam(...)
AgentBlueprintRecord -> AgentRole + TenantContext.registerAgentRole(...)
Team members -> Team.addMember(...)
Team lead/reporting -> Team.setLead(...) + AgentRole.reportsTo/collaborators/manages
```

### Source of truth

```text
TeamManager / Team / AgentRole / TenantBus / TenantAwareAIAgent
```

Team Blueprint is not the executable runtime source of truth. It is a design-time spec that must compile into foundation objects before it can be considered runnable.

### Repeated-wheel risk

`TeamBlueprintService` currently owns versioning and business JSON, which is fine. It must not grow into:

```text
agent runtime creator
team membership runtime
message bus
execution router
permission engine
```

### Required convergence

Future adapter/compile layer:

```text
TeamBlueprintCompiler
TeamRuntimeAdapter
```

Responsibilities:

```text
TeamBlueprintVersion ACTIVE -> Team + AgentRole topology
AgentBlueprintRecord.role/instructions -> AgentRole roleName/description/responsibilities/skills
AgentBlueprintRecord.allowedTools -> ToolRegistry + TenantToolRegistry validation
approvalRules -> ApprovalSystem / GovernancePolicy constraints
runtime apply result -> version metadata / projection
```

---

## 2.4 Tool / Skill / Capability Foundation

### Classes / packages

```text
com.nousresearch.hermes.tools.ToolRegistry
com.nousresearch.hermes.tools.ToolEntry
com.nousresearch.hermes.tools.ToolInitializer
com.nousresearch.hermes.tools.ToolInitializerV2
com.nousresearch.hermes.tools.TenantAwareToolDispatcher
com.nousresearch.hermes.tools.ToolPerformanceTracker
com.nousresearch.hermes.tenant.core.TenantToolRegistry
com.nousresearch.hermes.tenant.core.TenantSkillManager
com.nousresearch.hermes.tenant.core.TenantSkill
com.nousresearch.hermes.tenant.tools.TenantAwareSkillTool
com.nousresearch.hermes.skills.SkillManager
com.nousresearch.hermes.skills.SkillHubClient
```

### Important entry points

```text
ToolRegistry.getInstance()
ToolRegistry.register(ToolEntry)
ToolRegistry.getAllToolNames()
ToolRegistry.getAllTools()
ToolRegistry.getDefinitions(Set<String>, boolean)
ToolRegistry.getToolDefinitions(Set<String>)
ToolRegistry.dispatch(String, Map<String, Object>)
ToolRegistry.getAvailableToolsets()

TenantAwareToolDispatcher.dispatch(String toolName, Map<String, Object> args)
TenantContext.getToolRegistry().checkPermission(...)
TenantContext.getToolRegistry().recordToolCall(...)

SkillManager.createSkill(...)
SkillManager.listSkills()
SkillManager.getRelevantSkills(String taskDescription, int limit)
TenantSkillManager.list / install / quota APIs
```

### Business Portal mapping

```text
AgentBlueprintRecord.allowedTools -> capability selection view
PromptAssetRecord / TeamBlueprint promptAssetRefs -> business-managed instructions, not skills
```

### Source of truth

```text
ToolRegistry for global registered tools
TenantToolRegistry for tenant policy and permission
TenantAwareToolDispatcher for execution path
SkillManager / TenantSkillManager for skills
```

### Repeated-wheel risk

Business Portal currently stores `allowedTools` as strings. These strings may be:

```text
not registered globally
registered but unavailable because toolset env checks fail
registered but denied by tenant policy
registered but too risky for auto execution
invented by future generation logic
```

### Required convergence

Future validator:

```text
FoundationCapabilityValidator
```

Minimum checks:

```text
tool exists in ToolRegistry.getAllToolNames()
tool entry risk/approval type is known
tenant permission allows use through TenantToolRegistry.checkPermission(...)
toolset availability is reflected from ToolRegistry.getAvailableToolsets()
skill refs map to SkillManager/TenantSkillManager entries when applicable
```

`allowedTools` in Business Portal should be treated as desired capability selection, not proof of execution permission.

---

## 2.5 Prompt / Memory / Knowledge Foundation

### Classes / packages

```text
com.nousresearch.hermes.prompt.PromptAssetService
com.nousresearch.hermes.prompt.PromptAssetRecord
com.nousresearch.hermes.prompt.PromptAssetVersion
com.nousresearch.hermes.memory.MemoryManager
com.nousresearch.hermes.memory.MemoryRetriever
com.nousresearch.hermes.memory.SemanticMemoryRetriever
com.nousresearch.hermes.memory.ContextCardBuilder
com.nousresearch.hermes.memory.MemoryCardIntegrator
com.nousresearch.hermes.tenant.core.TenantMemoryManager
com.nousresearch.hermes.org.knowledge.OrganizationalKnowledgeBase
com.nousresearch.hermes.skills.SkillManager
```

### Important entry points

```text
PromptAssetService.createPromptAsset(...)
PromptAssetService.createDraftVersion(...)
PromptAssetService.activateVersion(...)
PromptAssetService.requirePromptAsset(...)
PromptAssetService.requireVersion(...)

TenantContext.getMemoryManager()
TenantContext.getOrgKnowledgeBase()
SkillManager.getRelevantSkills(...)
```

### Business Portal mapping

```text
PromptAssetRecord -> business-managed prompt/SOP asset façade
prompt://assetId -> active prompt asset ref
prompt://assetId#vN -> pinned version ref
TeamBlueprintVersion.promptAssetRefs -> prompt dependency declaration
```

### Source of truth

Current source of truth for Business Portal prompt assets is `PromptAssetService`, but foundation source is broader:

```text
Memory / organizational knowledge / skill assets / prompt conventions
```

### Repeated-wheel risk

Prompt Asset could become a separate knowledge system if it starts replacing memory, skills or org knowledge.

### Required convergence

Future resolver:

```text
PromptAssetResolver
PromptContextAdapter
```

Responsibilities:

```text
resolve prompt:// refs to active/pinned content
merge prompt asset content with memory/context cards where needed
expose warnings when a prompt asset overlaps with skill or memory assets
provide compiled prompt context to AgentRole / TenantAwareAIAgent
```

Do not add more prompt-asset UI before this resolution boundary is clear.

---

## 2.6 Run / Trace / Observability Foundation

### Classes / packages

```text
com.nousresearch.hermes.collaboration.IntentOrchestrator.IntentRun
com.nousresearch.hermes.collaboration.IntentOrchestrator.IntentAttempt
com.nousresearch.hermes.org.observe.AgentTrace
com.nousresearch.hermes.org.observe.AgentObservability
com.nousresearch.hermes.agent.CognitiveTrace
com.nousresearch.hermes.agent.CognitiveTraceCollector
com.nousresearch.hermes.trajectory.TrajectoryCollector
com.nousresearch.hermes.trajectory.TrajectoryCompressor
com.nousresearch.hermes.trajectory.InsightExtractor
com.nousresearch.hermes.gateway.SessionManager.ToolCallRecord
```

### Important entry points

```text
IntentOrchestrator.getRun(String runId)
IntentOrchestrator.listRuns()
IntentRun.toMap()
IntentAttempt.traceId()

AgentObservability.startTrace(String agentId, String sessionId, String task)
AgentObservability.completeTrace(AgentTrace trace)
AgentObservability.getTrace(String traceId)
AgentObservability.getRecentTraces(String agentId, int limit)
AgentObservability.getAllRecentTraces(int limit)
AgentObservability.getRecentAnomalies(int limit)

AgentTrace.step(AgentTrace.Step)
AgentTrace.end(AgentTrace.Status)
AgentTrace.toTimeline()
AgentTrace.forensics()
```

### Business Portal mapping

```text
BusinessRunRecord -> business-readable projection
BusinessRunStep -> projection of trace/tool/decision steps
technicalTraceRef -> AgentTrace.traceId or IntentRun.runId
```

### Source of truth

```text
IntentRun + AgentTrace + AgentObservability
```

### Repeated-wheel risk

`BusinessRunService.createRun(...)` supports manual/demo run records. That is acceptable for smoke/demo, but it should not become the runtime trace store.

### Required convergence

Future adapter:

```text
BusinessRunProjectionAdapter
```

Responsibilities:

```text
IntentRun -> BusinessRunRecord
IntentAttempt -> BusinessRunStep
AgentTrace.Step -> BusinessRunStep evidence/detail
AgentTrace status/errors -> BusinessRunRecord status/result/risk
Approval links -> related BusinessApprovalRecord ids
technicalTraceRef -> trace/run source pointer
```

Manual BusinessRun records should be marked as `source=manual|smoke|ui` and excluded from foundation-truth assumptions.

---

## 2.7 Approval / Governance Foundation

### Classes / packages

```text
com.nousresearch.hermes.approval.ApprovalSystem
com.nousresearch.hermes.approval.ApprovalRequest
com.nousresearch.hermes.approval.ApprovalResult
com.nousresearch.hermes.approval.ApprovalMessageHandler
com.nousresearch.hermes.approval.ToolRisk
com.nousresearch.hermes.browser.BrowserApprovalQueue
com.nousresearch.hermes.browser.BrowserApprovalRequest
com.nousresearch.hermes.collaboration.GovernancePolicy
com.nousresearch.hermes.collaboration.Negotiator
com.nousresearch.hermes.tools.TenantAwareToolDispatcher
```

### Important entry points

```text
ApprovalSystem.requestApproval(ApprovalType type, String operation, String details)
ApprovalSystem.wouldNeedApproval(ApprovalType type, String operation)
ApprovalSystem.setMode(ApprovalType type, ApprovalMode mode)
ApprovalSystem.setExternalApprover(Consumer<ApprovalRequest>)
ApprovalSystem.addSessionApproval(String operationKey)
ApprovalSystem.clearSessionApprovals()

TenantAwareToolDispatcher.setApprovalSystem(ApprovalSystem)
TenantAwareToolDispatcher.setApprovalMessageHandler(ApprovalMessageHandler)
TenantAwareToolDispatcher.dispatch(...)

GovernancePolicy.getApprovalMode(ToolRisk risk)
AgentRole.canAutoApprove(ToolRisk risk)
```

### Business Portal mapping

```text
BusinessApprovalRecord -> business/mobile approval card
BusinessApprovalService.approve/reject/requestInfo -> current card-level state transitions
```

### Source of truth

```text
ApprovalSystem + tool risk/governance policy + delegated/browser approval queues
```

### Repeated-wheel risk

Business Portal currently owns a separate status model:

```text
PENDING / APPROVED / REJECTED / INFO_REQUESTED
```

This duplicates approval lifecycle semantics unless bridged to `ApprovalSystem`.

### Required convergence

Future adapter:

```text
BusinessApprovalAdapter
```

Responsibilities:

```text
ApprovalRequest -> BusinessApprovalRecord projection
Business card approve/reject -> ApprovalRequest decision
INFO_REQUESTED -> explicit foundation concept or metadata extension, not hidden local status
ApprovalResult -> BusinessApprovalRecord resolution projection
ToolRisk/ApprovalType -> business riskLevel/reasonRequired
```

Until the adapter exists, BusinessApprovalService should be considered a card store, not the approval engine.

---

## 2.8 Evaluation / Insights / Evolution Foundation

### Classes / packages

```text
com.nousresearch.hermes.insights.InsightsEngine
com.nousresearch.hermes.org.eval.AgentEvaluation
com.nousresearch.hermes.org.eval.AgentEvaluation.EvalResult
com.nousresearch.hermes.org.eval.AgentEvaluation.EvalSuite
com.nousresearch.hermes.org.evolution.SelfEvolutionEngine
com.nousresearch.hermes.org.evolution.FailureCase
com.nousresearch.hermes.org.evolution.SelfEvolutionEngine.SkillSuggestion
com.nousresearch.hermes.monitoring.AgentEvalMetrics
com.nousresearch.hermes.monitoring.EvalSnapshot
com.nousresearch.hermes.org.observe.AgentObservability
```

### Important entry points

```text
AgentEvaluation.EvalResult.Builder
AgentEvaluation.compare(EvalResult a, EvalResult b, Map<Dimension, Double> weights)

SelfEvolutionEngine.recordFailure(FailureCase)
SelfEvolutionEngine.detectPatterns(String agentId, int minOccurrences)
SelfEvolutionEngine.detectOrgPatterns(int minOccurrences)
SelfEvolutionEngine.suggestSkill(String agentId)
SelfEvolutionEngine.recordSuccess(String agentId, String pattern, String description)
SelfEvolutionEngine.buildEvolutionPrompt(String agentId)
SelfEvolutionEngine.getSummary()

AgentObservability.detectDrift(...)
AgentObservability.getRecentAnomalies(...)
```

### Business Portal mapping

```text
BusinessInsightService -> business summary/projection
EvolutionProposalService -> business proposal review state
EvolutionProposalRecord -> product-facing proposal artifact
```

### Source of truth

```text
AgentTrace / AgentObservability / AgentEvaluation / SelfEvolutionEngine
```

### Repeated-wheel risk

`BusinessInsightService` currently derives insights from file-backed BusinessRuns and Approvals. `EvolutionProposalService` owns its own proposal state machine and can apply a Team Blueprint draft directly.

That risks creating a parallel evolution engine.

### Required convergence

Future adapters:

```text
BusinessInsightProjectionAdapter
EvolutionProposalAdapter
```

Responsibilities:

```text
AgentTrace/eval/failure cases -> BusinessInsightRecord
BusinessInsightRecord -> EvolutionProposal candidate only when backed by trace/eval evidence
EvolutionProposal approval -> ApprovalSystem / DelegatedTask flow
EvolutionProposal apply -> SelfEvolutionEngine / TeamBlueprint versioning boundary
```

Current direct apply to TeamBlueprint draft is acceptable only as a temporary skeleton.

---

## 2.9 Delegated Task / Safe Execution Foundation

### Classes / packages

```text
com.nousresearch.hermes.collaboration.DelegatedTask
com.nousresearch.hermes.collaboration.DelegatedTaskStore
com.nousresearch.hermes.collaboration.DelegatedTaskEnvelope
com.nousresearch.hermes.collaboration.DelegatedTaskResult
com.nousresearch.hermes.collaboration.DelegatedTaskExecutor
com.nousresearch.hermes.collaboration.DelegatedTaskExecutorRegistry
com.nousresearch.hermes.collaboration.DelegatedTaskExecutionPolicy
com.nousresearch.hermes.collaboration.ParentVerificationPolicy
com.nousresearch.hermes.collaboration.ParentVerificationResult
```

### Important entry points

```text
DelegatedTaskStore.createPending(DelegatedTaskEnvelope)
DelegatedTaskStore.get(String taskId)
DelegatedTaskStore.list()
DelegatedTaskStore.submitResult(String taskId, DelegatedTaskResult)
DelegatedTaskStore.executePending(String taskId, String executorName, DelegatedTaskExecutionPolicy)
DelegatedTaskStore.verify(String taskId, ParentVerificationPolicy)
DelegatedTaskStore.executorRegistry()
```

### Business Portal mapping

```text
High-risk team changes / proposal apply / external side effects -> delegated task candidate
Business approval card -> review surface for delegated task or approval request
```

### Source of truth

```text
DelegatedTaskStore + ApprovalSystem + parent verification policy
```

### Required convergence

Business Portal should not directly execute high-risk proposal changes. It should request a delegated/governed change when:

```text
proposal changes runtime topology
proposal changes tool permissions
proposal changes high-risk prompt policy
proposal affects production team version
```

---

## 2.10 Gateway / Notification Foundation

### Classes / packages

```text
com.nousresearch.hermes.gateway.GatewayServer
com.nousresearch.hermes.gateway.GatewayServerV2
com.nousresearch.hermes.gateway.PlatformAdapter
com.nousresearch.hermes.gateway.platforms.PlatformAdapter
com.nousresearch.hermes.gateway.platforms.FeishuAdapter
com.nousresearch.hermes.gateway.platforms.FeishuAdapterV2
com.nousresearch.hermes.gateway.platforms.TelegramAdapter
com.nousresearch.hermes.gateway.platforms.DiscordAdapter
com.nousresearch.hermes.gateway.SessionManager
```

### Important entry points

```text
GatewayServer.registerAdapter(PlatformAdapter)
GatewayServerV2.registerAdapter(PlatformAdapter)
PlatformAdapter.sendMessage(String channel, String content)
PlatformAdapter.sendReply(String channel, String messageId, String content)
SessionManager.getSession(...)
SessionManager.getSessionByChannel(...)
SessionManager.listSessions()
```

### Business Portal mapping

```text
Approval/proposal/run notifications -> notification intent + business card payload
```

### Source of truth

```text
Gateway adapters
```

### Repeated-wheel risk

Future Business Portal notification logic could directly call Feishu/Telegram/Discord APIs. That would bypass gateway session/delivery abstractions.

### Required convergence

Future delivery adapter:

```text
BusinessNotificationAdapter
```

Responsibilities:

```text
format business card payload
choose recipient/channel from workspace policy
send via Gateway PlatformAdapter
record delivery evidence on business projection
```

---

## 3. Business Portal Objects: Reuse Contract

## 3.1 Workspace

| Question | Answer |
|---|---|
| Business object | `WorkspaceRecord` |
| Existing service | `WorkspaceService` |
| Foundation to reuse | `TenantManager`, `TenantContext` |
| Source of truth | Tenant foundation |
| Portal role | Business display metadata and façade |
| Do not add | quota/security/sandbox/audit/lifecycle logic |

## 3.2 Scenario

| Question | Answer |
|---|---|
| Business object | `ScenarioRecord` |
| Existing service | `ScenarioService` |
| Foundation to reuse | `IntentOrchestrator`, `TaskOrchestrator`, `WorkflowEngine` |
| Source of truth | Intent/workflow planning and execution |
| Portal role | Business framing object |
| Do not add | routing engine, workflow interpreter, execution state |

## 3.3 Prompt Asset

| Question | Answer |
|---|---|
| Business object | `PromptAssetRecord`, `PromptAssetVersion` |
| Existing service | `PromptAssetService` |
| Foundation to reuse | Memory, tenant memory, org knowledge, skills, prompt conventions |
| Source of truth | Not singular yet; resolver needed |
| Portal role | Business-managed prompt/SOP façade |
| Do not add | separate knowledge base or skill system |

## 3.4 Team Blueprint / Agent Blueprint

| Question | Answer |
|---|---|
| Business object | `TeamBlueprintRecord`, `TeamBlueprintVersion`, `AgentBlueprintRecord` |
| Existing service | `TeamBlueprintService` |
| Foundation to reuse | `TeamManager`, `Team`, `AgentRole`, `TenantBus`, `TenantAwareAIAgent` |
| Source of truth | Collaboration/runtime topology |
| Portal role | Versioned design-time blueprint |
| Do not add | runtime team executor or message bus |

## 3.5 Business Run

| Question | Answer |
|---|---|
| Business object | `BusinessRunRecord`, `BusinessRunStep` |
| Existing service | `BusinessRunService` |
| Foundation to reuse | `IntentRun`, `AgentTrace`, `AgentObservability` |
| Source of truth | Runtime traces and intent runs |
| Portal role | Business-readable projection |
| Do not add | primary trace persistence or execution engine |

## 3.6 Approval Card

| Question | Answer |
|---|---|
| Business object | `BusinessApprovalRecord` |
| Existing service | `BusinessApprovalService` |
| Foundation to reuse | `ApprovalSystem`, `ApprovalRequest`, `ApprovalResult`, tool risk, delegated/browser approvals |
| Source of truth | Approval foundation |
| Portal role | Business/mobile card projection |
| Do not add | second approval semantics engine |

## 3.7 Insight

| Question | Answer |
|---|---|
| Business object | `BusinessInsightRecord`, `BusinessInsightSummary` |
| Existing service | `BusinessInsightService` |
| Foundation to reuse | `InsightsEngine`, `AgentEvaluation`, `AgentObservability`, `SelfEvolutionEngine` |
| Source of truth | Trace/eval/evolution analytics |
| Portal role | Business-readable summary |
| Do not add | independent analytics truth from file records only |

## 3.8 Evolution Proposal

| Question | Answer |
|---|---|
| Business object | `EvolutionProposalRecord` |
| Existing service | `EvolutionProposalService` |
| Foundation to reuse | `SelfEvolutionEngine`, `FailureCase`, `DelegatedTaskStore`, `ApprovalSystem`, team versioning |
| Source of truth | Org evolution + governed change flow |
| Portal role | Business review/proposal surface |
| Do not add | separate evolution engine |

---

## 4. Duplicate Wheel Register

| Duplicate Risk | Current Symptom | Why It Matters | Convergence Action |
|---|---|---|---|
| Workspace as second tenant model | `WorkspaceRecord` persists beside tenant registry | Data/security could split | Keep Workspace as façade; tenant remains truth |
| Scenario as workflow engine | Scenario could start holding process logic | Would bypass IntentOrchestrator/WorkflowEngine | Add `ScenarioIntentAdapter` |
| Team Blueprint as runtime team | Team JSON is not applied to `TeamManager`/`AgentRole` | Teams may look configured but cannot run | Add `TeamBlueprintCompiler` |
| AgentBlueprint allowed tools as strings | No validation against real registry/policy yet | Generated teams may request impossible tools | Add `FoundationCapabilityValidator` |
| PromptAsset as isolated knowledge base | Prompt asset storage separate from memory/skills/org knowledge | Knowledge and prompt context can diverge | Add `PromptAssetResolver` |
| BusinessRun as trace store | Manual `BusinessRunRecord` can exist without `AgentTrace` | Dashboard can show non-runtime truth | Add `BusinessRunProjectionAdapter` |
| BusinessApproval as approval engine | Separate `PENDING/APPROVED/REJECTED/INFO_REQUESTED` | Approval decisions may not affect tool/runtime approval | Add `BusinessApprovalAdapter` |
| BusinessInsight as analytics source | Aggregates file records only | Misses real traces/evals/tool failures | Add `BusinessInsightProjectionAdapter` |
| EvolutionProposal as evolution engine | Local state machine and direct apply to draft | Could bypass org evolution/governed task flow | Add `EvolutionProposalAdapter` |
| Business notification delivery | Future risk of direct channel code | Would bypass gateway adapters | Add `BusinessNotificationAdapter` |

---

## 5. Adapter Design Backlog

Do these before implementing BusinessTeamGenerationService or generation API.

### P0 adapters / validators

```text
FoundationCapabilityValidator
ScenarioIntentAdapter
TeamBlueprintCompiler
PromptAssetResolver
BusinessRunProjectionAdapter
BusinessApprovalAdapter
EvolutionProposalAdapter
```

### P1 adapters

```text
BusinessInsightProjectionAdapter
BusinessNotificationAdapter
TeamRuntimeAdapter
PromptContextAdapter
```

### P0 minimal outputs

Each P0 adapter design should specify:

```text
input schema
foundation APIs called
output schema
error/warning behavior
source-of-truth boundary
what it explicitly refuses to own
```

---

## 6. Generation Guardrails Derived from Inventory

When BusinessTeamGenerationService is eventually implemented, it must follow this order:

```text
1. WorkspaceService.requireWorkspace -> tenant exists
2. ScenarioIntentAdapter drafts intent context, but does not execute by default
3. PromptAssetResolver validates prompt refs/content boundaries
4. FoundationCapabilityValidator validates tools/skills against registry + tenant policy
5. TeamBlueprintCompiler validates design-time team can compile to Team/AgentRole
6. Approval policy is checked against ApprovalSystem / GovernancePolicy semantics
7. Output is a draft/plan first, not an auto-published runtime mutation
8. High-risk changes become approval/delegated-task requests
```

Generation output must include warnings such as:

```text
requested_tool_unavailable
requested_tool_denied_by_tenant_policy
tool_requires_approval
prompt_asset_ref_missing
team_blueprint_not_runtime_compilable
scenario_needs_more_clarification
```

---

## 7. Immediate Non-goals

Do not do these until adapters are designed:

```text
Do not add POST /team-generation
Do not add more Business Portal CRUD tabs
Do not add more manual UI forms
Do not add direct notification delivery
Do not add a new approval/evolution state machine
Do not let LLM-generated tools bypass ToolRegistry/TenantToolRegistry
Do not treat BusinessRunRecord as runtime truth without AgentTrace/IntentRun source
```

---

## 8. Recommended Next Step

Next commit should be design-only or adapter-skeleton-only, not a product feature.

Recommended order:

```text
1. Write adapter contracts in docs first
2. Start with FoundationCapabilityValidator design
3. Then TeamBlueprintCompiler design
4. Then BusinessRunProjectionAdapter and BusinessApprovalAdapter design
5. Only then revisit BusinessTeamGenerationService MVP
```

The key architecture rule remains:

```text
Business Portal presents and orchestrates.
Hermes Foundation executes and owns truth.
```
