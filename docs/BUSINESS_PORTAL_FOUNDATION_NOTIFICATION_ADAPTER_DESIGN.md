# Business Notification Adapter — Contract Design

Status: design-only (2026-06-17)

This document scopes the future `BusinessNotificationAdapter` between Business Portal
artifacts (approval cards, run projections, evolution proposals) and the Hermes gateway
delivery layer.

It exists so that no future feature directly calls Feishu / Telegram / Discord / IRC /
Signal channel APIs from Business Portal classes.

It satisfies the gap noted in `docs/BUSINESS_PORTAL_FOUNDATION_ALIGNMENT_REVIEW.md` section 4.5.

---

## 1. Goal

Give Business Portal a single, governed delivery path for notifications, while keeping the
Hermes gateway and platform adapters as the source of truth for channel transport.

```text
Source of truth: GatewayServer / GatewayServerV2 / PlatformAdapter implementations
Business projection input: BusinessApprovalRecord, BusinessRunRecord, EvolutionProposalRecord
Adapter: BusinessNotificationAdapter (planned)
Boundary: BusinessPortalFoundationFacade.notify*(...) (planned, governed)
```

## 2. Non-goals

```text
No direct Feishu/Telegram/Discord/IRC/Signal client code in Business Portal.
No new channel selection logic outside gateway PlatformAdapter.
No new authentication/credential code in Business Portal.
No silent retry/backoff loops bypassing gateway behavior.
No autonomous escalation that does not go through approval/delegated boundaries.
No UI tab.
```

## 3. Allowed inputs

```text
BusinessApprovalRecord -> notification payload
BusinessRunRecord (foundation:* source) -> optional notification payload
EvolutionProposalRecord -> approval card notification payload
```

## 4. Required adapter contract

```text
BusinessNotificationAdapter.toApprovalCardPayload(BusinessApprovalRecord)
BusinessNotificationAdapter.toRunStoryPayload(BusinessRunRecord)
BusinessNotificationAdapter.toEvolutionProposalPayload(EvolutionProposalRecord)

BusinessNotificationAdapter.deliver(NotificationEnvelope, GatewayServer/PlatformAdapter)
```

`NotificationEnvelope` skeleton:

```text
{
  "workspaceId": "...",
  "channel": "feishu" | "telegram" | "discord" | "irc" | "signal",
  "recipient": { "userId": "...", "groupId": "...", "channelId": "..." },
  "kind": "approval-card" | "run-story" | "evolution-proposal",
  "payload": { ... },
  "metadata": {
    "source": "foundation:business-notification",
    "approvalId": "...",
    "runRef": "...",
    "proposalId": "..."
  }
}
```

## 5. Delivery rules

```text
Always go through GatewayServer / GatewayServerV2 PlatformAdapter.
Always include foundation refs (approval:// or business approval id, intent://, trace://, evolution-proposal://).
Always include metadata.source = "foundation:business-notification".
Refuse delivery if recipient/channel is missing.
Refuse delivery if the underlying record is marked manual/demo/smoke (BusinessRunRecord.metadata.source).
Refuse delivery for high-risk events without an active ApprovalRequest.
```

## 6. Facade extension (deferred)

```text
BusinessPortalFoundationFacade.notifyApprovalCard(...)
BusinessPortalFoundationFacade.notifyRunStory(...)
BusinessPortalFoundationFacade.notifyEvolutionProposal(...)
```

These remain off until the adapter is implemented and tested.

## 7. Dashboard endpoints (deferred)

Default policy: do not expose direct send endpoints; notifications should be triggered by
foundation events or governed actions, not by HTTP body.

If preview is needed:

```text
POST /api/v1/business/foundation/notifications/preview
```

This returns the formatted payload only and does not deliver.

## 8. Forbidden first steps

```text
Adding feishu/telegram/discord/irc/signal SDK calls inside Business Portal modules.
Implementing channel credential management in Business Portal.
Sending high-risk notifications without an ApprovalRequest in flight.
Sending notifications for manual/demo BusinessRunRecord entries.
```

## 9. Tests required before implementation

```text
BusinessNotificationAdapterTest
DashboardBusinessFoundationNotificationsPreviewRouteTest (if preview endpoint is added)
Architecture test allowlist update for the new adapter.
Cross-endpoint smoke test extension.
```

## 10. Open questions to resolve before implementation

```text
Q1: Recipient resolution policy — workspace-default vs per-record explicit recipient?
Q2: Failure handling — silent fail-open vs governed retry vs inbox?
Q3: How does this integrate with existing Approval system delivery already wired in foundation?
Q4: How does this play with quiet hours / approval session windows?
```

These must be answered in subsequent design iterations before any code change.
