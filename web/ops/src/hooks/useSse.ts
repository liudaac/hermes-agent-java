import { useEffect, useRef, useCallback } from "react";
import { openBusinessEventStream, type BusinessEvent } from "@/lib/api";

interface UseSseOptions {
  onEvent: (event: BusinessEvent) => void;
  onError?: (err: Event) => void;
  enabled?: boolean;
}

export function useSse({ onEvent, onError, enabled = true }: UseSseOptions) {
  const esRef = useRef<EventSource | null>(null);

  const connect = useCallback(() => {
    if (esRef.current) return;
    const es = openBusinessEventStream(
      (evt) => onEvent(evt),
      (err) => onError?.(err),
    );
    esRef.current = es;
  }, [onEvent, onError]);

  const disconnect = useCallback(() => {
    if (esRef.current) {
      esRef.current.close();
      esRef.current = null;
    }
  }, []);

  useEffect(() => {
    if (enabled) {
      connect();
    } else {
      disconnect();
    }
    return () => disconnect();
  }, [enabled, connect, disconnect]);

  return { connect, disconnect };
}
