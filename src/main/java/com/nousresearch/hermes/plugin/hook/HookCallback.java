package com.nousresearch.hermes.plugin.hook;

import java.util.Map;

/**
 * Callback interface for lifecycle hooks.
 */
@FunctionalInterface
public interface HookCallback {
    /**
     * Invoke the hook callback.
     *
     * @param context context map with hook-specific arguments
     * @return non-null value to contribute to hook results, or null for no-op
     */
    Object invoke(Map<String, Object> context);
}
