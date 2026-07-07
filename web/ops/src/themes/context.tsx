import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import { BUILTIN_THEMES, defaultTheme } from "./presets";
import type { DashboardTheme, ThemeLayer, ThemePalette } from "./types";
import { api } from "@/lib/api";
import { SPACE_THEMES, themeForSpace } from "./space-themes";
import type { SpaceName } from "@/lib/routing/spaces";

/** LocalStorage key — pre-applied before the React tree mounts to avoid
 *  a visible flash of the default palette on theme-overridden installs. */
const STORAGE_KEY = "hermes-dashboard-theme";

/** Turn a ThemeLayer into the two CSS expressions the DS consumes:
 *  `--<name>` (color-mix'd with alpha) and `--<name>-base` (opaque hex). */
function layerVars(name: "background" | "midground" | "foreground", layer: ThemeLayer) {
  const pct = Math.round(layer.alpha * 100);
  return {
    [`--${name}`]: `color-mix(in srgb, ${layer.hex} ${pct}%, transparent)`,
    [`--${name}-base`]: layer.hex,
    [`--${name}-alpha`]: String(layer.alpha),
  };
}

/** Write a theme's palette to `document.documentElement` as inline styles.
 *  Inline styles beat the `:root { }` rule in index.css, so this cascades
 *  into every shadcn-compat token defined over the DS triplet. */
function applyPalette(palette: ThemePalette) {
  const root = document.documentElement;
  const vars = {
    ...layerVars("background", palette.background),
    ...layerVars("midground", palette.midground),
    ...layerVars("foreground", palette.foreground),
    "--warm-glow": palette.warmGlow,
    "--noise-opacity-mul": String(palette.noiseOpacity),
  };
  for (const [k, v] of Object.entries(vars)) {
    root.style.setProperty(k, v);
  }
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [themeName, setThemeName] = useState<string>(() => {
    if (typeof window === "undefined") return "default";
    return window.localStorage.getItem(STORAGE_KEY) ?? "default";
  });
  const [userOverride, setUserOverride] = useState<string | null>(() => {
    if (typeof window === "undefined") return null;
    // userOverride is the theme the user explicitly picked via ThemeSwitcher.
    // If unset, the active space's default theme is used.
    return window.localStorage.getItem("hermes-dashboard-theme-override");
  });
  const [availableThemes, setAvailableThemes] = useState<
    Array<{ description: string; label: string; name: string }>
  >(() =>
    Object.values(BUILTIN_THEMES).map((t) => ({
      name: t.name,
      label: t.label,
      description: t.description,
    })),
  );

  useEffect(() => {
    const t = BUILTIN_THEMES[themeName] ?? defaultTheme;
    applyPalette(t.palette);
  }, [themeName]);

  useEffect(() => {
    let cancelled = false;
    api
      .getThemes()
      .then((resp) => {
        if (cancelled) return;
        if (resp.themes?.length) setAvailableThemes(resp.themes);
        if (resp.active && resp.active !== themeName) {
          setThemeName(resp.active);
          window.localStorage.setItem(STORAGE_KEY, resp.active);
        }
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, []);

  const setTheme = useCallback((name: string) => {
    const next = BUILTIN_THEMES[name] ? name : "default";
    setThemeName(next);
    window.localStorage.setItem(STORAGE_KEY, next);
    // Mark this as a user override — the active space will no longer
    // auto-switch the theme.
    setUserOverride(next);
    window.localStorage.setItem("hermes-dashboard-theme-override", next);
    api.setTheme(next).catch(() => {});
  }, []);

  /**
   * Switch the theme to the default for a given space, but only if the
   * user hasn't explicitly overridden the theme. Called from
   * SpaceThemeBridge whenever the user enters a new space.
   */
  const syncSpaceDefault = useCallback(
    (space: SpaceName) => {
      if (userOverride) return; // user has an override; respect it
      const targetName = SPACE_THEMES[space];
      if (BUILTIN_THEMES[targetName] && targetName !== themeName) {
        setThemeName(targetName);
        window.localStorage.setItem(STORAGE_KEY, targetName);
        api.setTheme(targetName).catch(() => {});
      }
    },
    [userOverride, themeName],
  );

  const clearOverride = useCallback(() => {
    setUserOverride(null);
    window.localStorage.removeItem("hermes-dashboard-theme-override");
  }, []);

  const value = useMemo<ThemeContextValue>(
    () => ({
      theme: BUILTIN_THEMES[themeName] ?? defaultTheme,
      themeName,
      availableThemes,
      setTheme,
      syncSpaceDefault,
      clearOverride,
      hasUserOverride: !!userOverride,
    }),
    [themeName, availableThemes, setTheme, syncSpaceDefault, clearOverride, userOverride],
  );

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

/**
 * Bridge component — call inside App to keep the active theme in sync
 * with the active space. The user's explicit override (if any) wins.
 */
export function SpaceThemeBridge({ activeSpace }: { activeSpace: SpaceName }) {
  const { syncSpaceDefault } = useTheme();
  useEffect(() => {
    syncSpaceDefault(activeSpace);
  }, [activeSpace, syncSpaceDefault]);
  return null;
}

/** Convenience — get the default theme for a space (without touching state). */
export function useSpaceDefaultTheme(space: SpaceName): DashboardTheme {
  return themeForSpace(space);
}

export function useTheme(): ThemeContextValue {
  return useContext(ThemeContext);
}

const ThemeContext = createContext<ThemeContextValue>({
  theme: defaultTheme,
  themeName: "default",
  availableThemes: Object.values(BUILTIN_THEMES).map((t) => ({
    name: t.name,
    label: t.label,
    description: t.description,
  })),
  setTheme: () => {},
  syncSpaceDefault: () => {},
  clearOverride: () => {},
  hasUserOverride: false,
});

interface ThemeContextValue {
  availableThemes: Array<{ description: string; label: string; name: string }>;
  clearOverride: () => void;
  hasUserOverride: boolean;
  setTheme: (name: string) => void;
  syncSpaceDefault: (space: SpaceName) => void;
  theme: DashboardTheme;
  themeName: string;
}
