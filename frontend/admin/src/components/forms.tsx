import type { ChangeEvent, ReactNode } from 'react';

export function Field({
  label,
  hint,
  error,
  required,
  children,
}: {
  label: ReactNode;
  hint?: ReactNode;
  error?: string | null;
  required?: boolean;
  children: ReactNode;
}) {
  return (
    <label className="field">
      <span className="field-label">
        {label}
        {required && <span className="required" aria-hidden> *</span>}
      </span>
      {children}
      {hint && <span className="field-hint">{hint}</span>}
      {error && <span className="field-error">{error}</span>}
    </label>
  );
}

export function TextInput({
  value,
  onChange,
  ...rest
}: {
  value: string;
  onChange(value: string): void;
} & Omit<React.InputHTMLAttributes<HTMLInputElement>, 'value' | 'onChange'>) {
  return (
    <input
      className="input"
      value={value}
      onChange={(event: ChangeEvent<HTMLInputElement>) => onChange(event.target.value)}
      {...rest}
    />
  );
}

export function TextArea({
  value,
  onChange,
  rows = 3,
  ...rest
}: {
  value: string;
  onChange(value: string): void;
  rows?: number;
} & Omit<React.TextareaHTMLAttributes<HTMLTextAreaElement>, 'value' | 'onChange' | 'rows'>) {
  return (
    <textarea
      className="input"
      rows={rows}
      value={value}
      onChange={(event) => onChange(event.target.value)}
      {...rest}
    />
  );
}

export function NumberInput({
  value,
  onChange,
  ...rest
}: {
  value: number | '' | null;
  onChange(value: number | ''): void;
} & Omit<React.InputHTMLAttributes<HTMLInputElement>, 'value' | 'onChange'>) {
  return (
    <input
      className="input"
      type="number"
      value={value ?? ''}
      onChange={(event) => onChange(event.target.value === '' ? '' : Number(event.target.value))}
      {...rest}
    />
  );
}

/**
 * A money input.
 *
 * `step` follows the currency's own scale, so a form cannot offer to type
 * 1250.50 into a field denominated in a currency that has no minor unit.
 */
export function MoneyInput({
  value,
  onChange,
  minorUnits,
  ...rest
}: {
  value: string;
  onChange(value: string): void;
  minorUnits: number;
} & Omit<React.InputHTMLAttributes<HTMLInputElement>, 'value' | 'onChange'>) {
  return (
    <input
      className="input"
      type="number"
      inputMode="decimal"
      step={minorUnits === 0 ? '1' : (1 / 10 ** minorUnits).toFixed(minorUnits)}
      value={value}
      onChange={(event) => onChange(event.target.value)}
      {...rest}
    />
  );
}

export function Select<V extends string>({
  value,
  onChange,
  options,
  placeholder,
  ...rest
}: {
  value: V | '';
  onChange(value: V | ''): void;
  options: readonly V[] | readonly { value: V; label: string }[];
  placeholder?: string;
} & Omit<React.SelectHTMLAttributes<HTMLSelectElement>, 'value' | 'onChange'>) {
  const normalised = options.map((option) =>
    typeof option === 'string' ? { value: option, label: humanLabel(option) } : option,
  ) as { value: V; label: string }[];

  return (
    <select
      className="input"
      value={value}
      onChange={(event) => onChange(event.target.value as V | '')}
      {...rest}
    >
      {placeholder !== undefined && <option value="">{placeholder}</option>}
      {normalised.map((option) => (
        <option key={option.value} value={option.value}>
          {option.label}
        </option>
      ))}
    </select>
  );
}

function humanLabel(value: string): string {
  const spaced = value.replace(/_/g, ' ').toLowerCase();
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

export function CheckBox({
  checked,
  onChange,
  label,
  hint,
  disabled,
}: {
  checked: boolean;
  onChange(checked: boolean): void;
  label: ReactNode;
  hint?: ReactNode;
  disabled?: boolean;
}) {
  return (
    <label className="checkbox">
      <input
        type="checkbox"
        checked={checked}
        disabled={disabled}
        onChange={(event) => onChange(event.target.checked)}
      />
      <span>
        {label}
        {hint && <span className="field-hint">{hint}</span>}
      </span>
    </label>
  );
}

/** A tri-state filter: unset, yes, no. Unset is not "no". */
export function TriState({
  value,
  onChange,
  yes = 'Yes',
  no = 'No',
  any = 'Any',
}: {
  value: boolean | undefined;
  onChange(value: boolean | undefined): void;
  yes?: string;
  no?: string;
  any?: string;
}) {
  return (
    <select
      className="input"
      value={value === undefined ? '' : String(value)}
      onChange={(event) =>
        onChange(event.target.value === '' ? undefined : event.target.value === 'true')
      }
    >
      <option value="">{any}</option>
      <option value="true">{yes}</option>
      <option value="false">{no}</option>
    </select>
  );
}

export function FormRow({ children, columns = 2 }: { children: ReactNode; columns?: number }) {
  return (
    <div className="form-row" style={{ gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))` }}>
      {children}
    </div>
  );
}

export function FormActions({ children }: { children: ReactNode }) {
  return <div className="form-actions">{children}</div>;
}

export function FilterBar({ children, onReset }: { children: ReactNode; onReset?(): void }) {
  return (
    <div className="filter-bar">
      {children}
      {onReset && (
        <button type="button" className="button button-ghost" onClick={onReset}>
          Clear filters
        </button>
      )}
    </div>
  );
}
