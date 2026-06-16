#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${HERMES_BASE_URL:-http://127.0.0.1:8080}"
WORKSPACE_ID="${WORKSPACE_ID:-customer-service-demo}"
TEAM_ID="${TEAM_ID:-after-sales-team}"
APPROVAL_ACTION="${APPROVAL_ACTION:-approve}" # approve | reject | request-info | none

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

log() {
  printf '\n== %s ==\n' "$1"
}

json_get() {
  local file="$1"
  local expr="$2"
  python3 - "$file" "$expr" <<'PY'
import json, sys
path, expr = sys.argv[1], sys.argv[2]
with open(path, 'r', encoding='utf-8') as f:
    data = json.load(f)
cur = data
for part in expr.split('.'):
    if not part:
        continue
    if isinstance(cur, list):
        cur = cur[int(part)]
    else:
        cur = cur.get(part)
    if cur is None:
        break
print('' if cur is None else cur)
PY
}

request() {
  local method="$1"
  local path="$2"
  local payload="${3:-}"
  local output="$4"
  local status_file="$output.status"

  if [[ -n "$payload" ]]; then
    curl -sS -X "$method" "$BASE_URL$path" \
      -H 'Content-Type: application/json' \
      -d "$payload" \
      -o "$output" \
      -w '%{http_code}' > "$status_file"
  else
    curl -sS -X "$method" "$BASE_URL$path" \
      -o "$output" \
      -w '%{http_code}' > "$status_file"
  fi

  local status
  status="$(cat "$status_file")"
  printf '%s %s -> HTTP %s\n' "$method" "$path" "$status"
  python3 -m json.tool "$output" || cat "$output"
  printf '\n'
}

assert_ok_or_conflict() {
  local output="$1"
  local status
  status="$(cat "$output.status")"
  if [[ "$status" != "200" && "$status" != "201" && "$status" != "409" ]]; then
    echo "Unexpected HTTP status: $status" >&2
    exit 1
  fi
}

assert_ok() {
  local output="$1"
  local status
  status="$(cat "$output.status")"
  if [[ "$status" != "200" && "$status" != "201" ]]; then
    echo "Unexpected HTTP status: $status" >&2
    exit 1
  fi
}

log "Business Portal smoke test"
echo "BASE_URL=$BASE_URL"
echo "WORKSPACE_ID=$WORKSPACE_ID"
echo "TEAM_ID=$TEAM_ID"
echo "APPROVAL_ACTION=$APPROVAL_ACTION"

log "Create workspace"
workspace_payload="$(cat <<JSON
{
  "workspaceId": "$WORKSPACE_ID",
  "name": "Customer Service Demo",
  "description": "Business workspace for after-sales scenarios",
  "owner": "ops",
  "metadata": {"department": "customer-service"}
}
JSON
)"
request POST "/api/v1/workspaces" "$workspace_payload" "$TMP_DIR/workspace.json"
assert_ok_or_conflict "$TMP_DIR/workspace.json"

log "Create team blueprint"
team_payload="$(cat <<JSON
{
  "teamId": "$TEAM_ID",
  "name": "After-sales Team",
  "description": "Handles refund and after-sales cases",
  "scenario": "after-sales ticket handling",
  "operatingManual": "Classify the ticket, check policy, decide whether approval is needed, then draft a response.",
  "promptAssetRefs": ["prompt://after-sales/base", "prompt://after-sales/refund-policy"],
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
}
JSON
)"
request POST "/api/v1/workspaces/$WORKSPACE_ID/team-blueprints" "$team_payload" "$TMP_DIR/team.json"
assert_ok_or_conflict "$TMP_DIR/team.json"

log "Create business run story"
run_payload="$(cat <<JSON
{
  "teamId": "$TEAM_ID",
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
    {"title": "Classify request", "summary": "Identified the case as a refund request.", "actor": "ticket-classifier", "evidence": "Customer explicitly asked for a return/refund."},
    {"title": "Check policy", "summary": "Matched the case against the 7-day return policy.", "actor": "policy-specialist", "evidence": "Signed 3 days ago; amount 89 CNY; not a special category."}
  ],
  "metrics": {"durationMs": 1234, "estimatedCost": 0.01}
}
JSON
)"
request POST "/api/v1/workspaces/$WORKSPACE_ID/runs" "$run_payload" "$TMP_DIR/run.json"
assert_ok "$TMP_DIR/run.json"
RUN_ID="$(json_get "$TMP_DIR/run.json" 'runId')"
echo "RUN_ID=$RUN_ID"

log "Create business approval card"
approval_payload="$(cat <<JSON
{
  "teamId": "$TEAM_ID",
  "title": "High value refund approval",
  "summary": "Customer requested a 1200 CNY refund; manual approval is required.",
  "reasonRequired": "Refund amount is above the automatic approval threshold.",
  "approveEffect": "The system will continue the refund flow and draft the customer reply.",
  "rejectEffect": "The case will stay in manual handling and the customer will not receive an automatic refund response.",
  "recommendation": "Approve after checking the product return condition.",
  "riskLevel": "HIGH",
  "evidence": {"orderId": "O-1001", "amount": 1200, "policy": "Refunds above 1000 CNY require human approval"}
}
JSON
)"
request POST "/api/v1/workspaces/$WORKSPACE_ID/approvals" "$approval_payload" "$TMP_DIR/approval.json"
assert_ok "$TMP_DIR/approval.json"
APPROVAL_ID="$(json_get "$TMP_DIR/approval.json" 'approvalId')"
echo "APPROVAL_ID=$APPROVAL_ID"

case "$APPROVAL_ACTION" in
  approve)
    log "Approve business approval card"
    request POST "/api/v1/workspaces/$WORKSPACE_ID/approvals/$APPROVAL_ID/approve" '{"actor":"ops-user","reason":"Product condition checked; policy allows refund."}' "$TMP_DIR/approval-action.json"
    assert_ok "$TMP_DIR/approval-action.json"
    ;;
  reject)
    log "Reject business approval card"
    request POST "/api/v1/workspaces/$WORKSPACE_ID/approvals/$APPROVAL_ID/reject" '{"actor":"ops-user","reason":"Evidence is insufficient."}' "$TMP_DIR/approval-action.json"
    assert_ok "$TMP_DIR/approval-action.json"
    ;;
  request-info)
    log "Request more information for business approval card"
    request POST "/api/v1/workspaces/$WORKSPACE_ID/approvals/$APPROVAL_ID/request-info" '{"actor":"ops-user","requestedInfo":"Please upload product return photos."}' "$TMP_DIR/approval-action.json"
    assert_ok "$TMP_DIR/approval-action.json"
    ;;
  none)
    echo "Leaving approval as PENDING."
    ;;
  *)
    echo "Unsupported APPROVAL_ACTION=$APPROVAL_ACTION" >&2
    exit 1
    ;;
esac

log "Read Business Portal home"
request GET "/api/v1/business/home?workspaceId=$WORKSPACE_ID" "" "$TMP_DIR/home.json"
assert_ok "$TMP_DIR/home.json"

log "Read Business Portal teams"
request GET "/api/v1/business/teams?workspaceId=$WORKSPACE_ID" "" "$TMP_DIR/teams.json"
assert_ok "$TMP_DIR/teams.json"

log "Read Business Portal runs"
request GET "/api/v1/business/runs?workspaceId=$WORKSPACE_ID" "" "$TMP_DIR/runs.json"
assert_ok "$TMP_DIR/runs.json"

log "Read Business Portal approvals"
request GET "/api/v1/business/approvals?workspaceId=$WORKSPACE_ID&status=ALL" "" "$TMP_DIR/approvals.json"
assert_ok "$TMP_DIR/approvals.json"

log "Read Business Portal insights"
request GET "/api/v1/business/insights?workspaceId=$WORKSPACE_ID" "" "$TMP_DIR/insights.json"
assert_ok "$TMP_DIR/insights.json"

log "Smoke summary"
echo "Workspace: $WORKSPACE_ID"
echo "Team:      $TEAM_ID"
echo "Run:       $RUN_ID"
echo "Approval:  $APPROVAL_ID"
echo "Home risk: $(json_get "$TMP_DIR/home.json" 'risk.level')"
echo "Insights:  $(json_get "$TMP_DIR/insights.json" 'total')"
echo "OK"
