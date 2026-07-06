/**
 * CrossSpaceLink — link component for jumping between Portal / Ops / NOC.
 *
 * Aligned with the three-space refactor: business users land in /portal
 * by default, but sometimes need to drill into /ops or /noc (e.g. when
 * a business-team health issue needs a governance-deep-link). This
 * component:
 *
 *   1. Renders an inline <a> (or <button>) styled as a "→ NOC" link.
 *   2. On click, navigates to the target space root.
 *   3. Remembers the source space so the user can return with a single
 *      click via <ReturnToSource />.
 *
 *   <CrossSpaceLink to="/noc/agents/abc-123" sourceLabel="治理详情" />
 *
 * If you want a confirmation prompt (e.g. "you are about to enter the
 * NOC control console"), pass `confirm: true`.
 */

import { useCallback, type MouseEvent, type ReactNode } from "react";
import { useNavigate } from "react-router-dom";
import { ArrowRight } from "lucide-react";
import { cn } from "@/lib/utils";
import { rememberSpace, type SpaceName } from "./spaces";
import { pathToSpace } from "./nav";

const STORAGE_KEY = "hermes.lastVisitedSpace";

/**
 * Read the most recently visited space across all sessions.
 * Used by <ReturnToSource /> to know where the user came from.
 */
export function readLastVisitedSpace(): SpaceName | null {
  if (typeof window === "undefined") return null;
  const raw = window.sessionStorage.getItem(STORAGE_KEY);
  if (raw === "portal" || raw === "ops" || raw === "noc") return raw;
  return null;
}

function writeLastVisitedSpace(space: SpaceName): void {
  try {
    window.sessionStorage.setItem(STORAGE_KEY, space);
  } catch {
    // ignore
  }
}

export interface CrossSpaceLinkProps {
  /** Target path (anywhere, including other spaces). */
  to: string;
  /** Visible label (e.g. "治理详情", "查看日志", "审计"). */
  children: ReactNode;
  /** Whether to show a confirmation prompt before navigating. */
  confirm?: boolean;
  /** Confirmation message override. */
  confirmMessage?: string;
  /** Whether to remember the source space on click. Default true. */
  rememberSource?: boolean;
  className?: string;
  icon?: ReactNode;
}

export function CrossSpaceLink({
  to,
  children,
  confirm = false,
  confirmMessage,
  rememberSource = true,
  className,
  icon,
}: CrossSpaceLinkProps) {
  const navigate = useNavigate();

  const onClick = useCallback(
    (e: MouseEvent<HTMLAnchorElement>) => {
      e.preventDefault();
      if (rememberSource) {
        const currentSpace = pathToSpace(window.location.pathname);
        if (currentSpace) {
          writeLastVisitedSpace(currentSpace);
        }
      }
      const proceed = () => {
        const targetSpace = pathToSpace(to);
        if (targetSpace) rememberSpace(targetSpace);
        navigate(to);
      };
      if (confirm) {
        const msg =
          confirmMessage ??
          "你即将进入其他控制台。是否继续？\n(可在右上角随时切换返回)";
        if (window.confirm(msg)) {
          proceed();
        }
      } else {
        proceed();
      }
    },
    [to, confirm, confirmMessage, rememberSource, navigate],
  );

  return (
    <a
      href={to}
      onClick={onClick}
      className={cn(
        "inline-flex items-center gap-1 text-[0.7rem] tracking-[0.08em]",
        "underline underline-offset-2 hover:opacity-100",
        "text-amber-200/90 hover:text-amber-100",
        className,
      )}
    >
      {children}
      {icon ?? <ArrowRight className="h-3 w-3" />}
    </a>
  );
}

/**
 * ReturnToSource — small "← 返回" button that shows in non-portal spaces
 * and returns the user to their most recently visited space.
 *
 * <ReturnToSource /> // → "← 返回 Portal"
 */
export interface ReturnToSourceProps {
  fallback?: SpaceName;
  className?: string;
  children?: (source: SpaceName) => ReactNode;
}

const SPACE_LABEL: Record<SpaceName, string> = {
  portal: "业务",
  ops: "Ops",
  noc: "NOC",
};

export function ReturnToSource({ fallback = "portal", className, children }: ReturnToSourceProps) {
  const navigate = useNavigate();
  const source = readLastVisitedSpace() ?? fallback;

  if (pathToSpace(window.location.pathname) === source) {
    // Already in the source space — no need to render.
    return null;
  }

  return (
    <button
      type="button"
      onClick={() => {
        rememberSpace(source);
        const root =
          source === "portal" ? "/portal"
            : source === "ops" ? "/ops"
            : "/noc";
        navigate(root);
      }}
      className={cn(
        "inline-flex items-center gap-1.5 rounded-full border border-current/20",
        "bg-background-base/40 px-3 py-1 text-[0.7rem] tracking-[0.1em]",
        "opacity-80 hover:opacity-100 transition-opacity",
        className,
      )}
      title={`返回 ${SPACE_LABEL[source]}`}
    >
      <span aria-hidden>←</span>
      {children ? children(source) : <>返回 {SPACE_LABEL[source]}</>}
    </button>
  );
}
