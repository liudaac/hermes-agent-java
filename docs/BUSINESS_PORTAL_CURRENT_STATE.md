# Business Portal Current State

Date: 2026-06-16
Latest verified commit: `55da47e test: expand business portal approval smoke flow`

This document summarizes the current Business Portal implementation state after the backend, frontend, and smoke-flow iterations.

## 1. Executive Summary

Business Portal is now a working MVP that covers the full basic business loop:

```text
Create Workspace
  → Create Team Blueprint
  → Create Run Story
  → Create Approval Card
  → Approve / Reject / Request info
  → View Home / Teams / Runs / Approvals / Insights
```

The implementation includes:

```text
Backend APIs      ✅
File persistence  ✅
Dashboard UI      ✅
Create forms      ✅
Approval actions  ✅
Smoke script      ✅
Real API smoke    ✅
Browser screenshot ⏸ paused by environment / user choice
```

## 2. Main User-Facing Page

Business Portal is available in the Dashboard at:

```text
/business
```

Navigation label:

```text
Business
```

The page currently provides:

```text
Home metrics
Team cards
Run story cards
Approval cards
Insight cards
Next actions
Demo data command
Create object forms
Approval action buttons
```

## 3. Backend Capabilities

### 3.1 Workspace façade

Purpose:

```text
Give business users a Workspace object instead of exposing raw Tenant directly.
```

Key classes:

```text
WorkspaceRecord
FileWorkspaceRepository
WorkspaceService
WorkspaceDashboardIntegration
```

APIs:

```text
GET  /api/v1/workspaces
POST /api/v1/workspaces
GET  /api/v1/workspaces/{workspaceId}
```

Persistence:

```text
$HERMES_HOME/business/workspaces/{workspaceId}/workspace.json
```

Current behavior:

```text
POST /api/v1/workspaces auto-creates the underlying tenant.
MVP keeps workspaceId == tenantId but returns both fields.
```

### 3.2 Scenario API

Purpose:

```text
Represent reusable business scenarios and connect business goals to entry teams.
```

Key classes:

```text
ScenarioRecord
FileScenarioRepository
ScenarioService
ScenarioDashboardIntegration
```

APIs:

```text
GET  /api/v1/workspaces/{workspaceId}/scenarios
POST /api/v1/workspaces/{workspaceId}/scenarios
GET  /api/v1/workspaces/{workspaceId}/scenarios/{scenarioId}
```

Persistence:

```text
$HERMES_HOME/business/workspaces/{workspaceId}/scenarios/{scenarioId}.json
```

Current behavior:

```text
Scenario is now a first-class backend object.
Team Blueprint can store scenarioId.
Business Run can store scenarioId and can be filtered by scenarioId.
Business Insights can summarize runs for a scenarioId.
Scenario list/create UI is available in the /business page.
Scenario filter is available for runs and insights.
```

### 3.3 Prompt Asset API

Purpose:

```text
Represent workspace-scoped prompt assets that Team Blueprint versions can reference.
```

Key classes:

```text
PromptAssetRecord
FilePromptAssetRepository
PromptAssetService
PromptAssetDashboardIntegration
```

APIs:

```text
GET  /api/v1/workspaces/{workspaceId}/prompt-assets
POST /api/v1/workspaces/{workspaceId}/prompt-assets
GET  /api/v1/workspaces/{workspaceId}/prompt-assets/{assetId}
```

Persistence:

```text
$HERMES_HOME/business/workspaces/{workspaceId}/prompt-assets/{assetId}.json
```

Current behavior:

```text
Prompt Asset is now a first-class backend object.
Team Blueprint promptAssetRefs validation and Prompt Asset UI are still next-step work.
```

### 3.4 Team Blueprint Versioning

Purpose:

```text
Create and manage versioned digital employee teams.
```

Key classes:

```text
AgentBlueprintRecord
TeamBlueprintRecord
TeamBlueprintVersion
FileTeamBlueprintRepository
TeamBlueprintService
TeamBlueprintDashboardIntegration
```

APIs:

```text
GET  /api/v1/workspaces/{workspaceId}/team-blueprints
POST /api/v1/workspaces/{workspaceId}/team-blueprints
GET  /api/v1/workspaces/{workspaceId}/team-blueprints/{teamId}
POST /api/v1/workspaces/{workspaceId}/team-blueprints/{teamId}/versions
POST /api/v1/workspaces/{workspaceId}/team-blueprints/{teamId}/versions/{version}/activate
```

Persistence:

```text
$HERMES_HOME/business/workspaces/{workspaceId}/team-blueprints/{teamId}.json
```

Current behavior:

```text
Create team blueprint → v1 ACTIVE
Create draft version → vN DRAFT
Activate version → selected version ACTIVE, previous ACTIVE becomes INACTIVE
```

### 3.5 Business Run Story API

Purpose:

```text
Store business-readable run stories instead of exposing raw technical traces by default.
```

Key classes:

```text
BusinessRunRecord
BusinessRunStep
FileBusinessRunRepository
BusinessRunService
BusinessRunDashboardIntegration
```

APIs:

```text
GET  /api/v1/workspaces/{workspaceId}/runs
POST /api/v1/workspaces/{workspaceId}/runs
GET  /api/v1/workspaces/{workspaceId}/runs/{runId}
GET  /api/v1/business/runs
```

Persistence:

```text
$HERMES_HOME/business/workspaces/{workspaceId}/runs/{runId}.json
```

Business story fields:

```text
taskTitle
taskInput
resultSummary
conclusionReason
systemAction
riskJudgement
nextSuggestion
steps
technicalTraceRef
```

### 3.6 Business Approval Center

Purpose:

```text
Create and resolve mobile-first business approval cards.
```

Key classes:

```text
BusinessApprovalRecord
FileBusinessApprovalRepository
BusinessApprovalService
BusinessApprovalDashboardIntegration
```

APIs:

```text
GET  /api/v1/workspaces/{workspaceId}/approvals
POST /api/v1/workspaces/{workspaceId}/approvals
GET  /api/v1/workspaces/{workspaceId}/approvals/{approvalId}
POST /api/v1/workspaces/{workspaceId}/approvals/{approvalId}/approve
POST /api/v1/workspaces/{workspaceId}/approvals/{approvalId}/reject
POST /api/v1/workspaces/{workspaceId}/approvals/{approvalId}/request-info
GET  /api/v1/business/approvals
```

Persistence:

```text
$HERMES_HOME/business/workspaces/{workspaceId}/approvals/{approvalId}.json
```

Statuses:

```text
PENDING
APPROVED
REJECTED
INFO_REQUESTED
```

Current behavior:

```text
Only PENDING approvals can be resolved.
Resolved approvals cannot be processed again.
```

### 3.7 Business Insights API

Purpose:

```text
Generate lightweight business insights from workspace, team, run, and approval data.
```

Key classes:

```text
BusinessInsightRecord
BusinessInsightSummary
BusinessInsightService
BusinessInsightDashboardIntegration
```

APIs:

```text
GET /api/v1/business/insights
GET /api/v1/workspaces/{workspaceId}/insights
```

Metrics:

```text
workspaceCount
teamCount
runCount
failedRunCount
needsApprovalRunCount
pendingApprovalCount
highRiskApprovalCount
failureRate
```

Suggested actions:

```text
create-workspace
create-team-blueprint
run-sample-tasks
review-approvals
review-failed-runs
collect-more-runs
```

### 3.8 Business Home API

Purpose:

```text
Aggregate Business Portal data into a business command center.
```

API:

```text
GET /api/v1/business/home
```

Response sections:

```text
summary
today
needsAttention
risk
teamStatus
insights
nextActions
workspaces
emptyState
```

Risk levels:

```text
LOW
MEDIUM
HIGH
```

## 4. Frontend Capabilities

### 4.1 Dashboard route

Files:

```text
web/src/App.tsx
web/src/pages/BusinessPortalPage.tsx
web/src/components/business/BusinessPortalSections.tsx
web/src/components/business/BusinessPortalForms.tsx
web/src/lib/api.ts
```

Route:

```text
/business
```

### 4.2 Page sections

The page currently renders:

```text
Business header
Workspace selector
Refresh button
Create business objects panel
Empty state
Demo data guide
Summary metrics
Today health section
Needs attention section
Teams section
Team status section
Run stories section
Approvals section
Insights section
Next actions section
```

### 4.3 Create business objects panel

The create forms are grouped in a single panel:

```text
Create business objects
  1. Workspace
  2. Scenario
  3. Team Blueprint
  4. Run Story
  5. Approval Card
```

Default open behavior:

```text
No workspace → Workspace panel open
Workspace exists but no team → Team panel open
Run and Approval panels default closed
```

### 4.4 Create forms

The page can create:

```text
Workspace
Scenario
Team Blueprint
Run Story
Approval Card
```

Create Workspace fields:

```text
workspaceId
name
owner
description
```

Create Scenario fields:

```text
scenarioId
name
entryTeamId
description
successCriteria
approvalRules
```

Create Team Blueprint fields:

```text
teamId
name
scenario
description
operatingManual
```

Create Run Story fields:

```text
team
status
taskTitle
taskInput
resultSummary
conclusionReason
```

Create Approval Card fields:

```text
team
riskLevel
title
summary
reasonRequired
recommendation
approveEffect
rejectEffect
```

### 4.5 Cards and details

Run cards support detail expansion:

```text
Input
Conclusion reason
System action
Risk judgement
Next suggestion
Technical trace
Steps JSON preview
Metrics JSON preview
```

Approval cards support detail expansion:

```text
Why approval is required
If approved
If rejected
Recommendation
Resolved by
Resolution reason
Requested info
Evidence JSON preview
```

Insight cards support detail expansion:

```text
Possible cause
Expected benefit
Suggested action
Metrics JSON preview
```

### 4.6 Approval actions

For `PENDING` approvals, the page shows:

```text
Approve
Reject
Request info
```

Current MVP behavior:

```text
Approve accepts an editable reason.
Reject accepts an editable reason.
HIGH / CRITICAL approvals require confirmation phrases before Approve / Reject.
Request info accepts a short user-entered message.
All actions refresh page data after success.
```

## 5. Smoke and Verification

### 5.1 Smoke script

Script:

```text
scripts/smoke-business-portal.sh
```

Basic command:

```bash
HERMES_BASE_URL=http://127.0.0.1:9119 \
WORKSPACE_ID=customer-service-demo \
TEAM_ID=after-sales-team \
APPROVAL_ACTION=approve \
scripts/smoke-business-portal.sh
```

Enhanced approval action command:

```bash
HERMES_BASE_URL=http://127.0.0.1:9119 \
WORKSPACE_ID=customer-service-demo \
TEAM_ID=after-sales-team \
APPROVAL_ACTION=all \
scripts/smoke-business-portal.sh
```

Supported approval actions:

```text
approve
reject
request-info
none
all
```

`APPROVAL_ACTION=all` validates:

```text
approve endpoint
reject endpoint
request-info endpoint
```

### 5.2 Latest real smoke result

Command:

```bash
HERMES_BASE_URL=http://127.0.0.1:9119 \
WORKSPACE_ID=customer-service-demo \
TEAM_ID=after-sales-team \
APPROVAL_ACTION=all \
scripts/smoke-business-portal.sh
```

Result:

```text
Workspace: customer-service-demo
Team:      after-sales-team
Run:       run-86b3a85c-a
Approval:  apv-8e2d5cf1-3
Approvals: apv-8e2d5cf1-3 apv-dcfc9b72-e apv-15d53506-4
Home risk: LOW
Insights:  1
OK
```

### 5.3 Build and tests

Frontend build command:

```bash
cd web && npm run build
```

Backend full test command used during backend iterations:

```bash
mvn -q test
```

Known note:

```text
mvn -q test passes but emits Playwright missing dependency warnings in this environment.
```

## 6. Current Limitations

### 6.1 Browser screenshot check paused

Status:

```text
Paused
```

Reason:

```text
The user chose not to connect a browser for now.
The built-in browser tool also reported no supported browser in this environment.
Kimi WebBridge daemon was running, but extension_connected=false.
```

Remaining check:

```text
Open http://127.0.0.1:9119/business in a browser.
Verify layout, form behavior, card expansion, and approval actions visually.
Capture desktop and mobile screenshots.
```

### 6.2 MVP form limitations

Current forms are intentionally minimal:

```text
Scenario form creates reusable business scenario records.
Team form creates one default business-analyst role card.
Run form creates one default step.
Approval form creates structured evidence but not arbitrary evidence editing.
Approval actions use fixed approve/reject reasons.
High-risk approval does not yet require confirmation phrase.
No inline edit/delete for workspace/team/run/approval yet.
No pagination on Business Portal page yet.
```

### 6.3 Backend integration limitations

Current Business Portal data is stored as file-backed business records.

Still not fully connected to:

```text
Team Blueprint promptAssetRefs validation and Prompt Asset UI
Scenario-aware home aggregation, if needed
Real Agent execution
Raw AgentTrace conversion
Evolution proposal state machine
Prompt asset registry UI
Knowledge source management UI
Fine-grained permission model
```

## 7. Next Recommended Work

### P0: Browser visual verification

When browser access is available:

```text
Start Dashboard
Run smoke with APPROVAL_ACTION=all
Open /business
Verify desktop layout
Verify mobile layout
Verify create forms
Verify details expansion
Verify approval action buttons
Capture screenshots
```

### P1: Improve form quality

Recommended next UI improvements:

```text
Add textarea autosize or richer field layout if needed.
Add inline examples/templates for common scenarios.
Improve form grouping once browser screenshots are available.
Add multi-person approval or policy-backed confirmation for truly critical workflows.
```

### P1: Team blueprint editor

Recommended next backend/frontend improvements:

```text
Edit role cards.
Add/remove agents.
Create draft version from current version.
Activate draft version from UI.
Show version history and active version badge.
```

### P2: Real execution integration

Recommended product evolution:

```text
Generate BusinessRunRecord from actual AgentTrace.
Link technicalTraceRef to raw technical trace view.
Create approval cards from real high-risk actions.
Generate insights from real failure and correction patterns.
```

## 8. Important Files

Backend:

```text
src/main/java/com/nousresearch/hermes/workspace/*
src/main/java/com/nousresearch/hermes/blueprint/*
src/main/java/com/nousresearch/hermes/scenario/*
src/main/java/com/nousresearch/hermes/prompt/*
src/main/java/com/nousresearch/hermes/business/approval/*
src/main/java/com/nousresearch/hermes/business/run/*
src/main/java/com/nousresearch/hermes/business/insight/*
src/main/java/com/nousresearch/hermes/dashboard/DashboardServer.java
```

Frontend:

```text
web/src/App.tsx
web/src/lib/api.ts
web/src/pages/BusinessPortalPage.tsx
web/src/components/business/BusinessPortalSections.tsx
web/src/components/business/BusinessPortalForms.tsx
```

Docs and smoke:

```text
docs/BUSINESS_AGENT_TEAM_PLATFORM_PLAN.md
docs/BUSINESS_PORTAL_ITERATION_BREAKDOWN.md
docs/BUSINESS_PORTAL_API_EXAMPLES.md
docs/BUSINESS_PORTAL_SMOKE_RESULT.md
docs/BUSINESS_PORTAL_CURRENT_STATE.md
scripts/smoke-business-portal.sh
```

## 9. Current Commit Chain

Recent commits, newest first:

```text
55da47e test: expand business portal approval smoke flow
9bfc876 refactor: group business portal create forms
fa79366 feat: add business approval action buttons
d816665 feat: add business approval card create form
7d7f89d feat: add business run story create form
9c9a492 feat: add business team blueprint create form
7b31683 feat: add business workspace create form
93b8a88 feat: add business portal card detail expanders
f4030d5 feat: add business portal demo data guide
01dc986 refactor: split business portal dashboard components
cf7838e docs: record business portal smoke result
6ff1359 feat: add business portal dashboard page
d23c4e8 docs: add business portal api smoke flow
59e3d30 feat: aggregate business portal home metrics
f5ebc75 feat: add business insights APIs
```
