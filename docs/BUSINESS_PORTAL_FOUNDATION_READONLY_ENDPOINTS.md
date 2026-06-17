# Business Portal Foundation Read-only Endpoints

Date: 2026-06-17

This document is the canonical reference for the read-only Business Portal foundation endpoints exposed by `DashboardServer`. They are the first product integration step on top of `BusinessPortalFoundationFacade` and intentionally do not mutate, generate or execute anything.

Related documents:

```text
docs/BUSINESS_PORTAL_FOUNDATION_AUDIT.md
docs/BUSINESS_PORTAL_FOUNDATION_CAPABILITY_INVENTORY.md
docs/BUSINESS_PORTAL_FOUNDATION_ADAPTERS.md
docs/BUSINESS_PORTAL_FOUNDATION_BASELINE.md
docs/BUSINESS_AGENT_TEAM_PLATFORM_PLAN.md
```

---

## 1. Common Rules

All endpoints in this document share the same boundary:

```text
Authorization: Bearer <dashboard session token>
Read-only: no foundation or business store mutation
No generation
No runtime execution
No new business object creation
By-reference only for record-bound endpoints
```

Common error responses:

```text
401 Unauthorized              if missing or invalid bearer token
400 {"ok": false, ...}        for missing required fields
404 {"ok": false, ...}        for missing workspace/record
500 {"ok": false, ...}        for unexpected errors
```

All success responses share:

```text
{"ok": true, ...}
```

---

## 2. Endpoint Catalog

### 2.1 GET /api/v1/business/foundation/diagnostics

Read-only adapter baseline.

Source:

```text
BusinessPortalFoundationFacade.diagnostics()
```

Tests:

```text
DashboardBusinessFoundationDiagnosticsRouteTest
```

### 2.2 POST /api/v1/business/foundation/team-blueprints/validate

Validate an existing team blueprint against foundation capability grounding.

Request:

```json
{ "workspaceId": "...", "teamId": "..." }
```

Source:

```text
BusinessPortalFoundationFacade.validateTeamBlueprint(workspaceId, teamBlueprint)
```

Behavior:

```text
Loads existing TeamBlueprintRecord by reference.
Validates against ToolRegistry, tenant policy, prompt bridge and active version.
Returns FoundationCapabilityValidationReport.toMap().
```

Tests:

```text
DashboardBusinessFoundationValidationRouteTest
```

### 2.3 POST /api/v1/business/foundation/prompt-context/preview

Resolve prompt refs and optionally enrich with foundation context.

Request:

```json
{
  "workspaceId": "...",
  "promptAssetRefs": ["prompt://base", "prompt://policy#v2"],
  "taskContext": "refund ticket",
  "includeFoundationContext": false
}
```

Source:

```text
BusinessPortalFoundationFacade.resolvePromptContext(workspaceId, refs, taskContext, options)
```

Tests:

```text
DashboardBusinessFoundationPromptContextRouteTest
```

### 2.4 POST /api/v1/business/foundation/scenarios/plan

Plan an existing scenario through `IntentOrchestrator` without execution.

Request:

```json
{ "workspaceId": "...", "scenarioId": "...", "userInput": "..." }
```

Source:

```text
BusinessPortalFoundationFacade.buildScenarioIntentRequest(scenario, userInput)
BusinessPortalFoundationFacade.planScenarioIntent(scenario, userInput)
```

Tests:

```text
DashboardBusinessFoundationScenarioPlanRouteTest
```

### 2.5 POST /api/v1/business/foundation/runs/project

Project an existing foundation IntentRun into a BusinessRunRecord.

Request:

```json
{ "workspaceId": "...", "intentRunId": "...", "scenarioId": "...", "scenarioName": "..." }
```

Source:

```text
TenantContext.getIntentOrchestrator().getRun(intentRunId)
BusinessPortalFoundationFacade.projectIntentRun(...)
```

Tests:

```text
DashboardBusinessFoundationRunProjectionRouteTest
```

### 2.6 POST /api/v1/business/foundation/insights/project

Project recent foundation traces and evolution summary into BusinessInsightSummary.

Request:

```json
{ "workspaceId": "...", "limit": 50 }
```

Source:

```text
TenantContext.getObservability().getAllRecentTraces(limit)
TenantContext.getEvolutionEngine().getSummary()
BusinessPortalFoundationFacade.projectFoundationInsights(...)
```

Tests:

```text
DashboardBusinessFoundationInsightProjectionRouteTest
```

### 2.7 POST /api/v1/business/foundation/evolution-proposals/preview

Preview foundation governance projections of an existing evolution proposal.

Request:

```json
{ "workspaceId": "...", "proposalId": "..." }
```

Source:

```text
BusinessPortalFoundationFacade.projectProposalFailureCase(proposal)
BusinessPortalFoundationFacade.projectProposalApproval(proposal)
BusinessPortalFoundationFacade.projectProposalDelegatedTaskEnvelope(proposal)
```

Tests:

```text
DashboardBusinessFoundationEvolutionProposalPreviewRouteTest
```

---

## 3. Architectural Guarantees

These endpoints together hold the following invariants:

```text
No endpoint creates, updates or deletes a Business Portal record.
No endpoint mutates Hermes foundation state.
No endpoint executes agents or workflows.
No endpoint accepts a generated payload as foundation truth.
All foundation references preserve foundation identifiers (intent://, trace://, prompt://, evolution-proposal://).
All payload sources are tagged via metadata (e.g. foundation:intent-run).
```

The `DashboardServer` auth middleware now stops downstream handlers after rejecting Host or Authorization, so unauthorized requests cannot leak read-only foundation projections.

---

## 4. Acceptance Test Set

The following tests collectively cover the read-only product integration surface:

```text
DashboardBusinessFoundationDiagnosticsRouteTest
DashboardBusinessFoundationValidationRouteTest
DashboardBusinessFoundationPromptContextRouteTest
DashboardBusinessFoundationScenarioPlanRouteTest
DashboardBusinessFoundationRunProjectionRouteTest
DashboardBusinessFoundationInsightProjectionRouteTest
DashboardBusinessFoundationEvolutionProposalPreviewRouteTest
BusinessPortalFoundationArchitectureTest
BusinessPortalFoundationFacadeTest
BusinessPortalFoundationDiagnosticsTest
BusinessPortalAdapterChainSmokeTest
```

These pass alongside the adapter-level tests already listed in `BUSINESS_PORTAL_FOUNDATION_BASELINE.md`.

---

## 5. Entry Conditions for the Next Phase

A future mutation/generation phase should not begin until at least the following are true:

```text
1. All read-only endpoints in section 2 are exercised in CI for at least one full release cycle.
2. Each mutation/generation endpoint has a written design doc explaining:
   - which foundation source-of-truth it touches
   - which adapter/facade method it calls
   - whether it produces an approval/delegated task artifact for high-risk changes
   - which BusinessPortalFoundationDiagnostics non-goal it changes (if any) and why
3. Architecture test coverage is extended before merging the new endpoint.
4. PR review checklist from BUSINESS_PORTAL_FOUNDATION_ADAPTERS.md is followed.
```

Forbidden first mutation steps:

```text
POST /api/v1/business/foundation/team-blueprints/generate
POST /api/v1/business/foundation/scenarios/execute (without design doc and approval boundary)
POST /api/v1/business/foundation/evolution-proposals/apply (without governed delegated task and approval)
```

---

## 6. Recommended Next Engineering Step

Continue read-only:

```text
- Add a smoke/integration test that calls every read-only endpoint in section 2 with the same workspace fixture.
- Or expose a top-level dashboard view that consumes BusinessPortalFoundationFacade.diagnostics() and renders adapter status.
```

Avoid jumping to mutation/generation endpoints without the entry conditions in section 5.
