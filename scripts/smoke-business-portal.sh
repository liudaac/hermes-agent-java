#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${HERMES_BASE_URL:-http://127.0.0.1:8080}"
WORKSPACE_ID="${WORKSPACE_ID:-customer-service-demo}"
TEAM_ID="${TEAM_ID:-after-sales-team}"
APPROVAL_ACTION="${APPROVAL_ACTION:-approve}" # approve | reject | request-info | none | all

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

create_approval_card() {
  local suffix="$1"
  local output="$2"
  local title="High value refund approval"
  if [[ -n "$suffix" ]]; then
    title="High value refund approval ($suffix)"
  fi
  local approval_payload
  approval_payload="$(cat <<JSON
{
  "teamId": "$TEAM_ID",
  "title": "$title",
  "summary": "Customer requested a 1200 CNY refund; manual approval is required.",
  "reasonRequired": "Refund amount is above the automatic approval threshold.",
  "approveEffect": "The system will continue the refund flow and draft the customer reply.",
  "rejectEffect": "The case will stay in manual handling and the customer will not receive an automatic refund response.",
  "recommendation": "Approve after checking the product return condition.",
  "riskLevel": "HIGH",
  "evidence": {"orderId": "O-1001", "amount": 1200, "policy": "Refunds above 1000 CNY require human approval", "variant": "$suffix"}
}
JSON
)"
  request POST "/api/v1/workspaces/$WORKSPACE_ID/approvals" "$approval_payload" "$output"
  assert_ok "$output"
  LAST_APPROVAL_ID="$(json_get "$output" 'approvalId')"
  echo "APPROVAL_ID=$LAST_APPROVAL_ID"
}

perform_approval_action() {
  local action="$1"
  local approval_id="$2"
  local output="$3"
  case "$action" in
    approve)
      log "Approve business approval card: $approval_id"
      request POST "/api/v1/workspaces/$WORKSPACE_ID/approvals/$approval_id/approve" '{"actor":"ops-user","reason":"Product condition checked; policy allows refund."}' "$output"
      assert_ok "$output"
      ;;
    reject)
      log "Reject business approval card: $approval_id"
      request POST "/api/v1/workspaces/$WORKSPACE_ID/approvals/$approval_id/reject" '{"actor":"ops-user","reason":"Evidence is insufficient."}' "$output"
      assert_ok "$output"
      ;;
    request-info)
      log "Request more information for business approval card: $approval_id"
      request POST "/api/v1/workspaces/$WORKSPACE_ID/approvals/$approval_id/request-info" '{"actor":"ops-user","requestedInfo":"Please upload product return photos."}' "$output"
      assert_ok "$output"
      ;;
    none)
      echo "Leaving approval as PENDING: $approval_id"
      ;;
    *)
      echo "Unsupported approval action: $action" >&2
      exit 1
      ;;
  esac
}

APPROVAL_IDS=""

case "$APPROVAL_ACTION" in
  all)
    log "Create and process approval cards for all actions"
    create_approval_card "approve" "$TMP_DIR/approval-approve.json"
    APPROVAL_APPROVE_ID="$LAST_APPROVAL_ID"
    APPROVAL_IDS="$APPROVAL_IDS $APPROVAL_APPROVE_ID"
    perform_approval_action approve "$APPROVAL_APPROVE_ID" "$TMP_DIR/approval-action-approve.json"

    create_approval_card "reject" "$TMP_DIR/approval-reject.json"
    APPROVAL_REJECT_ID="$LAST_APPROVAL_ID"
    APPROVAL_IDS="$APPROVAL_IDS $APPROVAL_REJECT_ID"
    perform_approval_action reject "$APPROVAL_REJECT_ID" "$TMP_DIR/approval-action-reject.json"

    create_approval_card "request-info" "$TMP_DIR/approval-request-info.json"
    APPROVAL_REQUEST_INFO_ID="$LAST_APPROVAL_ID"
    APPROVAL_IDS="$APPROVAL_IDS $APPROVAL_REQUEST_INFO_ID"
    perform_approval_action request-info "$APPROVAL_REQUEST_INFO_ID" "$TMP_DIR/approval-action-request-info.json"
    APPROVAL_ID="$APPROVAL_APPROVE_ID"
    ;;
  approve|reject|request-info|none)
    log "Create business approval card"
    create_approval_card "$APPROVAL_ACTION" "$TMP_DIR/approval.json"
    APPROVAL_ID="$LAST_APPROVAL_ID"
    APPROVAL_IDS="$APPROVAL_ID"
    perform_approval_action "$APPROVAL_ACTION" "$APPROVAL_ID" "$TMP_DIR/approval-action.json"
    ;;
  *)
    echo "Unsupported APPROVAL_ACTION=$APPROVAL_ACTION" >&2
    echo "Supported values: approve | reject | request-info | none | all" >&2
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
echo "Approvals: $APPROVAL_IDS"
echo "Home risk: $(json_get "$TMP_DIR/home.json" 'risk.level')"
echo "Insights:  $(json_get "$TMP_DIR/insights.json" 'total')"
echo "OK"
