# Eval Run Read-only Projection — Design Sketch

Status: design-only (2026-06-17)

This document scopes a future read-only projection of foundation `AgentEvaluation.EvalResult`
data into a Business Portal `EvalRun` artifact. It is not yet implemented.

It satisfies the gap noted in `docs/BUSINESS_PORTAL_FOUNDATION_ALIGNMENT_REVIEW.md` section 4.3.

---

## 1. Goal

Give business reviewers a foundation-grounded view of agent evaluation results, without
adding a new business object that owns evaluation truth.

```text
Source of truth: AgentEvaluation / EvalResult / EvalSuite (Hermes foundation)
Business projection: EvalRun read-only record
Adapter: BusinessEvalRunProjectionAdapter (planned)
Boundary: BusinessPortalFoundationFacade.projectEvalRuns(...) (planned)
```

## 2. Non-goals

```text
No new EvalSet business object yet (plan section 5.6 stays paused).
No mutation of AgentEvaluation state.
No automatic evolution proposal creation from eval failure.
No UI tab.
No generation API.
No cross-tenant leakage.
```

## 3. Inputs

```text
workspaceId
optional: agentId filter
optional: limit (default 50)
optional: minComposite, maxComposite filters
```

## 4. Outputs

```text
EvalRun read-only projection record:
{
  "workspaceId": "...",
  "evalRunId": "eval-run-<traceId|generated>",
  "agentId": "...",
  "agentVersion": "...",
  "task": "...",
  "scores": { "ACCURACY": ..., "SAFETY": ..., ... },
  "compositeScore": 0.8,
  "passed": true,
  "durationMs": 250,
  "tokens": 100,
  "estimatedCost": 0.01,
  "metadata": {
    "source": "foundation:agent-evaluation",
    "evalSuite": "...",
    "traceRef": "trace://...",
    "notes": "..."
  }
}
```

## 5. Required adapter contract

```text
BusinessEvalRunProjectionAdapter.fromEvalResult(workspaceId, EvalResult)
BusinessEvalRunProjectionAdapter.fromEvalSuite(workspaceId, EvalSuite)
```

Adapter must:

```text
preserve foundation traceRef when present
tag metadata.source = "foundation:agent-evaluation"
not call AgentEvaluation.compare() to mutate baselines
not assume a Business Portal EvalSet object exists
```

## 6. Facade and dashboard surface (deferred)

```text
BusinessPortalFoundationFacade.projectEvalRun(...)        // implemented
BusinessPortalFoundationFacade.projectEvalRuns(...)       // implemented
GET  /api/v1/business/foundation/eval-runs?workspaceId=...&agentId=...     // blocked: see section 6.1
POST /api/v1/business/foundation/eval-runs/preview        // blocked: see section 6.1
```

### 6.1 Foundation gap discovered (2026-06-17)

While preparing the dashboard preview endpoint we confirmed:

```text
Hermes foundation currently has no per-tenant EvalResultStore.
AgentEvaluation.EvalResult is a value object built ad hoc.
There is no list/query API for foundation eval results.
```

Implications:

```text
A by-reference eval-runs/preview endpoint cannot be honest right now: it would have to
accept an EvalResult payload in the request body, which violates the read-only/by-reference
guarantee defined in BUSINESS_PORTAL_FOUNDATION_READONLY_ENDPOINTS.md section 1.
```

Decision:

```text
Do not add eval-runs/preview HTTP endpoint until a foundation EvalResultStore (or equivalent
listing API) is in place.
The BusinessEvalRunProjectionAdapter remains usable from facade for in-process callers
that already have a List<EvalResult>, e.g. tests, smoke checks or future foundation code.
```

This is recorded in `docs/BUSINESS_PORTAL_FOUNDATION_ALIGNMENT_REVIEW.md` so the gap cannot
be silently skipped.

Both endpoints stay read-only. They cannot be merged before the alignment review’s
acceptance test cycle is satisfied.

## 7. Tests required before merge

```text
BusinessEvalRunProjectionAdapterTest
DashboardBusinessFoundationEvalRunsRouteTest
Architecture test allowlist update (if a new explicit adapter is added)
Cross-endpoint smoke test extension
```

## 8. PR checklist

```text
Follows BUSINESS_PORTAL_FOUNDATION_ADAPTERS.md section 6.
Updates BUSINESS_PORTAL_FOUNDATION_READONLY_ENDPOINTS.md catalog.
Updates BUSINESS_PORTAL_FOUNDATION_BASELINE.md acceptance test list.
Adds metadata.source = "foundation:agent-evaluation" to every projected record.
Refuses to apply when AgentEvaluation data is missing for the workspace.
```
