# Business Portal Smoke Result

Date: 2026-06-16

## Scope

This smoke check validates the Business Portal backend loop and prepares the UI screenshot check.

Tested flow:

```text
Start Dashboard
  ↓
Run scripts/smoke-business-portal.sh
  ↓
Create workspace
  ↓
Create team blueprint
  ↓
Create business run story
  ↓
Create business approval card
  ↓
Approve approval card
  ↓
Read Business Portal home / teams / runs / approvals / insights
```

## Environment

Dashboard command:

```bash
java -jar target/hermes-agent-java-0.1.0-SNAPSHOT.jar dashboard --port 9119 --host 127.0.0.1
```

Smoke command:

```bash
HERMES_BASE_URL=http://127.0.0.1:9119 \
WORKSPACE_ID=customer-service-demo \
TEAM_ID=after-sales-team \
APPROVAL_ACTION=approve \
scripts/smoke-business-portal.sh
```

## Result

Smoke script completed successfully.

Summary printed by script:

```text
Workspace: customer-service-demo
Team:      after-sales-team
Run:       run-05dfe880-c
Approval:  apv-cf8a7f43-d
Home risk: LOW
Insights:  1
OK
```

Validated endpoints:

```text
POST /api/v1/workspaces
POST /api/v1/workspaces/{workspaceId}/team-blueprints
POST /api/v1/workspaces/{workspaceId}/runs
POST /api/v1/workspaces/{workspaceId}/approvals
POST /api/v1/workspaces/{workspaceId}/approvals/{approvalId}/approve
GET  /api/v1/business/home
GET  /api/v1/business/teams
GET  /api/v1/business/runs
GET  /api/v1/business/approvals
GET  /api/v1/business/insights
```

## UI Screenshot Check

Intended URL:

```text
http://127.0.0.1:9119/business
```

Status:

```text
Blocked in this environment.
```

Reason:

```text
The built-in browser tool reported: No supported browser found.
Kimi WebBridge daemon was running, but the browser extension was not connected:
{"extension_connected":false,"running":true,"version":"v1.9.17"}
```

Therefore the backend smoke test is complete, but visual screenshot verification still needs a browser-enabled environment or a connected Kimi WebBridge extension.

## Next Manual/Automated UI Check

When a browser is available:

```text
1. Start Dashboard on port 9119.
2. Run scripts/smoke-business-portal.sh with HERMES_BASE_URL=http://127.0.0.1:9119.
3. Open http://127.0.0.1:9119/business.
4. Confirm the page shows:
   - workspace customer-service-demo
   - team after-sales-team
   - at least one run story
   - at least one approval card, approved or visible via status=ALL data
   - one or more insights
   - Home risk LOW after approval
5. Capture desktop and mobile-width screenshots.
```


## Enhanced Approval Action Smoke

Date: 2026-06-16

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
Approvals:  apv-8e2d5cf1-3 apv-dcfc9b72-e apv-15d53506-4
Home risk: LOW
Insights:  1
OK
```

This validates that the script can exercise all approval action endpoints in one run:

```text
POST /api/v1/workspaces/{workspaceId}/approvals/{approvalId}/approve
POST /api/v1/workspaces/{workspaceId}/approvals/{approvalId}/reject
POST /api/v1/workspaces/{workspaceId}/approvals/{approvalId}/request-info
```


## Prompt Asset → Team Blueprint Smoke

Date: 2026-06-16

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
Smoke completed successfully with exit code 0.
```

This validates the prompt asset chain:

```text
POST /api/v1/workspaces/{workspaceId}/prompt-assets
  → creates after-sales-base
POST /api/v1/workspaces/{workspaceId}/team-blueprints
  → uses promptAssetRefs: ["prompt://after-sales-base"]
  → backend validates the referenced PromptAsset
```


## Prompt Asset Version Smoke

Date: 2026-06-16

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
Smoke completed successfully with exit code 0.
```

This validates the versioned Prompt Asset chain:

```text
Create Prompt Asset v1
Create Prompt Asset draft version
Activate prompt asset v2
Create Team Blueprint with promptAssetRefs: ["prompt://after-sales-base#v2"]
Validate explicit prompt version reference in TeamBlueprintService
```
