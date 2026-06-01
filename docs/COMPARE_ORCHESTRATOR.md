# Tenant Compare Orchestrator

The server-side compare orchestrator runs multi-tenant comparison jobs in the backend instead of relying only on browser-side orchestration.

## Goals

- Run multiple tenants through a shared topic/prompt.
- Keep each tenant in its own session and tenant context.
- Allow each tenant to use its own allowed tools and auto-loaded skills.
- Preserve run status, events, and final conclusion in memory.
- Provide APIs to create, inspect, list, and stop comparison runs.

## API

### Create run

```http
POST /api/compare/runs
Content-Type: application/json

{
  "topic": "Discuss the deployment plan",
  "rounds": 3,
  "tenant_ids": ["tenant-a", "tenant-b", "tenant-c"]
}
```

Response:

```json
{
  "ok": true,
  "run": {
    "id": "...",
    "topic": "Discuss the deployment plan",
    "rounds": 3,
    "status": "PENDING",
    "participants": [
      { "tenant_id": "tenant-a", "session_id": "compare-..." }
    ],
    "event_count": 0
  }
}
```

### List runs

```http
GET /api/compare/runs
```

### Get run detail

```http
GET /api/compare/runs/{id}
```

Returns summary plus the event list.

### Stop run

```http
POST /api/compare/runs/{id}/stop
```

## Execution model

Runs execute asynchronously in the gateway process.

For `N` participants and `R` rounds, the orchestrator executes:

```text
participant[0] -> participant[1] -> ... -> participant[N-1]
```

for `R` cycles, feeding each response as the next participant's input.

After all turns finish, the orchestrator asks the default tenant to generate a neutral conclusion from all run events.

## Current limitations

- Run state is in-memory only.
- No SSE stream endpoint yet; clients poll `GET /api/compare/runs/{id}`.
- Stop is cooperative and takes effect between turns.
- Frontend ComparePage still uses browser-side orchestration; backend API is ready for the next integration step.

## Next step

Wire ComparePage to `/api/compare/runs` so browser refreshes do not interrupt comparison runs.
