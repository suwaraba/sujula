import { useId, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes, type TextareaHTMLAttributes } from 'react';

type Shell = { label: ReactNode; hint?: ReactNode; error?: string | undefined; required?: boolean };

function Field({ label, hint, error, required, id, children }: Shell & { id: string; children: ReactNode }) {
  return (
    <div className="field">
      <label className="field__label" htmlFor={id}>
        {label}
        {required && <span className="field__required" aria-hidden="true">*</span>}
      </label>
      {children}
      {error ? (
        <div className="field__error" id={`${id}-error`}>{error}</div>
      ) : (
        hint && <div className="field__hint" id={`${id}-hint`}>{hint}</div>
      )}
    </div>
  );
}

export function TextField({ label, hint, error, required, ...rest }: Shell & InputHTMLAttributes<HTMLInputElement>) {
  const id = useId();
  return (
    <Field label={label} hint={hint} error={error} required={required} id={id}>
      <input
        id={id} className="input"
        aria-invalid={error ? 'true' : undefined}
        aria-describedby={error ? `${id}-error` : hint ? `${id}-hint` : undefined}
        required={required} {...rest}
      />
    </Field>
  );
}

export function TextArea({ label, hint, error, required, ...rest }: Shell & TextareaHTMLAttributes<HTMLTextAreaElement>) {
  const id = useId();
  return (
    <Field label={label} hint={hint} error={error} required={required} id={id}>
      <textarea
        id={id} className="textarea"
        aria-invalid={error ? 'true' : undefined}
        aria-describedby={error ? `${id}-error` : hint ? `${id}-hint` : undefined}
        required={required} {...rest}
      />
    </Field>
  );
}

export function SelectField({ label, hint, error, required, children, ...rest }: Shell & SelectHTMLAttributes<HTMLSelectElement>) {
  const id = useId();
  return (
    <Field label={label} hint={hint} error={error} required={required} id={id}>
      <select
        id={id} className="select"
        aria-invalid={error ? 'true' : undefined}
        aria-describedby={error ? `${id}-error` : hint ? `${id}-hint` : undefined}
        required={required} {...rest}
      >
        {children}
      </select>
    </Field>
  );
}

/** Turns a rejected request into per-field messages. */
export function fieldError(errors: Record<string, string> | undefined, ...names: string[]): string | undefined {
  if (!errors) return undefined;
  for (const name of names) if (errors[name]) return errors[name];
  return undefined;
}
