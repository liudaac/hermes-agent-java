package com.nousresearch.hermes.tools.impl;

import com.nousresearch.hermes.tools.ToolRegistry;
import com.nousresearch.hermes.tools.ToolEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * AskUserTool - lets the LLM request structured user interaction.
 *
 * <p>Instead of writing "please choose 1 or 2" in plain text, the LLM calls
 * this tool with a structured interaction request. The dispatcher intercepts
 * it and returns a pending interaction that ChatService surfaces to the
 * frontend as an interactive card.</p>
 *
 * <h2>Interaction types</h2>
 * <ul>
 *   <li><b>choice</b> - present options, user picks one</li>
 *   <li><b>input</b> - ask for text input with optional placeholder</li>
 *   <li><b>confirm</b> - simple yes/no confirmation</li>
 * </ul>
 *
 * <p>The tool always "fails" with a special error containing the interaction
 * spec. TenantAwareToolDispatcher catches this and throws
 * ToolApprovalRequiredException so ChatService can render the card.</p>
 */
public class AskUserTool {

    private static final Logger logger = LoggerFactory.getLogger(AskUserTool.class);

    public static void register(ToolRegistry registry) {
        registry.register(new ToolEntry.Builder()
            .name("ask_user")
            .toolset("interaction")
            .schema(Map.of(
                "description", "Ask the user a question or present choices. Use this when you need " +
                    "user input to proceed (e.g. choose between options, confirm a non-destructive " +
                    "action, or get missing information). Do NOT use this for dangerous commands - " +
                    "those are handled automatically by the approval system.",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "interaction_type", Map.of(
                            "type", "string",
                            "enum", List.of("choice", "input", "confirm"),
                            "description", "Type of interaction: choice (pick from options), input (text), confirm (yes/no)"
                        ),
                        "prompt", Map.of(
                            "type", "string",
                            "description", "The question or prompt to show the user"
                        ),
                        "options", Map.of(
                            "type", "array",
                            "items", Map.of("type", "string"),
                            "description", "For choice type: list of options to pick from"
                        ),
                        "placeholder", Map.of(
                            "type", "string",
                            "description", "For input type: placeholder text for the input field"
                        ),
                        "default_value", Map.of(
                            "type", "string",
                            "description", "Optional default value for input type"
                        )
                    ),
                    "required", List.of("interaction_type", "prompt")
                )
            ))
            .handler(AskUserTool::handle)
            .emoji("❓")
            .risk(com.nousresearch.hermes.approval.ToolRisk.LOW)
            .requiresApproval(false)
            .build());

        logger.info("Registered ask_user tool");
    }

    /**
     * Handle the ask_user tool call.
     * Always returns a "pending" result - the dispatcher will intercept this
     * and throw ToolApprovalRequiredException so ChatService can show the card.
     */
    private static String handle(Map<String, Object> args) {
        String type = (String) args.get("interaction_type");
        String prompt = (String) args.get("prompt");

        if (prompt == null || prompt.isBlank()) {
            return ToolRegistry.toolError("prompt is required");
        }

        // Build interaction spec as JSON
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("interaction_type", type != null ? type : "input");
        spec.put("prompt", prompt);

        if ("choice".equals(type)) {
            Object options = args.get("options");
            if (options instanceof List<?> list) {
                spec.put("options", list);
            } else {
                return ToolRegistry.toolError("options is required for choice type");
            }
        }

        if (args.containsKey("placeholder")) {
            spec.put("placeholder", args.get("placeholder"));
        }
        if (args.containsKey("default_value")) {
            spec.put("default_value", args.get("default_value"));
        }

        // Return a special marker that the dispatcher will catch
        return ToolRegistry.toolError("__ASK_USER__:" +
            com.alibaba.fastjson2.JSON.toJSONString(spec));
    }
}
