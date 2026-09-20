import { useId, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes, type TextareaHTMLAttributes } from 'react';

type FieldShell = {
  label: ReactNode;
  hint?: ReactNode;
  error?: string | undefined;
  required?: boolean;
};

function Shell({
  label, hint, error, required, id, children,
}: FieldShell & { id: string; children: ReactNode }) {
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

export function TextField({
  label, hint, error, required, ...rest
}: FieldShell & InputHTMLAttributes<HTMLInputElement>) {
  const id = useId();
  return (
    <Shell label={label} hint={hint} error={error} required={required} id={id}>
      <input
        id={id}
        className="input"
        aria-invalid={error ? 'true' : undefined}
        aria-describedby={error ? `${id}-error` : hint ? `${id}-hint` : undefined}
        required={required}
        {...rest}
      />
    </Shell>
  );
}

export function TextArea({
  label, hint, error, required, ...rest
}: FieldShell & TextareaHTMLAttributes<HTMLTextAreaElement>) {
  const id = useId();
  return (
    <Shell label={label} hint={hint} error={error} required={required} id={id}>
      <textarea
        id={id}
        className="textarea"
        aria-invalid={error ? 'true' : undefined}
        aria-describedby={error ? `${id}-error` : hint ? `${id}-hint` : undefined}
        required={required}
        {...rest}
      />
    </Shell>
  );
}

export function SelectField({
  label, hint, error, required, children, ...rest
}: FieldShell & SelectHTMLAttributes<HTMLSelectElement>) {
  const id = useId();
  return (
    <Shell label={label} hint={hint} error={error} required={required} id={id}>
      <select
        id={id}
        className="select"
        aria-invalid={error ? 'true' : undefined}
        aria-describedby={error ? `${id}-error` : hint ? `${id}-hint` : undefined}
        required={required}
        {...rest}
      >
        {children}
      </select>
    </Shell>
  );
}

export function CheckField({
  label, hint, ...rest
}: { label: ReactNode; hint?: ReactNode } & InputHTMLAttributes<HTMLInputElement>) {
  return (
    <label className="checkbox">
      <input type="checkbox" {...rest} />
      <span className="checkbox__text">
        {label}
        {hint && <span className="checkbox__hint">{hint}</span>}
      </span>
    </label>
  );
}

/**
 * Turns a rejected request into per-field messages.
 *
 * The application answers a bean-validation failure with a map of field names,
 * so the seller sees "a price is required" under the price rather than one
 * generic banner they then have to decode.
 */
export function fieldError(
  errors: Record<string, string> | undefined,
  ...names: string[]
): string | undefined {
  if (!errors) return undefined;
  for (const name of names) {
    if (errors[name]) return errors[name];
  }
  return undefined;
}
