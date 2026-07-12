/**
 * useSse — NOC convenience hook.
 *
 * Subscribes to the legacy business-event stream
 * (/api/v1/business/events/stream) so existing NOC pages (DLQ, SLA,
 * Workflow, Human-in-the-loop) keep receiving {type,workspaceId,payload,
 * timestamp}-shaped events.
 *
 * Thin wrapper around useEventSource from @hermes/ui.
 */

import { useEventSource } from "@hermes/ui";
import { createBusinessEventStream, type BusinessEvent } from "../lib/api/sse";

interface UseSseOptions {
  onEvent: (event: BusinessEvent) => void;
  onError?: (err: Event) => void;
  enabled?: boolean;
}

export function useSse({ onEvent, onError, enabled = true }: UseSseOptions) {
  return useEventSource({
    enabled,
    onError,
    factory: () => createBusinessEventStream(),
    onMessage: (data) => onEvent(data as BusinessEvent),
  });
}
