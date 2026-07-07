/**
 * className composition utility.
 * Minimal replacement — keeps portal independent of the ops shadcn-style cn.
 */
export type ClassValue =
  | string
  | number
  | null
  | undefined
  | false
  | Record<string, boolean | null | undefined>
  | ClassValue[];

export function cn(...inputs: ClassValue[]): string {
  const out: string[] = [];

  const walk = (v: ClassValue) => {
    if (!v && v !== 0) return;
    if (typeof v === "string" || typeof v === "number") {
      out.push(String(v));
    } else if (Array.isArray(v)) {
      v.forEach(walk);
    } else if (typeof v === "object") {
      for (const [key, val] of Object.entries(v)) {
        if (val) out.push(key);
      }
    }
  };

  inputs.forEach(walk);
  return out.join(" ");
}
