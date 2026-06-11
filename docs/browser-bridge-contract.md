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
