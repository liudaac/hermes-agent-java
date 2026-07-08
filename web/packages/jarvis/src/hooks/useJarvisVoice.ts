/**
 * useJarvisVoice — speak Jarvis responses via the browser's built-in
 * SpeechSynthesis API. Zero backend calls, zero API keys, works
 * offline.
 *
 * Two entry points:
 *   - welcome(): picks a random summon greeting and speaks it
 *   - speak(text): speaks arbitrary text
 *
 * Voice selection prefers a Chinese voice where available, falls back
 * to the default voice. Rate is slightly faster than natural (1.05)
 * to keep it snappy; pitch is slightly lower (0.92) for a "Jarvis"-ish
 * register instead of the default assistant chirp.
 *
 * Muting: a `hermes-jarvis-mute` flag in localStorage persists the
 * user's preference across reloads. The HUD panel exposes a toggle.
 */
import { useCallback, useEffect, useRef, useState } from "react";

const MUTE_KEY = "hermes-jarvis-mute";

const WELCOME_LINES = [
  "系统就绪，请指示。",
  "在线，等待指令。",
  "您好，我已就位。",
  "Jarvis 已激活。",
  "连接已建立，请指示。",
  "在线，请问需要什么？",
  "待命。",
];

function pickVoice(): SpeechSynthesisVoice | null {
  if (typeof window === "undefined" || !("speechSynthesis" in window)) return null;
  const voices = window.speechSynthesis.getVoices();
  if (voices.length === 0) return null;
  // Prefer Chinese voices (zh-*) with a male-ish register when available.
  const zh = voices.filter((v) => v.lang?.toLowerCase().startsWith("zh"));
  const zhMale = zh.find((v) => /(male|yunxi|yunyang|kangkang|zhiwei)/i.test(v.name));
  if (zhMale) return zhMale;
  if (zh.length > 0) return zh[0];
  // Fall back to any English voice tagged as male-ish, then default.
  const enMale = voices.find((v) => /male/i.test(v.name));
  return enMale ?? voices[0] ?? null;
}

export function useJarvisVoice() {
  const [muted, setMuted] = useState<boolean>(() => {
    if (typeof window === "undefined") return true;
    try {
      const v = localStorage.getItem(MUTE_KEY);
      // Default to UNMUTED — the whole point of the voice welcome is
      // to feel alive on first summon. Users who don't want voice can
      // mute and the preference persists.
      if (v === null) return false;
      return v === "1";
    } catch {
      return false;
    }
  });
  const lastUtteranceRef = useRef<SpeechSynthesisUtterance | null>(null);
  const voicesReadyRef = useRef(false);

  // Chrome loads voices async; listen for the voiceschanged event so
  // pickVoice() has something to work with even on the first call.
  useEffect(() => {
    if (typeof window === "undefined" || !("speechSynthesis" in window)) return;
    const load = () => { voicesReadyRef.current = true; };
    window.speechSynthesis.addEventListener("voiceschanged", load);
    // Some browsers (Safari) already have voices on init.
    if (window.speechSynthesis.getVoices().length > 0) voicesReadyRef.current = true;
    return () => window.speechSynthesis.removeEventListener("voiceschanged", load);
  }, []);

  const persistMute = useCallback((m: boolean) => {
    setMuted(m);
    try { localStorage.setItem(MUTE_KEY, m ? "1" : "0"); } catch { /* ignore */ }
    if (m && "speechSynthesis" in window) {
      window.speechSynthesis.cancel();
    }
  }, []);

  const toggleMute = useCallback(() => persistMute(!muted), [muted, persistMute]);

  const speak = useCallback((text: string) => {
    if (muted) return;
    if (typeof window === "undefined" || !("speechSynthesis" in window)) return;
    if (!text || text.length > 300) {
      // Don't read very long blocks aloud; it's a greeting ping,
      // not an audiobook.
      return;
    }
    // Cancel any in-progress speech so we don't overlap lines.
    window.speechSynthesis.cancel();
    const u = new SpeechSynthesisUtterance(text);
    const v = pickVoice();
    if (v) u.voice = v;
    u.rate = 1.05;
    u.pitch = 0.92;
    u.volume = 0.85;
    // If the picked voice isn't Chinese, force lang to zh so the synth
    // engine at least attempts Chinese pronunciation (better than
    // reading Chinese characters as English phonemes).
    if (!v?.lang?.toLowerCase().startsWith("zh")) u.lang = "zh-CN";
    lastUtteranceRef.current = u;
    window.speechSynthesis.speak(u);
  }, [muted]);

  const welcome = useCallback(() => {
    const line = WELCOME_LINES[Math.floor(Math.random() * WELCOME_LINES.length)];
    speak(line);
  }, [speak]);

  const cancel = useCallback(() => {
    if (typeof window !== "undefined" && "speechSynthesis" in window) {
      window.speechSynthesis.cancel();
    }
  }, []);

  return { speak, welcome, cancel, muted, toggleMute, persistMute };
}
