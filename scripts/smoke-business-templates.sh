#!/usr/bin/env bash
# Smoke test for Business Portal templates.
# Lists agent + scenario templates, clones one into a fresh workspace,
# and verifies the resulting workspace/team/scenario exist.

set -euo pipefail

BASE_URL="${HERMES_BASE_URL:-http://127.0.0.1:8080}"
TEMPLATE_ID="${TEMPLATE_ID:-hr-onboarding-7day}"
WORKSPACE_NAME="${WORKSPACE_NAME:-Smoke Test - $(date +%s)}"
AUTH_HEADER=()
if [[ -n "${HERMES_TOKEN:-}" ]]; then
  AUTH_HEADER=(-H "Authorization: Bearer ${HERMES_TOKEN}")
fi

log() { printf '\n== %s ==\n' "$1"; }

req_json() {
  local method="$1" path="$2"
  if [[ $# -ge 3 ]]; then
    curl -sf "${AUTH_HEADER[@]}" -X "$method" "$BASE_URL$path" \
      -H "Content-Type: application/json" -d "$3"
  else
    curl -sf "${AUTH_HEADER[@]}" -X "$method" "$BASE_URL$path"
  fi
}

log "Listing agent templates"
agents=$(req_json GET /api/v1/business/agent-templates)
agent_count=$(echo "$agents" | python3 -c 'import json,sys; print(json.load(sys.stdin)["count"])')
echo "Found $agent_count agent templates"
echo "$agents" | python3 -c '
import json,sys
data = json.load(sys.stdin)
for item in data["items"][:5]:
    print(f"  - {item['"'"'templateId'"'"']:<30} {item['"'"'name'"'"']} ({item['"'"'category'"'"']})")
'

log "Listing scenario templates"
scenarios=$(req_json GET /api/v1/business/scenario-templates)
scenario_count=$(echo "$scenarios" | python3 -c 'import json,sys; print(json.load(sys.stdin)["count"])')
echo "Found $scenario_count scenario templates"

log "Fetching $TEMPLATE_ID detail"
req_json GET "/api/v1/business/scenario-templates/$TEMPLATE_ID" \
  | python3 -c '
import json,sys
d=json.load(sys.stdin)["item"]
print(f"  Name:        {d['"'"'name'"'"']}")
print(f"  Category:    {d['"'"'category'"'"']}")
print(f"  Agents:      {len(d['"'"'involvedAgents'"'"'])}")
print(f"  Assets:      {len(d['"'"'cloneBlueprint'"'"']['"'"'promptAssets'"'"'])}")
print(f"  Timeline:    {len(d['"'"'workflowTimeline'"'"'])} steps")
'

log "Cloning $TEMPLATE_ID"
clone=$(req_json POST "/api/v1/business/scenario-templates/$TEMPLATE_ID/clone" \
  "{\"workspaceName\":\"$WORKSPACE_NAME\"}")
workspace_id=$(echo "$clone" | python3 -c 'import json,sys; print(json.load(sys.stdin)["workspaceId"])')
team_id=$(echo "$clone" | python3 -c 'import json,sys; print(json.load(sys.stdin)["teamId"])')
scenario_id=$(echo "$clone" | python3 -c 'import json,sys; print(json.load(sys.stdin)["scenarioId"])')
echo "  workspaceId: $workspace_id"
echo "  teamId:      $team_id"
echo "  scenarioId:  $scenario_id"

log "Verifying cloned workspace home"
req_json GET "/api/v1/business/home?workspaceId=$workspace_id" | python3 -c '
import json,sys
d=json.load(sys.stdin)
summary=d.get("summary",{})
print(f"  workspaces:{summary.get('"'"'workspaceCount'"'"')} teams:{summary.get('"'"'teamCount'"'"')}")
'

log "Done — Business Templates smoke OK"
