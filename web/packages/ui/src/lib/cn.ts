/**
 * className composition utility.
 *
 * Wraps clsx + tailwind-merge so utility lists can include conditionals
 * (`{ "foo": cond }`, `["a", cond && "b"]`) and conflicting tailwind
 * classes resolve predictably (later wins).
 */
import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

export type { ClassValue };

export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs));
}
