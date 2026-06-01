# Changelog

All notable changes to Hermes Agent Java will be documented in this file.

## [Unreleased]

### Added
- **Persistence System**
  - Session serialization with JSON support (`SessionSerializer`, `JsonSessionSerializer`)
  - Quota usage persistence with daily tracking (`TenantQuotaManager`)
  - File system repository implementation (`FileSystemTenantRepository`)
  - PostgreSQL repository implementation (`PostgresTenantRepository`)
  - Unified persistence layer (`TenantPersistenceManager`)
  
- **Trajectory & Learning**
  - Trajectory compression (`TrajectoryCompressor`)
  - Insight extraction from conversations (`InsightExtractor`)
  - Automatic skill pattern detection

- **Monitoring & Alerts**
  - Alert notification system with multiple channels (`AlertChannel`)
  - Email alert support (`EmailAlertChannel`)
  - Webhook alert support (`WebhookAlertChannel`) - DingTalk, Feishu, Slack
  - Automatic metric collection with data source integration

- **Tenant Context Improvements**
  - Unified `save()` method for all components
  - Auto-save mechanism (every 5 minutes)
  - Complete shutdown persistence chain

- **AI Agent Capabilities**
  - Auto-skill loading from configuration (`loadAutoSkills`)
  - Background conversation review (`spawnBackgroundReview`)
  - Memory extraction from conversations
  - Skill candidate detection and prompting

- **Gateway Service**
  - PID file support for service mode
  - Background start/stop operations

### Fixed
- Session serialization returning empty bytes
- Quota usage not persisting across restarts
- Missing data sources in TenantMetrics (6 TODOs)
- HTTP download implementation for skill registry
- Skill candidate prompting to users

### Dependencies
- Added `jackson-datatype-jsr310` for Java 8 date/time support
- Added `javax.mail` for email alerts

---

## [0.1.0] - 2026-06-01

### Initial Release
- Multi-tenant architecture with isolation
- Platform adapters: Telegram, Discord, Feishu
- Skill management system
- Memory management
- Quota enforcement
- Audit logging
- Prometheus metrics export
