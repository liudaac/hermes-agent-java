package com.nousresearch.hermes.plugin.hook;

/**
 * Lifecycle hook types that plugins may register callbacks for.
 * Mirrors Python VALID_HOOKS.
 */
public enum HookType {
    PRE_TOOL_CALL,
    POST_TOOL_CALL,
    TRANSFORM_TERMINAL_OUTPUT,
    TRANSFORM_TOOL_RESULT,
    TRANSFORM_LLM_OUTPUT,
    PRE_LLM_CALL,
    POST_LLM_CALL,
    PRE_API_REQUEST,
    POST_API_REQUEST,
    ON_SESSION_START,
    ON_SESSION_END,
    ON_SESSION_FINALIZE,
    ON_SESSION_RESET,
    SUBAGENT_STOP,
    PRE_GATEWAY_DISPATCH,
    PRE_APPROVAL_REQUEST,
    POST_APPROVAL_RESPONSE
}
