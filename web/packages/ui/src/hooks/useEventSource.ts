/**
 * useEventSource — generic React hook that owns an EventSource lifecycle.
 *
 * The caller supplies a `factory` that creates a *bare* EventSource from a
 * URL. The hook wires onMessage / on() handlers via bindEventSource and
 * tears the connection down on unmount / disable / dep change. The
 * factory is NOT expected to attach its own listeners.
 *
 * For advanced cases (factory needs to attach listeners itself), return
 * the EventSource with listeners attached and omit onMessage/on; the hook
 * will skip auto-binding.
 */

import { useEffect, useRef, useCallback } from "react";
import { bindEventSource } from "../lib/sse";

export interface UseEventSourceOptions {
  /** Factory returning a NEW bare EventSource. Called each (re)connect. */
  factory: () => EventSource;

  /** Called for every unnamed (default "message") event. */
  onMessage?: (data: unknown) => void;
  /** Named-event handlers, e.g. { suggestion: fn, error: fn }. */
  on?: Record<string, (data: unknown) => void>;
  /** Called when the EventSource fires an error event. */
  onError?: (err: Event) => void;
  /** Set false to disconnect manually. Default true. */
  enabled?: boolean;
}

export function useEventSource(opts: UseEventSourceOptions) {
  const { factory, onMessage, on, onError, enabled = true } = opts;

  const esRef = useRef<EventSource | null>(null);

  // Keep latest handler refs in refs so the useEffect doesn't reconnect
  // on every handler identity change.
  const onMessageRef = useRef(onMessage);
  const onNamedRef = useRef(on);
  const onErrorRef = useRef(onError);
  onMessageRef.current = onMessage;
  onNamedRef.current = on;
  onErrorRef.current = onError;

  const connect = useCallback(() => {
    if (esRef.current) return;
    const es = factory();
    esRef.current = es;

    const msg = onMessageRef.current;
    const named = onNamedRef.current;
    if (msg || named) {
      const merged: Record<string, (data: unknown) => void> = {};
      if (msg) merged.message = msg;
      if (named) {
        for (const [name, h] of Object.entries(named)) merged[name] = h;
      }
      bindEventSource(es, merged);
    }
    if (onErrorRef.current) {
      es.onerror = (e) => onErrorRef.current?.(e);
    }
  }, [factory]);

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
    // Intentionally depend only on enabled + stable callbacks so we don't
    // churn the connection on every render. factory should be stable.
  }, [enabled, connect, disconnect]);

  return { connect, disconnect };
}
