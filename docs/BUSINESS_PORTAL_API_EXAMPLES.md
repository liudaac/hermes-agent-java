# Business Portal API Examples

This document shows the minimum end-to-end flow for the Business Portal backend MVP.

It exercises the five business-facing entries:

```text
Home      /api/v1/business/home
Teams     /api/v1/business/teams
Runs      /api/v1/business/runs
Approvals /api/v1/business/approvals
Insights  /api/v1/business/insights
```

## 0. Assumptions

Default base URL:

```bash
export HERMES_BASE_URL="http://127.0.0.1:8080"
```

If the dashboard is running on a different host or port, change `HERMES_BASE_URL`.

The smoke script version of this flow is available at:

```bash
scripts/smoke-business-portal.sh
```

## 1. Create a Workspace

A workspace is the business-facing façade over the underlying tenant.

```bash
curl -sS -X POST "$HERMES_BASE_URL/api/v1/workspaces" \
  -H 'Content-Type: application/json' \
  -d '{
    "workspaceId": "customer-service-demo",
    "name": "Customer Service Demo",
    "description": "Business workspace for after-sales scenarios",
    "owner": "ops",
    "metadata": {
      "department": "customer-service"
    }
  }'
```

Expected result:

```json
{
  "ok": true,
  "workspaceId": "customer-service-demo",
  "tenantId": "customer-service-demo",
  "message": "Workspace created"
}
```

Notes:

```text
workspaceId is the business space ID.
tenantId is the isolation ID used by the underlying tenant system.
The first MVP keeps workspaceId == tenantId, but both fields are returned deliberately.
```

## 2. Create a Team Blueprint

A team blueprint describes a digital employee team and its versioned role cards.

```bash
curl -sS -X POST "$HERMES_BASE_URL/api/v1/workspaces/customer-service-demo/team-blueprints" \
  -H 'Content-Type: application/json' \
  -d '{
    "teamId": "after-sales-team",
    "name": "After-sales Team",
    "description": "Handles refund and after-sales cases",
    "scenario": "after-sales ticket handling",
    "operatingManual": "Classify the ticket, check policy, decide whether approval is needed, then draft a response.",
    "promptAssetRefs": [
      "prompt://after-sales/base",
      "prompt://after-sales/refund-policy"
    ],
    "agents": [
      {
        "agentId": "ticket-classifier",
        "displayName": "Ticket Classifier",
        "responsibility": "Classify customer requests and route them to the right policy path",
        "knowledgeRefs": ["knowledge://after-sales/policy"],
        "allowedTools": ["order.query"],
        "approvalRules": ["Refunds above 1000 CNY require human approval"]
      },
      {
        "agentId": "policy-specialist",
        "displayName": "Policy Specialist",
        "responsibility": "Judge whether the request matches after-sales policies",
        "knowledgeRefs": ["knowledge://after-sales/policy"],
        "allowedTools": ["policy.search"],
        "approvalRules": ["Special categories require human confirmation"]
      }
    ]
  }'
```

Expected result:

```text
The returned team has activeVersion = 1.
Version 1 has status = ACTIVE.
```

## 3. Create a Business Run Story

A run record is a business-readable trace. It should explain what happened without forcing business users to read raw technical logs.

```bash
curl -sS -X POST "$HERMES_BASE_URL/api/v1/workspaces/customer-service-demo/runs" \
  -H 'Content-Type: application/json' \
  -d '{
    "teamId": "after-sales-team",
    "scenario": "refund request",
    "taskTitle": "Customer requested a refund",
    "taskInput": "The customer signed for the order 3 days ago and wants to return it.",
    "resultSummary": "Suggest allowing the customer to initiate a return request.",
    "conclusionReason": "The order was signed 3 days ago, the product is not a special category, and the amount is 89 CNY.",
    "systemAction": "Generated a customer-service reply draft.",
    "riskJudgement": "No manual approval required.",
    "nextSuggestion": "If the customer uploads damage photos, switch to the quality issue flow.",
    "status": "COMPLETED",
    "technicalTraceRef": "trace://demo-after-sales-run-1",
    "steps": [
      {
        "title": "Classify request",
        "summary": "Identified the case as a refund request.",
        "actor": "ticket-classifier",
        "evidence": "Customer explicitly asked for a return/refund."
      },
      {
        "title": "Check policy",
        "summary": "Matched the case against the 7-day return policy.",
        "actor": "policy-specialist",
        "evidence": "Signed 3 days ago; amount 89 CNY; not a special category."
      }
    ],
    "metrics": {
      "durationMs": 1234,
      "estimatedCost": 0.01
    }
  }'
```

Expected result:

```text
The returned run has a runId like run-xxxxxxxxxx.
GET /api/v1/business/runs?workspaceId=customer-service-demo should include this record.
```

## 4. Create a Business Approval Card

Approval cards are optimized for mobile review: users should understand the decision and act quickly.

```bash
curl -sS -X POST "$HERMES_BASE_URL/api/v1/workspaces/customer-service-demo/approvals" \
  -H 'Content-Type: application/json' \
  -d '{
    "teamId": "after-sales-team",
    "title": "High value refund approval",
    "summary": "Customer requested a 1200 CNY refund; manual approval is required.",
    "reasonRequired": "Refund amount is above the automatic approval threshold.",
    "approveEffect": "The system will continue the refund flow and draft the customer reply.",
    "rejectEffect": "The case will stay in manual handling and the customer will not receive an automatic refund response.",
    "recommendation": "Approve after checking the product return condition.",
    "riskLevel": "HIGH",
    "evidence": {
      "orderId": "O-1001",
      "amount": 1200,
      "policy": "Refunds above 1000 CNY require human approval"
    }
  }'
```

Expected result:

```text
The returned approval has an approvalId like apv-xxxxxxxxxx.
Its status is PENDING.
```

Approve it:

```bash
curl -sS -X POST "$HERMES_BASE_URL/api/v1/workspaces/customer-service-demo/approvals/$APPROVAL_ID/approve" \
  -H 'Content-Type: application/json' \
  -d '{
    "actor": "ops-user",
    "reason": "Product condition checked; policy allows refund."
  }'
```

Or reject it:

```bash
curl -sS -X POST "$HERMES_BASE_URL/api/v1/workspaces/customer-service-demo/approvals/$APPROVAL_ID/reject" \
  -H 'Content-Type: application/json' \
  -d '{
    "actor": "ops-user",
    "reason": "Evidence is insufficient."
  }'
```

Or ask for more information:

```bash
curl -sS -X POST "$HERMES_BASE_URL/api/v1/workspaces/customer-service-demo/approvals/$APPROVAL_ID/request-info" \
  -H 'Content-Type: application/json' \
  -d '{
    "actor": "ops-user",
    "requestedInfo": "Please upload product return photos."
  }'
```

## 5. View Business Portal Entries

### Home

```bash
curl -sS "$HERMES_BASE_URL/api/v1/business/home?workspaceId=customer-service-demo"
```

Home aggregates teams, runs, approvals and insights. It returns:

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

### Teams

```bash
curl -sS "$HERMES_BASE_URL/api/v1/business/teams?workspaceId=customer-service-demo"
```

### Runs

```bash
curl -sS "$HERMES_BASE_URL/api/v1/business/runs?workspaceId=customer-service-demo"
```

### Approvals

```bash
curl -sS "$HERMES_BASE_URL/api/v1/business/approvals?workspaceId=customer-service-demo&status=ALL"
```

### Insights

```bash
curl -sS "$HERMES_BASE_URL/api/v1/business/insights?workspaceId=customer-service-demo"
```

Insights include:

```text
workspaceCount
teamCount
runCount
failedRunCount
needsApprovalRunCount
pendingApprovalCount
highRiskApprovalCount
failureRate
nextActions
```

## 6. Run the Smoke Script

```bash
chmod +x scripts/smoke-business-portal.sh
HERMES_BASE_URL="http://127.0.0.1:8080" scripts/smoke-business-portal.sh
```

Optional variables:

```bash
WORKSPACE_ID="customer-service-demo"
TEAM_ID="after-sales-team"
APPROVAL_ACTION="approve" # approve | reject | request-info | none
```

Example:

```bash
HERMES_BASE_URL="http://127.0.0.1:8080" \
WORKSPACE_ID="customer-service-demo" \
TEAM_ID="after-sales-team" \
APPROVAL_ACTION="approve" \
scripts/smoke-business-portal.sh
```

## 7. Expected End State

After the flow completes:

```text
GET /api/v1/business/home      returns non-empty summary/today/insights/nextActions
GET /api/v1/business/teams     returns the created team blueprint
GET /api/v1/business/runs      returns the created run story
GET /api/v1/business/approvals returns the created approval card
GET /api/v1/business/insights  returns generated business insights
```
