package com.nousresearch.hermes.dashboard.jarvis;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.model.ChatCompletionResponse;
import com.nousresearch.hermes.model.ModelClient;
import com.nousresearch.hermes.model.ModelMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

/**
 * IntentRouter — classify a user message into one of the three product
 * spaces (portal / ops / noc) or "cross" (spans multiple spaces), and
 * (when classified) dispatch the question to the corresponding product
 * via {@link ProductQueryService} so the user gets a real answer rather
 * than just a label.
 *
 * <p>Classification: a single small LLM call with a system prompt that
 * describes the four intents. The LLM is asked to reply with a strict
 * JSON object so we can parse reliably. On any failure (LLM error,
 * parse error, unknown intent), fall back to "cross" with confidence
 * 0 so the front-end can take the safe "ask the user" path.</p>
 *
 * <p>Heuristic short-circuits live in the front-end {@code
 * useIntentRouter} hook — the backend is only consulted when the
 * heuristic says "cross" / "idle".</p>
 *
 * <p>Dispatch: after classification, if the intent is portal / ops / noc,
 * the router calls {@link ProductQueryService#dispatch} which picks a
 * specific action via another small LLM call and executes it. The
 * resulting {@link ProductQueryService.QueryResult} is folded into
 * {@link RoutingResult#routed}.</p>
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
    private final ProductQueryService productQueryService;

    public IntentRouter(ModelClient modelClient, ProductQueryService productQueryService) {
        this.modelClient = modelClient;
        this.productQueryService = productQueryService;
    }

    /**
     * Classify a free-form user input and (if a specific product is
     * identified) dispatch to that product's query service. Never throws
     * — returns a fallback result on any error.
     */
    public RoutingResult route(String input, String workspaceId) {
        if (input == null || input.isBlank()) {
            return RoutingResult.fallback("cross");
        }

        List<ModelMessage> messages = List.of(
            ModelMessage.system(SYSTEM_PROMPT),
            ModelMessage.user("用户输入：" + input.trim())
        );

        IntentResult intent;
        try {
            ChatCompletionResponse resp = modelClient.chatCompletion(messages, null, false);
            if (resp == null || resp.getContent() == null || resp.getContent().isBlank()) {
                return RoutingResult.fallback("cross");
            }
            intent = parse(resp.getContent());
        } catch (Exception e) {
            log.warn("Intent classification LLM call failed: {}", e.getMessage());
            return RoutingResult.fallback("cross");
        }

        // Dispatch to the product query service when classification is
        // specific. "cross" falls through (no single product owns it).
        ProductQueryService.QueryResult routed = null;
        if (intent != null && productQueryService != null
            && !"cross".equals(intent.getIntent())) {
            try {
                routed = productQueryService.dispatch(intent.getIntent(), input, workspaceId);
            } catch (Exception e) {
                log.warn("Product dispatch for intent={} failed: {}",
                    intent.getIntent(), e.getMessage());
            }
        }
        return new RoutingResult(intent, routed);
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

    /**
     * Routing result: classification + (optional) product dispatch.
     * Mirrors the front-end type `RoutingResult`.
     */
    public static final class RoutingResult {
        public final IntentResult intent;
        public final ProductQueryService.QueryResult routed;

        public RoutingResult(IntentResult intent, ProductQueryService.QueryResult routed) {
            this.intent = intent;
            this.routed = routed;
        }

        public static RoutingResult fallback(String intent) {
            return new RoutingResult(IntentResult.fallback(intent), null);
        }
    }
}
