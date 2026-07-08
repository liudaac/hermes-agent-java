package com.nousresearch.hermes.dashboard.jarvis;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.model.ChatCompletionResponse;
import com.nousresearch.hermes.model.ModelClient;
import com.nousresearch.hermes.model.ModelMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

/**
 * IntentRouter — classify a user message into one of the three product
 * spaces (portal / ops / noc) or "cross" (spans multiple spaces).
 *
 * MVP approach: a single small LLM call with a system prompt that
 * describes the four intents. The LLM is asked to reply with a strict
 * JSON object so we can parse reliably. On any failure (LLM error,
 * parse error, unknown intent), fall back to "cross" with confidence
 * 0 so the front-end can take the safe "ask the user" path.
 *
 * Heuristic short-circuits live in the front-end {@code
 * useIntentRouter} hook — the backend is only consulted when the
 * heuristic says "cross" / "idle".
 *
 * This is part of the cross-space dialogue shell (design.md §11.3).
 */
public class IntentRouter {
    private static final Logger log = LoggerFactory.getLogger(IntentRouter.class);

    private static final Set<String> VALID_INTENTS = Set.of("portal", "ops", "noc", "cross");

    private static final String SYSTEM_PROMPT = """
You are the intent classifier for Hermes — a cross-space dialogue shell that
sits on top of three product surfaces. The user is asking a question, and
you must decide which product API should answer it.

The three products are:

- portal: business user surface — "my digital team", teams, templates,
  approvals, runs, my-tasks, suggestions. Anything a business user does.
- ops:    platform console — tenants, skills, sessions, logs, cron,
  analytics, config, env, playground, compare.
- noc:    org control center — traces, workflows, DLQ, SLA, human-in-the-loop,
  alerts, governance, audit, evals.

If the question clearly belongs to one product, choose that product.
If the question is ambiguous or clearly needs information from multiple
products, choose "cross".

Reply with a single JSON object and nothing else. Use this exact shape:

{"intent": "portal|ops|noc|cross", "confidence": 0.0-1.0, "reasoning": "<= 20 words"}
""";

    private final ModelClient modelClient;

    public IntentRouter(ModelClient modelClient) {
        this.modelClient = modelClient;
    }

    /**
     * Classify a free-form user input. Never throws — returns a fallback
     * result on any error.
     */
    public IntentResult route(String input) {
        if (input == null || input.isBlank()) {
            return IntentResult.fallback("cross");
        }

        List<ModelMessage> messages = List.of(
            ModelMessage.system(SYSTEM_PROMPT),
            ModelMessage.user("用户输入：" + input.trim())
        );

        ChatCompletionResponse resp;
        try {
            resp = modelClient.chatCompletion(messages, null, false);
        } catch (Exception e) {
            log.warn("Intent classification LLM call failed: {}", e.getMessage());
            return IntentResult.fallback("cross");
        }

        if (resp == null) {
            return IntentResult.fallback("cross");
        }

        String content = resp.getContent();
        if (content == null || content.isBlank()) {
            return IntentResult.fallback("cross");
        }

        try {
            return parse(content);
        } catch (Exception e) {
            log.warn("Intent classification parse failed: {} (content={})", e.getMessage(), content);
            return IntentResult.fallback("cross");
        }
    }

    /**
     * Extract the first JSON object from the LLM's reply and parse it.
     * The model sometimes wraps JSON in markdown fences or prose — we
     * tolerate that by scanning for the first '{' and matching the
     * matching '}'.
     */
    private IntentResult parse(String content) {
        String json = extractFirstJsonObject(content);
        JSONObject obj = JSON.parseObject(json);
        String intent = obj.getString("intent");
        if (intent == null) return IntentResult.fallback("cross");
        intent = intent.toLowerCase().trim();
        if (!VALID_INTENTS.contains(intent)) {
            return IntentResult.fallback("cross");
        }
        double confidence = 0.5;
        Double c = obj.getDouble("confidence");
        if (c != null) {
            confidence = c;
            if (Double.isNaN(confidence) || confidence < 0) confidence = 0;
            if (confidence > 1) confidence = 1;
        }
        String reasoning = obj.getString("reasoning");
        return new IntentResult(intent, confidence, "prompt", reasoning);
    }

    private static String extractFirstJsonObject(String content) {
        int first = content.indexOf('{');
        if (first < 0) {
            throw new IllegalArgumentException("No JSON object in: " + content);
        }
        int depth = 0;
        for (int i = first; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return content.substring(first, i + 1);
                }
            }
        }
        throw new IllegalArgumentException("Unterminated JSON object in: " + content);
    }

    /**
     * Result of a (single) intent classification. Mirrors the front-end
     * type `IntentResult` in {@code web/packages/jarvis/src/api/jarvisApi.ts}.
     */
    public static final class IntentResult {
        private final String intent;
        private final double confidence;
        private final String source;
        private final String reasoning;

        public IntentResult(String intent, double confidence, String source, String reasoning) {
            this.intent = intent;
            this.confidence = confidence;
            this.source = source;
            this.reasoning = reasoning;
        }

        public static IntentResult fallback(String intent) {
            return new IntentResult(intent, 0.0, "fallback", null);
        }

        public String getIntent() { return intent; }
        public double getConfidence() { return confidence; }
        public String getSource() { return source; }
        public String getReasoning() { return reasoning; }
    }
}
