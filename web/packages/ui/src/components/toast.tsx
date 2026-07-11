import {
  createContext,
  useContext,
  useCallback,
  useState,
  useEffect,
  type ReactNode,
} from "react";
import { createPortal } from "react-dom";

export interface ToastState {
  message: string;
  type: "success" | "error";
}

export interface ToastContextValue {
  showToast: (message: string, type: "success" | "error") => void;
}

const ToastContext = createContext<ToastContextValue>({
  showToast: () => {},
});

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toast, setToast] = useState<ToastState | null>(null);

  const showToast = useCallback((message: string, type: "success" | "error") => {
    setToast({ message, type });
    setTimeout(() => setToast(null), 3000);
  }, []);

  return (
    <ToastContext.Provider value={{ showToast }}>
      {children}
      <GlobalToast toast={toast} />
    </ToastContext.Provider>
  );
}

export function useToast(): ToastContextValue {
  return useContext(ToastContext);
}

/** Renders the toast UI — lives inside ToastProvider so it shares state.
 *  Portals to document.body to escape any ancestor stacking context. */
function GlobalToast({ toast }: { toast: ToastState | null }) {
  const [visible, setVisible] = useState(false);
  const [current, setCurrent] = useState<ToastState | null>(null);

  useEffect(() => {
    if (toast) {
      setCurrent(toast);
      setVisible(true);
    } else {
      setVisible(false);
      const timer = setTimeout(() => setCurrent(null), 200);
      return () => clearTimeout(timer);
    }
  }, [toast]);

  if (!current) return null;

  return createPortal(
    <div
      role="status"
      aria-live="polite"
      className={`fixed top-16 right-4 z-50 border px-4 py-2.5 font-courier text-xs tracking-wider uppercase backdrop-blur-sm ${
        current.type === "success"
          ? "bg-success/15 text-success border-success/30"
          : "bg-destructive/15 text-destructive border-destructive/30"
      }`}
      style={{
        animation: visible
          ? "toast-in 200ms ease-out forwards"
          : "toast-out 200ms ease-in forwards",
      }}
    >
      {current.message}
    </div>,
    document.body,
  );
}
