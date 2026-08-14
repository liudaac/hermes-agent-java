package com.nousresearch.hermes.harness.session.library;

import com.nousresearch.hermes.gateway.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Extracts structured step summaries from session messages.
 *
 * <p>Parses the session's message history and tool call records to build
 * a list of {@link SessionAsset.StepSummary} entries that can be injected
 * as a reference flow into new sessions.</p>
 */
public class SessionStepExtractor {

    private static final Logger logger = LoggerFactory.getLogger(SessionStepExtractor.class);

    /**
     * Extract steps from a session.
     *
     * @param session the session manager's Session object
     * @return ordered list of step summaries
     */
    public List<SessionAsset.StepSummary> extract(SessionManager.Session session) {
        if (session == null) return List.of();

        List<SessionAsset.StepSummary> steps = new ArrayList<>();
        int index = 0;

        // Extract from tool call records (primary source)
        for (var tc : session.toolCalls) {
            String action = tc.tool();
            String result = tc.ok() ? "success" : "failed";
            boolean keyStep = isKeyTool(tc.tool());
            steps.add(new SessionAsset.StepSummary(
                    index++, action, tc.tool(), result, keyStep, tc.timestamp()
            ));
        }

        // If no tool calls, extract from messages (fallback)
        if (steps.isEmpty()) {
            for (var msg : session.messages) {
                if ("assistant".equals(msg.role())) {
                    String content = msg.content();
                    // Truncate to first 200 chars as the action description
                    String action = content.length() > 200
                            ? content.substring(0, 200) + "..."
                            : content;
                    steps.add(new SessionAsset.StepSummary(
                            index++, action, null, null, false, msg.timestamp()
                    ));
                }
            }
        }

        logger.debug("Extracted {} steps from session {}", steps.size(), session.id);
        return steps;
    }

    /**
     * Build a reference context string from steps, for injection into new sessions.
     *
     * @param title   the session title
     * @param steps   the structured steps
     * @return formatted reference context
     */
    public String buildReferenceContext(String title, List<SessionAsset.StepSummary> steps) {
        if (steps == null || steps.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("[参考流程: \"").append(title != null ? title : "历史会话").append("\"]\n");
        for (var step : steps) {
            sb.append(step.index() + 1).append(". ");
            sb.append(step.action());
            if (step.toolUsed() != null && !step.toolUsed().isBlank()) {
                sb.append(" -> tool: ").append(step.toolUsed());
            }
            if (step.result() != null && !step.result().isBlank()) {
                sb.append(" -> 结果: ").append(step.result());
            }
            if (step.keyStep()) {
                sb.append(" ⭐");
            }
            sb.append("\n");
        }
        sb.append("\n[注意] 请参考上述流程执行本次任务，根据实际情况调整。");
        return sb.toString();
    }

    /**
     * Generate a concise title from the first user message.
     */
    public String generateTitle(SessionManager.Session session) {
        if (session == null || session.messages == null) return null;
        for (var msg : session.messages) {
            if ("user".equals(msg.role()) && msg.content() != null && !msg.content().isBlank()) {
                String content = msg.content().trim();
                // Use first 60 chars as title
                return content.length() > 60 ? content.substring(0, 60) + "..." : content;
            }
        }
        return null;
    }

    /**
     * Generate a simple summary from the session messages.
     * This is a heuristic fallback; production should use LLM SummaryFunction.
     */
    public String generateSummary(SessionManager.Session session) {
        if (session == null || session.messages == null) return null;
        int userMsgs = 0;
        int assistantMsgs = 0;
        int toolCalls = session.toolCalls != null ? session.toolCalls.size() : 0;
        Set<String> toolsUsed = new HashSet<>();

        for (var msg : session.messages) {
            if ("user".equals(msg.role())) userMsgs++;
            else if ("assistant".equals(msg.role())) assistantMsgs++;
        }
        if (session.toolCalls != null) {
            for (var tc : session.toolCalls) {
                toolsUsed.add(tc.tool());
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("会话包含 ").append(userMsgs).append(" 条用户消息, ")
          .append(assistantMsgs).append(" 条回复");
        if (toolCalls > 0) {
            sb.append(", 使用了 ").append(toolCalls).append(" 次工具调用 (");
            sb.append(String.join(", ", toolsUsed));
            sb.append(")");
        }
        sb.append("。");
        return sb.toString();
    }

    private boolean isKeyTool(String toolName) {
        if (toolName == null) return false;
        String lower = toolName.toLowerCase();
        return lower.contains("exec") || lower.contains("terminal") || lower.contains("write")
                || lower.contains("delete") || lower.contains("deploy") || lower.contains("publish")
                || lower.contains("send");
    }
}
