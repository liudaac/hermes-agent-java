/**
 * useIntentRouter — classify user input into portal/ops/noc/cross.
 *
 * Per design.md §11.3 (跨空间意图分类) the MVP uses a prompt-based
 * classifier that calls the backend `/api/jarvis/intent` endpoint.
 * The backend resolves the intent by sending a small prompt to the
 * LLM (or a regex fallback for cheap cases). The hook is optimistic
 * — it returns 'portal' immediately and updates once the round-trip
 * completes, so simple UI feedback (e.g. routing to portalApi) is
 * never blocked.
 */

import { useCallback } from "react";
import { jarvisApi, type IntentName, type IntentResult } from "../api/jarvisApi";

export type Intent = IntentName | "idle";

const HEURISTIC_KEYWORDS: Array<{ intent: Intent; re: RegExp }> = [
  { intent: "portal", re: /(团队|审批|场景|模板|待办|我的|数字员工|portal|approval|team)/i },
  { intent: "ops",    re: /(配置|租户|技能|日志|session|tenants|skill|env|config|logs|cron)/i },
  { intent: "noc",    re: /(异常|告警|追踪|trace|sla|dlq|审核|风控|audit|governance|dlq|trace)/i },
];

function heuristicClassify(input: string): Intent {
  for (const { intent, re } of HEURISTIC_KEYWORDS) {
    if (re.test(input)) return intent;
  }
  return "idle";
}

export function useIntentRouter() {
  const route = useCallback(async (input: string): Promise<IntentResult> => {
    const quick = heuristicClassify(input);
    if (quick !== "idle" && quick !== "cross") {
      return { intent: quick, confidence: 0.5, source: "heuristic" };
    }
    try {
      const result = await jarvisApi.classifyIntent(input);
      return { ...result, intent: result.intent ?? "cross" };
    } catch {
      return { intent: "cross", confidence: 0.0, source: "fallback" };
    }
  }, []);

  return { route };
}
