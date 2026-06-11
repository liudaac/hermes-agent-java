# Browser Bridge Contract v1

Hermes uses a provider-neutral `BrowserBridge` abstraction for real-browser automation. HTTP-backed providers such as Kimi WebBridge or OpenClaw Browser Relay should expose this minimal daemon contract.

## Runtime configuration

System properties or env vars:

| Property | Env | Default |
| --- | --- | --- |
| `hermes.browser.bridge.provider` | `HERMES_BROWSER_BRIDGE_PROVIDER` | `mock` |
| `hermes.browser.bridge.endpoint` | `HERMES_BROWSER_BRIDGE_ENDPOINT` | provider-specific |
| `hermes.browser.bridge.timeoutMs` | `HERMES_BROWSER_BRIDGE_TIMEOUT_MS` | `10000` |
| `hermes.browser.bridge.actionPath` | `HERMES_BROWSER_BRIDGE_ACTION_PATH` | `/actions` |
| `hermes.browser.bridge.healthPath` | `HERMES_BROWSER_BRIDGE_HEALTH_PATH` | `/health` |
| `hermes.browser.bridge.capabilitiesPath` | `HERMES_BROWSER_BRIDGE_CAPABILITIES_PATH` | `/capabilities` |

Provider defaults:

- `kimi`: `http://127.0.0.1:17361`
- `openclaw`: `http://127.0.0.1:14511`

## POST `/actions`

Request:

```json
{
  "protocol": "hermes.browser.v1",
  "action": "open",
  "session_id": "optional-session-id",
  "url": "https://example.com",
  "target": "Search box",
  "text": "hello",
  "instruction": "extract the page title",
  "actor": "agent-or-dashboard",
  "reason": "why the action is needed"
}
```

Response:

```json
{
  "ok": true,
  "protocol": "hermes.browser.v1",
  "session_id": "browser-session-id",
  "url": "https://example.com",
  "title": "Example",
  "content": "optional extracted content",
  "message": "opened",
  "actions": [],
  "meta": {
    "engine": "provider-name"
  }
}
```

Error response:

```json
{
  "ok": false,
  "error_code": "selector_not_found",
  "message": "Could not find target",
  "meta": {
    "provider": "kimi-webbridge"
  }
}
```

## GET `/health`

Response should be JSON when possible:

```json
{
  "ok": true,
  "status": "ready",
  "provider": "kimi-webbridge",
  "version": "1.0.0"
}
```

Plain text is accepted and stored as `content`.

## GET `/capabilities`

Response:

```json
{
  "ok": true,
  "provider": "kimi-webbridge",
  "protocol": "hermes.browser.v1",
  "actions": ["open", "observe", "click", "type", "extract", "scroll", "press", "submit", "close"],
  "features": ["real-browser", "cookies", "health", "capabilities"]
}
```

## Error classification

Hermes normalizes common provider failures:

| Code | Meaning |
| --- | --- |
| `endpoint_missing` | Provider endpoint is not configured |
| `daemon_unavailable` | Daemon cannot be reached |
| `bridge_unavailable` | Unexpected bridge/transport failure |
| `auth_required` | HTTP 401/403 |
| `endpoint_not_found` | Non-action endpoint returned 404 |
| `selector_not_found` | Action endpoint returned 404 |
| `session_missing` | HTTP 409 |
| `navigation_timeout` | HTTP 408/504 or timeout exception |
| `provider_http_error` | Other non-2xx provider response |
| `provider_error` | Provider returned `ok=false` without a code |
| `provider_unknown` | Unknown Hermes provider id |

Sensitive actions are still governed by `BrowserBridgePolicy` and may require Browser Approval before execution.

## Contract harness

Hermes includes a small contract harness for provider authors:

- `BrowserBridgeMockDaemon` — starts a local daemon that implements this contract.
- `BrowserBridgeContractVerifier` — runs compatibility checks against any endpoint.
- `BrowserBridgeContractCli` — CLI-style entrypoint for external daemon verification.

Verifier checks:

1. `/capabilities` returns `protocol=hermes.browser.v1` and includes `open`.
2. `/health` succeeds.
3. `open` action succeeds and returns `session_id`.
4. `observe` succeeds for that session.
5. Missing-session action returns a classified error.

CLI usage from a built classpath:

```bash
java com.nousresearch.hermes.browser.contract.BrowserBridgeContractCli http://127.0.0.1:17361 kimi
```

Exit code:

- `0` compatible
- `1` contract failed
- `2` invalid CLI usage

## Provider probe / autodetect

`BrowserBridgeProviderProbe` tries several known path layouts against an endpoint and runs the verifier for each candidate. It recommends the highest-scoring configuration.

Built-in candidates currently include:

- `hermes-standard`: `/actions`, `/health`, `/capabilities`
- `hermes-standard-openclaw`: same paths with provider `openclaw`
- `versioned-v1`: `/v1/actions`, `/v1/health`, `/v1/capabilities`
- `versioned-v1-singular-action`: `/v1/action`, `/v1/health`, `/v1/capabilities`
- `api-browser`: `/api/browser/actions`, `/api/browser/health`, `/api/browser/capabilities`
- `api-bridge`: `/api/bridge/actions`, `/api/bridge/health`, `/api/bridge/capabilities`

Scoring weights:

- capabilities: 30
- health: 20
- open: 25
- observe: 15
- missing-session error classification: 10

Control Center exposes this as a **Probe** action on Browser Bridge Controls. Probe results include `recommended_config`, candidate list, score, and the best contract report.

### Apply recommendation

Control Center can apply the best probe result to a tenant via:

```text
POST /api/org/control/browser/{tenantId}/probe/apply
```

The endpoint reads the latest `probe_report.recommended_config`, configures the tenant `BrowserBridge`, runs `healthCheck()`, then runs a contract test. The response includes:

- `applied_config`
- `health`
- `contract_report`

This is intentionally tenant-scoped runtime configuration, not a global deployment config mutation.

## Tenant provider config persistence

Applied BrowserBridge provider configuration is persisted per tenant at:

```text
{tenantDir}/state/browser-bridge-config.json
```

Fields:

```json
{
  "provider": "kimi",
  "endpoint": "http://127.0.0.1:17361",
  "timeout_ms": 10000,
  "action_path": "/actions",
  "health_path": "/health",
  "capabilities_path": "/capabilities"
}
```

`TenantContext.getBrowserBridge()` loads this config lazily and restores the provider after restart. The persisted config is tenant-scoped and does not mutate process-wide defaults or environment variables.

### Config management actions

Control Center also exposes tenant-scoped config management:

```text
GET  /api/org/control/browser/{tenantId}/config
POST /api/org/control/browser/{tenantId}/reset
POST /api/org/control/browser/{tenantId}/clear-config
```

- `config` returns the persisted config and config file path.
- `reset` persists a mock provider config and clears the latest contract report.
- `clear-config` deletes the persisted config file and falls back to mock for the current runtime.

The UI exposes these as **Export config**, **Reset mock**, and **Clear config** actions.
