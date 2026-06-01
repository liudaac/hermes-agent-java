# Monitoring

Hermes Agent Java exposes tenant and system metrics in Prometheus text format through `MetricsCollector` and `TenantMetrics`.

## Tenant Metrics

Per-tenant metrics include:

- memory used / max / usage percent
- total and blocked network requests
- network requests per second
- active agents and sessions
- storage used and quota bytes
- file count
- active processes
- audit events in the last hour
- quota warning / exceeded flags
- tenant state

## Alert Metrics

The alert manager exports delivery metrics:

```text
hermes_alerts_fired_total
hermes_alerts_suppressed_total
hermes_alert_deliveries_succeeded_total
hermes_alert_deliveries_failed_total
hermes_alert_channel_deliveries_succeeded_total{channel="..."}
hermes_alert_channel_deliveries_failed_total{channel="..."}
```

These counters help monitor whether alerting itself is healthy.

## Alert Channels

Supported alert channels:

- Email (`EmailAlertChannel`)
- Webhook (`WebhookAlertChannel`)
  - DingTalk
  - Feishu / Lark
  - Slack
  - Discord / generic JSON webhook

Configuration is environment-variable based:

```bash
export ALERT_WEBHOOK_URL="https://..."
export ALERT_EMAIL_SENDER="bot@example.com"
export ALERT_EMAIL_PASSWORD="..."
export ALERT_EMAIL_RECIPIENT="ops@example.com"
export ALERT_SMTP_HOST="smtp.example.com"
export ALERT_SMTP_PORT="465"
export ALERT_EMAIL_SSL="true"
```

## Cooldown

Alert firing has a 5-minute cooldown per `tenant:type` key to prevent alert storms. Suppressed alerts are counted by `hermes_alerts_suppressed_total`.

## Suggested Prometheus Alerts

```yaml
groups:
  - name: hermes
    rules:
      - alert: HermesTenantMemoryHigh
        expr: hermes_tenant_memory_usage_percent > 0.8
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Hermes tenant memory usage high"

      - alert: HermesAlertDeliveryFailing
        expr: increase(hermes_alert_deliveries_failed_total[10m]) > 0
        for: 1m
        labels:
          severity: warning
        annotations:
          summary: "Hermes alert delivery failures detected"
```
