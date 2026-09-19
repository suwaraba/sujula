import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';

interface Toast {
  id: number;
  tone: 'good' | 'bad' | 'info';
  message: string;
}

interface ToastApi {
  ok(message: string): void;
  bad(message: string): void;
  info(message: string): void;
}

const ToastContext = createContext<ToastApi | null>(null);

export function useToast(): ToastApi {
  const context = useContext(ToastContext);
  if (!context) throw new Error('useToast must be used inside <ToastProvider>');
  return context;
}

let nextId = 1;

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);

  const push = useCallback((tone: Toast['tone'], message: string) => {
    const id = nextId++;
    setToasts((current) => [...current, { id, tone, message }]);
    // Long enough to read a sentence about money that just moved.
    window.setTimeout(() => setToasts((current) => current.filter((toast) => toast.id !== id)), 6000);
  }, []);

  const api = useMemo<ToastApi>(
    () => ({
      ok: (message) => push('good', message),
      bad: (message) => push('bad', message),
      info: (message) => push('info', message),
    }),
    [push],
  );

  return (
    <ToastContext.Provider value={api}>
      {children}
      <div className="toast-stack" role="status" aria-live="polite">
        {toasts.map((toast) => (
          <div key={toast.id} className={`toast toast-${toast.tone}`}>
            {toast.message}
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}
