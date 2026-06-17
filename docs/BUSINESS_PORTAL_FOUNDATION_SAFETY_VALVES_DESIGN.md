# Replay / Canary / Auto Rollback — Minimal Boundary Design

Status: design-only (2026-06-17)

This document scopes the minimal boundary for the safety valves described in
`docs/BUSINESS_AGENT_TEAM_PLATFORM_PLAN.md` section 8 (versioning, replay, canary, auto rollback, approval).

It does not implement anything. It exists so that any future implementation does not
silently grow into a second runtime or bypass the foundation facade.

---

## 1. Goal

Define how Business Portal proposes safe changes to runtime team behavior without owning
runtime execution itself.

```text
Source of truth: SelfEvolutionEngine, ApprovalSystem, DelegatedTaskStore, TeamBlueprint versioning, IntentOrchestrator.replayFailures
Business projection: SafetyValveRunRecord (planned, read-only)
Boundary: BusinessPortalFoundationFacade.projectSafetyValveRun(...) (planned)
```

## 2. Non-goals

```text
No autonomous canary runtime in Business Portal.
No new traffic-splitting infrastructure.
No second rollback engine.
No Business Portal-managed quota/policy mutation.
No UI tab.
```

## 3. Safety valve flows in scope

### 3.1 Replay

```text
Trigger: business reviewer asks to replay a failed scenario subtask.
Foundation API: IntentOrchestrator.replayFailures(runId)
Business Portal role: build a "replay request" envelope, surface preview, defer execution to foundation/delegated task path.
```

### 3.2 Canary

```text
Trigger: a Team Blueprint version transitions DRAFT -> CANARY before ACTIVE.
Foundation API: TeamBlueprintCompiler (existing) + governed approval + DelegatedTaskStore.
Business Portal role: present canary scope (which scenarios/users), show validation report, never execute traffic split itself.
```

### 3.3 Auto rollback

```text
Trigger: foundation observability detects regression after canary apply.
Foundation API: AgentObservability.detectDrift, SelfEvolutionEngine, version metadata.
Business Portal role: project rollback decision and link to the previous active version; do not roll back automatically without approval.
```

## 4. Required artifacts

```text
SafetyValveRunRecord (read-only projection)
{
  "workspaceId": "...",
  "valveType": "REPLAY" | "CANARY" | "ROLLBACK",
  "targetTeamBlueprintVersion": <int>,
  "rationale": "...",
  "evidence": { "intentRunIds": [...], "traceIds": [...], "evalRunIds": [...] },
  "approvalRef": "approval://...",
  "delegatedTaskRef": "delegated://...",
  "metadata": { "source": "foundation:safety-valve" }
}
```

## 5. Adapter contract

```text
BusinessSafetyValveAdapter.toReplayRequest(BusinessRunRecord)
BusinessSafetyValveAdapter.toCanaryProposal(TeamBlueprintRecord, TeamBlueprintVersion)
BusinessSafetyValveAdapter.toRollbackProposal(TeamBlueprintRecord, fromVersion, toVersion)
```

All three must:

```text
emit a foundation ApprovalRequest projection
emit a foundation DelegatedTaskEnvelope projection
preserve foundation references (intent://, trace://, eval://, prompt://)
refuse to execute foundation operations directly
```

## 6. Facade extension (deferred)

```text
BusinessPortalFoundationFacade.projectReplayRequest(...)
BusinessPortalFoundationFacade.projectCanaryProposal(...)
BusinessPortalFoundationFacade.projectRollbackProposal(...)
```

## 7. Dashboard endpoints (deferred)

```text
POST /api/v1/business/foundation/safety-valves/replay/preview
POST /api/v1/business/foundation/safety-valves/canary/preview
POST /api/v1/business/foundation/safety-valves/rollback/preview
```

All read-only. Each preview endpoint must produce:

```text
ApprovalRequest projection
DelegatedTaskEnvelope projection
foundation references list
```

## 8. Forbidden first steps

```text
Implementing replay/canary/rollback as direct Business Portal mutation endpoints.
Adding traffic split infrastructure inside Business Portal.
Bypassing TeamBlueprintCompiler for canary/rollback application.
Triggering rollback automatically from BusinessInsightProjectionAdapter findings.
```

## 9. Required tests before any future implementation

```text
BusinessSafetyValveAdapterTest (per valve type)
DashboardBusinessFoundationSafetyValvesRouteTest
Cross-endpoint smoke test extension
Architecture test allowlist update (if new adapters are added)
```

## 10. Open questions to resolve before implementation

```text
Q1: Where does canary scope live (workspace policy vs scenario metadata)?
Q2: Auto-rollback gate: ApprovalSystem session approval vs DelegatedTask completion?
Q3: How does observability drift signal feed into a Business Portal preview without bypassing privacy/security boundaries?
```

These are explicitly listed so the safety valves cannot be implemented in a single PR.
