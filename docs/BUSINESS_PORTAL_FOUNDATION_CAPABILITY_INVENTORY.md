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

---

## 9. Iteration 1 Status: FoundationCapabilityValidator

Date: 2026-06-17

First adapter-first implementation has started with:

```text
com.nousresearch.hermes.blueprint.FoundationCapabilityValidator
com.nousresearch.hermes.blueprint.FoundationCapabilityValidationReport
```

This implementation is intentionally non-mutating:

```text
It does not create business objects.
It does not expand UI.
It does not add generation API.
It does not compile or execute teams.
```

Current validation coverage:

```text
Workspace exists and resolves to tenantId
Tenant can be loaded through TenantManager
Active TeamBlueprintVersion can be located
Prompt refs use prompt://assetId or prompt://assetId#vN format
Agent role cards declare non-duplicate agentId
Agent responsibilities are present as warnings
Agent allowedTools resolve to ToolRegistry entries
Toolset availability is checked through ToolRegistry
Tool approval requirements are surfaced as warnings
Tenant security policy deny-list / allow-list is enforced in the report
```

Current finding codes include:

```text
workspace_missing
tenant_missing
active_version_missing
prompt_ref_invalid
prompt_ref_missing
agent_id_missing
agent_id_duplicate
agent_responsibility_missing
requested_tool_unavailable
toolset_unavailable
tool_requires_approval
requested_tool_denied_by_tenant_policy
requested_tool_not_allowed_by_tenant_policy
```

Important boundary:

```text
FoundationCapabilityValidator is a reporting gate, not an enforcement hook yet.
```

This preserves existing Business Portal smoke/demo flows while giving the next adapters and future generation path a real foundation grounding check.

Next likely iteration:

```text
TeamBlueprintCompiler design / skeleton
```

The compiler should consume a valid or warning-only validation report before mapping:

```text
TeamBlueprintVersion -> TeamManager / Team / AgentRole
```

---

## 10. Iteration 2 Status: TeamBlueprintCompiler Skeleton

Date: 2026-06-17

Second adapter-first implementation:

```text
com.nousresearch.hermes.blueprint.TeamBlueprintCompiler
com.nousresearch.hermes.blueprint.TeamBlueprintCompileResult
```

This is the first explicit design-time-to-foundation compile adapter.

Boundary:

```text
It does not create new Business Portal objects.
It does not add UI.
It does not add generation API.
It does not create a new runtime.
It only maps an existing TeamBlueprintVersion into existing collaboration foundation objects.
```

Compile flow:

```text
1. Run FoundationCapabilityValidator.
2. If validation has ERROR findings, refuse to apply.
3. Resolve WorkspaceRecord -> TenantContext.
4. Initialize tenant collaboration subsystem.
5. Create/reuse Team through TenantContext.getTeamManager().createTeam(...).
6. Convert each AgentBlueprintRecord into AgentRole.
7. Register roles through TenantContext.registerAgentRole(...).
8. Add each agent to Team.
9. Use first valid agent as initial team lead.
10. Store business blueprint metadata in Team shared state.
11. Save tenant state.
```

Current mapping:

| Business blueprint field | Foundation target |
|---|---|
| `TeamBlueprintRecord.teamId` | `Team.teamId` |
| `TeamBlueprintRecord.name` | `Team.name` |
| `description` + `operatingManual` | `Team.mission` |
| `scenarioId` | `Team.sharedState.business_scenario_id` |
| `promptAssetRefs` | `Team.sharedState.business_prompt_asset_refs` |
| `AgentBlueprintRecord.agentId` | Agent id in `TenantContext.registerAgentRole` and `Team.addMember` |
| `displayName` | `AgentRole.roleName` |
| `responsibility` | `AgentRole.description` + responsibilities |
| `knowledgeRefs` | `AgentRole.skills` for now |
| `allowedTools` | `AgentRole.allowedTools` |
| `approvalRules` | `AgentRole.maxAutoRisk(LOW)` + role metric |

Important limitation:

```text
TeamBlueprintCompiler does not instantiate TenantAwareAIAgent sessions yet.
It compiles topology and role metadata only.
```

This keeps runtime creation under existing agent/session foundation and avoids inventing a second agent runtime.

Next likely iteration:

```text
ScenarioIntentAdapter skeleton
```

or, if focusing on runtime observability first:

```text
BusinessRunProjectionAdapter skeleton
```

---

## 11. Iteration 3 Status: ScenarioIntentAdapter Skeleton

Date: 2026-06-17

Third adapter-first implementation:

```text
com.nousresearch.hermes.scenario.ScenarioIntentAdapter
com.nousresearch.hermes.scenario.ScenarioIntentRequest
```

Purpose:

```text
Keep ScenarioRecord as business framing.
Route planning/execution through IntentOrchestrator.
Avoid turning ScenarioService into a workflow engine.
```

Boundary:

```text
It does not add UI.
It does not add generation API.
It does not add new Business Portal objects.
It does not decompose tasks itself.
It does not select teammates itself.
It does not execute workflow steps itself.
```

Adapter behavior:

```text
ScenarioRecord + optional user input -> ScenarioIntentRequest
ScenarioIntentRequest.intent -> IntentOrchestrator.plan/execute
ScenarioRecord.entryTeamId -> preferredTeamId
Scenario successCriteria / approvalRules / metadata -> contextSignals
WorkspaceRecord -> TenantContext through WorkspaceService + TenantManager
```

Current mapping:

| Scenario field | Foundation target |
|---|---|
| `workspaceId` | `WorkspaceService.requireWorkspace` -> tenant |
| `scenarioId` | request metadata / scenario provenance |
| `name` | intent header |
| `description` | intent context |
| `entryTeamId` | `IntentOrchestrator.preferredTeamId` |
| `successCriteria` | intent text + contextSignals hints |
| `approvalRules` | intent text + `approval_required` / `high_stakes` hints |
| metadata `allowDelegation` / `allow_delegation` | `IntentOrchestrator.plan(... allowDelegation ...)` |
| metadata `contextSignals` / `context_signals` | `IntentOrchestrator` contextSignals |

Current methods:

```text
ScenarioIntentAdapter.toIntentRequest(...)
ScenarioIntentAdapter.plan(...)
ScenarioIntentAdapter.execute(...)
```

Important note:

```text
ScenarioIntentAdapter.execute returns the foundation IntentRun.
BusinessRunRecord projection is still a separate adapter, not handled here.
```

Next likely iteration:

```text
BusinessRunProjectionAdapter skeleton
```

That should project `IntentRun` / `AgentTrace` into business-readable run records without making BusinessRunService the trace store.

---

## 12. Iteration 4 Status: BusinessRunProjectionAdapter Skeleton

Date: 2026-06-17

Fourth adapter-first implementation:

```text
com.nousresearch.hermes.business.run.BusinessRunProjectionAdapter
```

Purpose:

```text
Project foundation execution truth into a Business Portal run story.
Keep IntentRun / AgentTrace as source of truth.
Avoid turning BusinessRunService into a second trace store.
```

Boundary:

```text
It does not execute work.
It does not add UI.
It does not add generation API.
It does not replace IntentRun / AgentTrace persistence.
It does not make BusinessRunService the primary runtime trace store.
```

Adapter behavior:

```text
IntentRun -> BusinessRunRecord
IntentRun.assignments -> assignment BusinessRunStep rows
IntentRun.attempts -> attempt BusinessRunStep rows
AgentTrace list -> trace summary BusinessRunStep rows
IntentRun status -> BusinessRunService status vocabulary
IntentRun runId -> technicalTraceRef intent://<runId>
projection metadata -> source=foundation:intent-run
```

Current methods:

```text
BusinessRunProjectionAdapter.fromIntentRun(workspaceId, scenarioId, scenarioName, run)
BusinessRunProjectionAdapter.fromIntentRun(workspaceId, scenarioId, scenarioName, run, traces)
BusinessRunProjectionAdapter.persistProjection(service, projection)
```

Persistence note:

```text
persistProjection(...) intentionally uses existing BusinessRunService.createRun(...).
The persisted business run gets its own business id, while metadata keeps projectionRunId and technicalTraceRef keeps the foundation intent:// id.
```

Current mapping:

| Foundation field | Business projection |
|---|---|
| `IntentRun.runId` | `technicalTraceRef=intent://runId`, metadata.intentRunId |
| `IntentRun.intent` | taskInput / taskTitle |
| `IntentRun.preferredTeamId` | teamId |
| `IntentRun.status` | BusinessRunRecord.status |
| `IntentRun.assignments` | assignment steps |
| `IntentRun.attempts` | attempt steps |
| `IntentRun.successes/failures` | metrics + conclusionReason |
| `AgentTrace` | trace summary steps + trace metrics |

Next likely iteration:

```text
BusinessApprovalAdapter skeleton
```

That should project ApprovalRequest / ApprovalResult into business approval cards without making BusinessApprovalService a second approval engine.

---

## 13. Iteration 5 Status: BusinessApprovalAdapter Skeleton

Date: 2026-06-17

Fifth adapter-first implementation:

```text
com.nousresearch.hermes.business.approval.BusinessApprovalAdapter
```

Purpose:

```text
Project foundation ApprovalRequest / ApprovalResult into Business Portal approval cards.
Keep ApprovalSystem as source of truth.
Avoid turning BusinessApprovalService into a second approval engine.
```

Boundary:

```text
It does not request approval itself.
It does not approve or deny ApprovalRequest directly.
It does not add UI.
It does not add generation API.
It does not create a new approval state machine.
```

Adapter behavior:

```text
ApprovalRequest -> BusinessApprovalRecord projection
ApprovalResult -> in-memory card resolution projection
BusinessApprovalService.createApproval(...) can persist the projection
BusinessApprovalService.approve/reject(...) can mirror a foundation result onto persisted cards
```

Current methods:

```text
BusinessApprovalAdapter.fromApprovalRequest(workspaceId, teamId, request)
BusinessApprovalAdapter.persistRequest(service, projection)
BusinessApprovalAdapter.withResult(card, result, actor)
BusinessApprovalAdapter.resolvePersisted(service, workspaceId, approvalId, result, actor)
```

Current mapping:

| Foundation field | Business approval projection |
|---|---|
| `ApprovalRequest.type` | title / evidence.approvalType / metadata.foundationApprovalType |
| `ApprovalRequest.operation` | summary / approveEffect / rejectEffect / evidence.operation |
| `ApprovalRequest.details` | summary / evidence.details |
| `ApprovalRequest.dangerous` | riskLevel / reasonRequired / evidence.dangerous |
| `ApprovalResult.approved` | APPROVED / REJECTED mirror status |
| `ApprovalResult.reason` | resolutionReason |
| `ApprovalResult.sessionApproved` | metadata.foundationSessionApproved |

Important note:

```text
Business approval cards are UX/projection artifacts.
Foundation ApprovalRequest.approve()/deny(...) remains the actual engine transition.
```

Next likely iteration:

```text
EvolutionProposalAdapter skeleton
```

That should connect business evolution proposals to SelfEvolutionEngine / DelegatedTaskStore / ApprovalSystem boundaries without creating a second evolution engine.

---

## 14. Iteration 6 Status: EvolutionProposalAdapter Skeleton

Date: 2026-06-17

Sixth adapter-first implementation:

```text
com.nousresearch.hermes.evolution.EvolutionProposalAdapter
```

Purpose:

```text
Connect Business Portal evolution proposals to Hermes foundation evolution, approval and delegated-task boundaries.
Keep SelfEvolutionEngine / DelegatedTaskStore / ApprovalSystem as the foundation sources of truth.
Avoid turning EvolutionProposalService into a second evolution engine.
```

Boundary:

```text
It does not apply runtime mutations.
It does not create or publish team blueprint versions by itself.
It does not approve ApprovalRequest directly.
It does not execute delegated tasks.
It does not add UI.
It does not add generation API.
```

Adapter behavior:

```text
EvolutionProposalRecord -> FailureCase
EvolutionProposalRecord -> SelfEvolutionEngine.recordFailure(...)
EvolutionProposalRecord -> ApprovalRequest
EvolutionProposalRecord -> BusinessApprovalRecord projection
EvolutionProposalRecord -> DelegatedTaskEnvelope
EvolutionProposalRecord -> DelegatedTaskStore.createPending(...)
```

Current methods:

```text
EvolutionProposalAdapter.toFailureCase(proposal)
EvolutionProposalAdapter.recordFailureLearning(proposal)
EvolutionProposalAdapter.toApprovalRequest(proposal)
EvolutionProposalAdapter.toBusinessApprovalCard(proposal)
EvolutionProposalAdapter.toDelegatedTaskEnvelope(proposal)
EvolutionProposalAdapter.createDelegatedReviewTask(proposal)
```

Current mapping:

| Business proposal field | Foundation target |
|---|---|
| `proposalId` | `FailureCase.id`, approval operation, delegated envelope runId |
| `teamId` / `targetTeamId` | `FailureCase.agentId`, delegated suggestedTeamId, business approval teamId |
| `finding` | `FailureCase.taskDescription/actualOutcome/diagnosis` |
| `proposedChange` | `FailureCase.lesson/correctiveAction`, approval details, delegated intent |
| `expectedBenefit` | `FailureCase.expectedOutcome`, approval evidence |
| evidence / metadata root cause | `FailureCase.RootCause` |
| evidence / metadata severity/risk | `FailureCase.Severity` and approval dangerous flag |

Important note:

```text
EvolutionProposalService.apply(...) remains the existing business draft/versioning skeleton.
EvolutionProposalAdapter only prepares foundation-backed learning/review/delegation artifacts.
```

Next likely iteration:

```text
PromptAssetResolver / PromptContextAdapter skeleton
```

or a cross-adapter smoke test wiring:

```text
ScenarioIntentAdapter -> BusinessRunProjectionAdapter -> BusinessInsight/Evolution path
```

---

## 15. Iteration 7 Status: PromptAssetResolver / PromptContext Skeleton

Date: 2026-06-17

Seventh adapter-first implementation:

```text
com.nousresearch.hermes.prompt.PromptAssetResolver
com.nousresearch.hermes.prompt.PromptContext
com.nousresearch.hermes.prompt.FoundationPromptAssetBridge
```

Purpose:

```text
Resolve business-managed prompt:// refs into explicit prompt context segments.
Optionally enrich prompt context from existing foundation memory, skills and org knowledge.
Avoid turning PromptAssetService into a second Memory / Skill / Knowledge system.
```

Boundary:

```text
It does not write prompt assets.
It does not modify memory, skills or organizational knowledge.
It does not add UI.
It does not add generation API.
It does not merge sources into a new knowledge store.
```

Adapter behavior:

```text
prompt://assetId -> active PromptAssetVersion
prompt://assetId#vN -> pinned PromptAssetVersion
PromptAssetVersion -> PromptContext.Segment(source=business-prompt-asset)
TenantMemoryManager.getSystemPromptSnapshot -> Segment(source=foundation-memory)
TenantSkillManager.listAvailableSkills -> Segment(source=foundation-skills)
OrganizationalKnowledgeBase.buildRagContext -> Segment(source=foundation-org-knowledge)
```

Current methods:

```text
PromptAssetResolver.resolve(workspaceId, refs)
PromptAssetResolver.resolve(workspaceId, refs, taskContext, ResolveOptions)
PromptAssetResolver.exists(workspaceId, assetId, version)
PromptAssetResolver.parse(ref)
PromptContext.render()
PromptContext.toMap()
```

Integration note:

```text
FoundationCapabilityValidator now accepts FoundationPromptAssetBridge.
PromptAssetResolver implements that bridge, so capability validation can check real prompt refs without depending on prompt storage internals.
```

Current mapping:

| Source | PromptContext segment |
|---|---|
| `PromptAssetVersion` | `business-prompt-asset` |
| `TenantMemoryManager` snapshot | `foundation-memory` |
| `TenantSkillManager` available skills | `foundation-skills` |
| `OrganizationalKnowledgeBase` RAG context | `foundation-org-knowledge` |

Important note:

```text
PromptContext is a projection for agent/runtime context assembly.
It is not a new source of truth.
```

Next likely iteration:

```text
Cross-adapter smoke test
```

Suggested chain:

```text
PromptAssetResolver + FoundationCapabilityValidator + TeamBlueprintCompiler + ScenarioIntentAdapter + BusinessRunProjectionAdapter + EvolutionProposalAdapter
```

This would validate the adapter-first architecture without adding product API/UI.

---

## 16. Iteration 8 Status: Cross-adapter Smoke Test

Date: 2026-06-17

Eighth adapter-first iteration adds a test-only architecture smoke chain:

```text
src/test/java/com/nousresearch/hermes/business/BusinessPortalAdapterChainSmokeTest.java
```

Purpose:

```text
Verify that the adapter-first Business Portal foundation chain can close without adding product API/UI.
Confirm Business Portal records remain façade/projection artifacts while foundation components own truth.
```

Smoke chain covered:

```text
WorkspaceService -> TenantManager / TenantContext
PromptAssetService -> PromptAssetResolver -> PromptContext
FoundationCapabilityValidator -> ToolRegistry + prompt bridge
TeamBlueprintService -> TeamBlueprintCompiler -> TeamManager / Team / AgentRole
ScenarioRecord -> ScenarioIntentAdapter -> IntentOrchestrator.IntentPlan
IntentRun -> BusinessRunProjectionAdapter -> BusinessRunRecord
EvolutionProposalRecord -> EvolutionProposalAdapter -> FailureCase / Approval card / DelegatedTask
```

Important test design note:

```text
Assertions are artifact-based, not global-count-based.
```

Reason:

```text
Tenant foundation can load persisted test-home state such as prior intent runs, memories or delegated tasks.
The smoke test verifies the current artifact exists and has the expected foundation references instead of assuming stores are empty.
```

This smoke test still does not:

```text
add product routes
add UI
call generation API
execute real agents through TenantBus
apply runtime mutations from evolution proposals
```

The current adapter-first baseline is now validated as a chain, not only as isolated adapters.

---

## 17. Iteration 9 Status: BusinessPortalFoundationFacade / AdapterRegistry

Date: 2026-06-17

Ninth adapter-first iteration adds a thin integration boundary:

```text
com.nousresearch.hermes.business.foundation.BusinessPortalFoundationFacade
com.nousresearch.hermes.business.foundation.BusinessPortalAdapterRegistry
```

Purpose:

```text
Give future Business Portal API/UI/generation code one foundation-grounded adapter boundary.
Prevent future product code from bypassing validation, compilation, projection and governance adapters.
```

Boundary:

```text
It does not add product routes.
It does not add UI.
It does not add generation API.
It does not create new business objects.
It does not execute agents by itself.
It only composes existing adapters.
```

Facade operations currently exposed:

```text
resolvePromptContext(...)
validateTeamBlueprint(...)
compileTeamBlueprint(...)
buildScenarioIntentRequest(...)
planScenarioIntent(...)
executeScenarioIntent(...)
projectIntentRun(...)
recordProposalLearning(...)
projectProposalApproval(...)
createProposalReviewTask(...)
```

Registry wiring:

```text
PromptAssetResolver
FoundationCapabilityValidator
TeamBlueprintCompiler
ScenarioIntentAdapter
BusinessRunProjectionAdapter
BusinessApprovalAdapter
EvolutionProposalAdapter
```

Design rule:

```text
Future Business Portal API/UI/generation code should depend on BusinessPortalFoundationFacade.
It should not directly stitch together low-level services in route handlers or UI integration classes.
```

Test coverage:

```text
BusinessPortalFoundationFacadeTest
```

The test verifies facade-level composition for prompt resolution, capability validation, team compilation, scenario planning and intent-run projection.

---

## 18. Iteration 10 Status: Foundation Adapter Development Contract

Date: 2026-06-17

Tenth adapter-first iteration adds a development contract document:

```text
docs/BUSINESS_PORTAL_FOUNDATION_ADAPTERS.md
```

Purpose:

```text
Make BusinessPortalFoundationFacade the default future boundary for API/UI/generation integration.
Document what each adapter owns and refuses to own.
Prevent future Business Portal work from bypassing validation, compiler, projection and governance adapters.
```

The contract defines:

```text
Core source-of-truth rule
Adapter ownership map
Mandatory flows for team generation/editing, scenario execution, run display, approval cards and evolution proposals
Generation guardrails
Review checklist for future PRs
Current non-goals
Recommended next engineering steps
```

Key rule:

```text
Future product code should call BusinessPortalFoundationFacade when touching foundation behavior.
Route handlers and dashboard integrations should not manually stitch together low-level foundation services if a facade method exists.
```

---

## 19. Iteration 11 Status: Business Portal Foundation Architecture Test

Date: 2026-06-17

Eleventh adapter-first iteration adds a lightweight JUnit architecture guard:

```text
src/test/java/com/nousresearch/hermes/business/foundation/BusinessPortalFoundationArchitectureTest.java
```

Purpose:

```text
Make the adapter/facade contract executable.
Prevent ordinary com.nousresearch.hermes.business classes from directly importing low-level foundation packages.
Keep business.foundation a thin wiring boundary.
```

Current scope is intentionally narrow:

```text
Only com.nousresearch.hermes.business.* production classes are checked.
Legacy dashboard/org handlers are not policed yet.
```

Allowed bridge classes:

```text
BusinessPortalFoundationFacade
BusinessPortalAdapterRegistry
BusinessApprovalAdapter
BusinessRunProjectionAdapter
```

This preserves existing adapter implementations while preventing future Business Portal service/dashboard/projection classes from bypassing the facade boundary.

---

## 20. Iteration 12 Status: BusinessInsightProjectionAdapter Skeleton

Date: 2026-06-17

Twelfth adapter-first iteration adds:

```text
com.nousresearch.hermes.business.insight.BusinessInsightProjectionAdapter
```

Purpose:

```text
Project foundation observability/evaluation/evolution signals into Business Portal insight records.
Keep AgentTrace / AgentEvaluation / SelfEvolutionEngine as source-of-truth signals.
Avoid making BusinessInsightService the only analytics truth from file-backed business records.
```

Boundary:

```text
It does not replace BusinessInsightService.
It does not analyze file-backed BusinessRunRecord as runtime truth.
It does not mutate traces, evals or evolution state.
It does not create proposals automatically.
It does not add UI or API.
```

Adapter behavior:

```text
AgentTrace list -> trace failure / human handoff / cost baseline insights
AgentEvaluation.EvalResult list -> eval regression insight
SelfEvolutionEngine.getSummary() map -> evolution backlog / pending suggestions insights
Combined foundation signals -> BusinessInsightSummary
```

Current methods:

```text
fromFoundationSignals(workspaceId, traces, evalResults, evolutionSummary)
fromTraces(workspaceId, traces)
fromEvalResults(workspaceId, evalResults)
fromEvolutionSummary(workspaceId, evolutionSummary)
```

Architecture guard update:

```text
BusinessInsightProjectionAdapter is now an explicit allowed bridge in BusinessPortalFoundationArchitectureTest.
```

This preserves the rule that ordinary Business Portal classes must not directly import foundation packages.

---

## 21. Iteration 13 Status: Insight Projection Added to Foundation Facade

Date: 2026-06-17

Thirteenth adapter-first iteration wires `BusinessInsightProjectionAdapter` into the unified foundation boundary:

```text
BusinessPortalFoundationFacade
BusinessPortalAdapterRegistry
```

New facade methods:

```text
projectFoundationInsights(workspaceId, traces, evalResults, evolutionSummary)
projectTraceInsights(workspaceId, traces)
projectEvalInsights(workspaceId, evalResults)
projectEvolutionInsights(workspaceId, evolutionSummary)
```

Registry now composes:

```text
BusinessInsightProjectionAdapter
```

Purpose:

```text
Keep all Business Portal foundation-backed projections reachable through BusinessPortalFoundationFacade.
Avoid making future API/UI code instantiate BusinessInsightProjectionAdapter directly when using the standard boundary.
```

No API/UI/generation surface was added.

---

## 22. Iteration 14 Status: Read-only Foundation Diagnostics

Date: 2026-06-17

Fourteenth adapter-first iteration adds a read-only diagnostics projection:

```text
com.nousresearch.hermes.business.foundation.BusinessPortalFoundationDiagnostics
BusinessPortalFoundationFacade.diagnostics()
```

Purpose:

```text
Expose the current facade/adapter baseline as a diagnostics report without adding API/UI.
Make it easy for future product integrations to verify the standard foundation boundary is wired and ready.
```

Diagnostics report includes:

```text
generatedAt
boundary name
adapter presence and implementation classes
guardrails
non-goals
facadeReady flag
```

Boundary:

```text
No runtime state mutation
No product route
No UI
No generation API
No foundation operation execution
```

Architecture guard update:

```text
business.foundation is still thin, now explicitly allowing:
- BusinessPortalFoundationFacade
- BusinessPortalAdapterRegistry
- BusinessPortalFoundationDiagnostics
```
