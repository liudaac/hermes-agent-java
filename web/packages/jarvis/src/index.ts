/**
 * @hermes/jarvis — the cross-space dialogue shell.
 *
 * Imported by all three SPAs (portal / ops / noc). The package never
 * ships as a real npm package — it's consumed via a Vite alias that
 * resolves `@hermes/jarvis` to this directory.
 *
 * Per design.md §0 (TL;DR): Jarvis is *not* a 4th product, it's a
 * dialogue shell layered on top of Portal / Ops / NOC. Mount
 * `<JarvisCore />` once at the top of each SPA's tree to get:
 *
 *   - bottom-right orb (the always-visible "Jarvis is here" affordance)
 *   - keyboard shortcut (⌘K) to summon the 360×420 overlay
 *   - cross-tab state sync (BroadcastChannel + storage)
 *   - cross-space awareness (Portal / Ops / NOC auto-detected)
 *   - intent routing (heuristic + prompt)
 *
 * What's NOT here (intentionally):
 *   - Direct LLM calls (the backend /api/jarvis/* endpoints are
 *     the single owner of model invocations)
 *   - The particles' actual physics (the engine does that)
 *   - Cross-product navigation (the SPA's own router handles that)
 */

// Core
export { JarvisCore, useJarvisCore } from "./core/JarvisCore";
export { JarvisEngine, type FormBehavior } from "./core/JarvisEngine";
export { JarvisFSM, type FormName, type SubFormName, type FormState } from "./core/JarvisFSM";
export { Particle } from "./core/Particle";
export * from "./core/Physics";
export * from "./core/Color";

// HUD
export { HudRing } from "./hud/HudRing";
export { CenterCore } from "./hud/CenterCore";
export { Scanline } from "./hud/Scanline";
export { DataOverlay } from "./hud/DataOverlay";

// Forms
export { makeForm } from "./forms";
export {
  buildPool,
  burstOut,
  layoutFor,
  phaseAt,
  TRANSITION_MS,
  TRANSITION_MIN_MS,
  applyPhaseMotion,
} from "./forms/transitions";

// Hooks
export { useJarvisStore, type JarvisMessage, type OverlayMode } from "./hooks/useJarvisStore";
export { useContextAwareness, setActiveResource, type ActiveContext, type SpaceName } from "./hooks/useContextAwareness";
export { useIntentRouter, type Intent } from "./hooks/useIntentRouter";
export { useLongIdle } from "./hooks/useLongIdle";
export { useAttention } from "./hooks/useAttention";
export { useKeyShortcuts } from "./hooks/useKeyShortcuts";
export { useCrossSpaceSync, broadcastForm } from "./hooks/useCrossSpaceSync";

// API
export { jarvisApi, type ChatRequest, type ChatResponse, type IntentResult, type IntentName, type Suggestion } from "./api/jarvisApi";
export { routeForIntent, type RouteTarget } from "./api/intentRoutes";

// Overlay
export { JarvisHudPanel } from "./overlay/JarvisHudPanel";
export { JarvisOverlay } from "./overlay/JarvisOverlay";
export { JarvisFullscreen } from "./overlay/JarvisFullscreen";
export { ConversationFlow } from "./overlay/ConversationFlow";
export { MessageBubble } from "./overlay/MessageBubble";
// Orb
export { JarvisOrb } from "./core/JarvisOrb";
