import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';

type Toast = { id: number; tone: 'ok' | 'danger' | 'info' | 'warn'; message: string };

type ToastContextValue = {
  show: (message: string, tone?: Toast['tone']) => void;
  success: (message: string) => void;
  error: (message: string) => void;
};

const ToastContext = createContext<ToastContextValue | null>(null);
let nextId = 1;

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);

  const show = useCallback((message: string, tone: Toast['tone'] = 'info') => {
    const id = nextId++;
    setToasts((current) => [...current, { id, tone, message }]);
    // Longer than a phone app's: an operator is looking at a customer, not the
    // screen, and glances back afterwards.
    setTimeout(() => setToasts((current) => current.filter((t) => t.id !== id)), 7000);
  }, []);

  const value = useMemo<ToastContextValue>(
    () => ({ show, success: (m) => show(m, 'ok'), error: (m) => show(m, 'danger') }),
    [show],
  );

  return (
    <ToastContext.Provider value={value}>
      {children}
      {createPortal(
        <div
          role="status"
          aria-live="polite"
          style={{
            position: 'fixed',
            insetInline: 'var(--space-4)',
            bottom: 'calc(var(--tabbar-h) + var(--safe-bottom) + var(--space-3))',
            zIndex: 60,
            display: 'flex',
            flexDirection: 'column',
            gap: 'var(--space-2)',
            pointerEvents: 'none',
            alignItems: 'center',
          }}
        >
          {toasts.map((toast) => (
            <div
              key={toast.id}
              className={`notice notice--${toast.tone}`}
              style={{ pointerEvents: 'auto', boxShadow: 'var(--shadow-lg)', maxWidth: 520, width: '100%' }}
            >
              <span className="notice__icon" aria-hidden="true">
                {toast.tone === 'ok' ? '✓' : toast.tone === 'danger' ? '⚠' : 'ℹ'}
              </span>
              <div className="notice__body">{toast.message}</div>
            </div>
          ))}
        </div>,
        document.body,
      )}
    </ToastContext.Provider>
  );
}

export function useToast(): ToastContextValue {
  const context = useContext(ToastContext);
  if (!context) throw new Error('useToast must be used inside <ToastProvider>');
  return context;
}
