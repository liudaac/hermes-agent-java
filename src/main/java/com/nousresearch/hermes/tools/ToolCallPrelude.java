package com.nousresearch.hermes.tools;

import com.nousresearch.hermes.approval.ToolRisk;
import com.nousresearch.hermes.model.ModelClient;
import com.nousresearch.hermes.model.ModelMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Tool call prelude — pre-execution safety, explainability, and preview layer.
 *
 * <p>Runs before any tool is dispatched. Responsible for:</p>
 * <ul>
 *   <li>Generating human-readable intent explanations</li>
 *   <li>Determining if dry-run preview is warranted</li>
 *   <li>Providing graceful rejection with business-friendly messages</li>
 *   <li>Detecting low-confidence or out-of-policy calls</li>
 * </ul>
 */
public class ToolCallPrelude {

    private static final Logger logger = LoggerFactory.getLogger(ToolCallPrelude.class);

    private final ModelClient modelClient;
    private final Set<String> dryRunRecommendedTools;

    public ToolCallPrelude(ModelClient modelClient) {
        this.modelClient = modelClient;
        this.dryRunRecommendedTools = Set.of(
            "write_file", "file_write", "execute_command", "terminal",
            "execute_bash", "execute_python", "memory_write", "send_email",
            "update_order", "refund", "cancel_shipment"
        );
    }

    /**
     * Analyze a tool call before execution.
     *
     * @param toolName   the tool being called
     * @param args       the arguments
     * @param context    execution context (task description, current step, etc.)
     * @param allowedTools set of tools this agent is permitted to use
     * @return prelude result with explain/reject/dry-run guidance
     */
    public Result analyze(String toolName, Map<String, Object> args,
                          ExecutionContext context, Set<String> allowedTools) {
        // 1. Permission boundary — hard reject
        if (allowedTools != null && !allowedTools.isEmpty() && !allowedTools.contains(toolName)) {
            return Result.reject(
                "This agent is not authorized to use '" + toolName + "'. " +
                "Allowed tools: " + String.join(", ", allowedTools) + ". " +
                "If this action is needed, request reassignment to an agent with the appropriate permissions."
            );
        }

        // 2. Generate intent explanation
        String explanation = explainIntent(toolName, args, context);

        // 3. Determine if dry-run is recommended
        boolean dryRun = isDryRunRecommended(toolName, args);

        // 4. Generate preview if applicable
        String preview = dryRun ? generatePreview(toolName, args) : null;

        // 5. Confidence heuristics — soft warnings
        List<String> warnings = new ArrayList<>();
        if (containsSensitiveArgs(args)) {
            warnings.add("Arguments contain potentially sensitive data (paths, IDs, amounts).");
        }
        if (isDestructiveTool(toolName)) {
            warnings.add("This is a destructive/modifying operation.");
        }

        return Result.proceed(explanation, dryRun, preview, warnings);
    }

    /**
     * Generate a natural language description of what the tool call will do.
     */
    public String explainIntent(String toolName, Map<String, Object> args,
                                 ExecutionContext context) {
        // Fast path: known tools with templates
        String fast = fastExplain(toolName, args);
        if (fast != null) return fast;

        // Fallback: LLM-generated explanation (only if modelClient available)
        if (modelClient != null) {
            try {
                return llmExplain(toolName, args, context);
            } catch (Exception e) {
                logger.debug("LLM explain failed, using fallback: {}", e.getMessage());
            }
        }

        return "Preparing to call '" + toolName + "' with provided arguments.";
    }

    /**
     * Provide a graceful, business-friendly rejection message.
     */
    public static String gracefulReject(String reason, String suggestion) {
        return "[WITHHELD] " + reason + "\n\nSuggestion: " + suggestion;
    }

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    private String fastExplain(String toolName, Map<String, Object> args) {
        return switch (toolName) {
            case "read_file", "file_read" ->
                "Reading file: " + args.get("path");
            case "write_file", "file_write" ->
                "Writing to file: " + args.get("path") + " (" + String.valueOf(args.get("content")).length() + " chars)";
            case "list_directory", "file_list" ->
                "Listing directory: " + args.get("path");
            case "search_files" ->
                "Searching for '" + args.get("query") + "' in " + args.get("path");
            case "execute_command", "terminal", "execute_bash" ->
                "Executing shell command: " + truncate(String.valueOf(args.get("command")), 80);
            case "execute_python" ->
                "Running Python code: " + truncate(String.valueOf(args.get("code")), 80);
            case "memory_read", "memory_search" ->
                "Searching memory for: " + args.get("query");
            case "memory_write" ->
                "Writing to memory: " + truncate(String.valueOf(args.get("content")), 80);
            case "find_teammate" ->
                "Finding teammate for task: " + args.get("task");
            case "delegate_task" ->
                "Delegating task '" + args.get("subtask") + "' to " + args.get("targetAgentId");
            case "browser_bridge" ->
                "Opening browser to: " + args.get("url");
            default -> null;
        };
    }

    private String llmExplain(String toolName, Map<String, Object> args,
                               ExecutionContext context) {
        StringBuilder user = new StringBuilder();
        user.append("Tool: ").append(toolName).append("\n");
        user.append("Arguments: ").append(args).append("\n");
        if (context.taskDescription != null) {
            user.append("Task context: ").append(context.taskDescription).append("\n");
        }
        user.append("\nDescribe in one sentence what this tool call will do.");

        var response = modelClient.chatCompletion(List.of(
            ModelMessage.system("You are a tool call explainer. Describe what a tool call will do in one natural language sentence. Be concise."),
            ModelMessage.user(user.toString())
        ), null, false);

        if (response != null && response.isSuccess()) {
            return response.getContent().trim();
        }
        return null;
    }

    private boolean isDryRunRecommended(String toolName, Map<String, Object> args) {
        if (dryRunRecommendedTools.contains(toolName)) return true;
        // Heuristic: write operations with large content
        if ((toolName.contains("write") || toolName.contains("update")) && args.containsKey("content")) {
            String content = String.valueOf(args.get("content"));
            if (content.length() > 500) return true;
        }
        return false;
    }

    private String generatePreview(String toolName, Map<String, Object> args) {
        return switch (toolName) {
            case "write_file", "file_write" -> {
                String content = String.valueOf(args.get("content"));
                yield "Preview (first 300 chars):\n" + truncate(content, 300);
            }
            case "execute_command", "terminal", "execute_bash" ->
                "Command preview: " + truncate(String.valueOf(args.get("command")), 200);
            case "memory_write" ->
                "Memory entry preview: " + truncate(String.valueOf(args.get("content")), 200);
            default -> "Preview not available for this tool.";
        };
    }

    private boolean containsSensitiveArgs(Map<String, Object> args) {
        if (args == null) return false;
        String flat = args.toString().toLowerCase();
        return flat.contains("password") || flat.contains("secret") || flat.contains("token")
            || flat.contains("api_key") || flat.contains("private_key");
    }

    private boolean isDestructiveTool(String toolName) {
        return toolName.contains("write") || toolName.contains("delete") || toolName.contains("remove")
            || toolName.contains("execute") || toolName.contains("update") || toolName.contains("cancel");
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }

    // ------------------------------------------------------------------
    // DTOs
    // ------------------------------------------------------------------

    public static class ExecutionContext {
        public String taskDescription;
        public String currentSubtask;
        public String agentRole;

        public ExecutionContext(String taskDescription, String currentSubtask, String agentRole) {
            this.taskDescription = taskDescription;
            this.currentSubtask = currentSubtask;
            this.agentRole = agentRole;
        }
    }

    public static class Result {
        public final boolean allowed;
        public final String explanation;
        public final boolean dryRunRecommended;
        public final String preview;
        public final List<String> warnings;
        public final String rejectReason;

        private Result(boolean allowed, String explanation, boolean dryRunRecommended,
                       String preview, List<String> warnings, String rejectReason) {
            this.allowed = allowed;
            this.explanation = explanation;
            this.dryRunRecommended = dryRunRecommended;
            this.preview = preview;
            this.warnings = warnings;
            this.rejectReason = rejectReason;
        }

        public static Result proceed(String explanation, boolean dryRun, String preview,
                                      List<String> warnings) {
            return new Result(true, explanation, dryRun, preview, warnings, null);
        }

        public static Result reject(String reason) {
            return new Result(false, null, false, null, List.of(), reason);
        }

        public boolean isRejected() {
            return !allowed;
        }
    }
}
