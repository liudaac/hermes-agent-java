/**
 * Color — HSL helpers + design-system form palette.
 *
 * Per design.md §2.1 (form totals) and §2.2.1 (Core spec), every form
 * has a primary + secondary color expressed in HSL. The form's
 * `ColorBehavior` (Behavior 3 in §2.6.3) decides how to interpolate
 * between primary and secondary over a particle's life.
 */

/** Form primary/secondary palette (design.md §2.1). */
export const FORM_PALETTE: Record<
  string,
  { primary: HSL; secondary: HSL }
> = {
  core:      { primary: { h: 187, s: 70, l: 60 }, secondary: { h: 263, s: 70, l: 60 } }, // cyan-300 / violet-500
  sphere:    { primary: { h: 187, s: 70, l: 50 }, secondary: { h: 152, s: 60, l: 50 } }, // cyan-400 / emerald-300
  helix:     { primary: { h: 263, s: 70, l: 60 }, secondary: { h: 330, s: 70, l: 65 } }, // violet-500 / pink-300
  cascade:   { primary: { h: 200, s: 70, l: 65 }, secondary: { h: 187, s: 80, l: 70 } }, // sky-300 / cyan-200
  pulse:     { primary: { h: 187, s: 90, l: 75 }, secondary: { h:  35, s: 90, l: 60 } }, // cyan-300 / amber
  net:       { primary: { h: 230, s: 70, l: 60 }, secondary: { h: 187, s: 70, l: 60 } }, // indigo-400 / cyan-200
  dawn:      { primary: { h: 263, s: 70, l: 60 }, secondary: { h: 187, s: 70, l: 60 } }, // violet → cyan
  archive:   { primary: { h: 187, s: 50, l: 40 }, secondary: { h: 263, s: 50, l: 40 } },
  vigilant:  { primary: { h:   0, s: 80, l: 60 }, secondary: { h:  10, s: 80, l: 50 } },
  reflective:{ primary: { h: 263, s: 60, l: 70 }, secondary: { h: 187, s: 60, l: 70 } },
  companion: { primary: { h:  35, s: 80, l: 65 }, secondary: { h:  35, s: 80, l: 75 } },
  authority: { primary: { h: 187, s: 50, l: 90 }, secondary: { h:  45, s: 80, l: 55 } },
};

export interface HSL { h: number; s: number; l: number; a?: number }

export function hslToCss(c: HSL, alpha = 1): string {
  const a = c.a ?? alpha;
  return `hsla(${c.h} ${c.s}% ${c.l}% / ${a})`;
}

/** Linearly interpolate two HSL colors. */
export function lerpHSL(a: HSL, b: HSL, t: number): HSL {
  return {
    h: a.h + (b.h - a.h) * t,
    s: a.s + (b.s - a.s) * t,
    l: a.l + (b.l - a.l) * t,
  };
}
