import { useEffect, useRef } from 'react';

/**
 * The digits somebody reads out, entered on a keypad rather than typed.
 *
 * This is the most important control in the app. The operator is standing
 * behind a counter with a parcel in one hand and a person on the other side of
 * the till reading a number aloud. A text input would raise a full keyboard
 * over most of a tablet, all of it letters, none of them wanted — and it would
 * autocorrect, autofill and remember, none of which should ever happen to
 * somebody else's collection code.
 *
 * The cells fill left to right so the count is visible without counting, and
 * the whole thing is operable by someone who cannot read: the keys are digits
 * in the layout every phone in the world uses.
 *
 * Codes are never held anywhere but this component's parent state. Nothing here
 * writes one to storage, a URL or a log.
 */
export function CodePad({
  value, onChange, length = 6, error = false, onComplete, label, disabled = false,
}: {
  value: string;
  onChange: (value: string) => void;
  /** The backend accepts four to eight; six is what it issues. */
  length?: number;
  error?: boolean;
  /** Fires when the last digit lands, so nobody hunts for a button. */
  onComplete?: (value: string) => void;
  label: string;
  disabled?: boolean;
}) {
  const completed = useRef<string | null>(null);

  useEffect(() => {
    if (value.length === length && completed.current !== value) {
      completed.current = value;
      onComplete?.(value);
    }
    if (value.length < length) completed.current = null;
  }, [value, length, onComplete]);

  const press = (digit: string) => {
    if (disabled || value.length >= length) return;
    onChange(value + digit);
    // Touch is the feedback that gets through in a busy shop.
    navigator.vibrate?.(8);
  };

  const back = () => {
    if (disabled || value.length === 0) return;
    onChange(value.slice(0, -1));
    navigator.vibrate?.(8);
  };

  // A physical keyboard is attached to plenty of counter tablets, and an
  // operator who has one should not be made to tap pictures of numbers.
  useEffect(() => {
    if (disabled) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (/^\d$/.test(event.key)) {
        event.preventDefault();
        press(event.key);
      } else if (event.key === 'Backspace') {
        event.preventDefault();
        back();
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  });

  return (
    <div className="codepad">
      <div
        className="codepad__cells"
        role="status"
        aria-live="polite"
        aria-label={`${label}: ${value.length} of ${length} digits entered`}
      >
        {Array.from({ length }, (_, index) => {
          const filled = index < value.length;
          const next = index === value.length;
          const classes = [
            'codepad__cell',
            filled ? 'is-filled' : '',
            next && !error ? 'is-next' : '',
            error ? 'is-error' : '',
          ].filter(Boolean).join(' ');
          return (
            <div key={index} className={classes} aria-hidden="true">
              {filled ? value[index] : ''}
            </div>
          );
        })}
      </div>

      <div className="codepad__keys">
        {['1', '2', '3', '4', '5', '6', '7', '8', '9'].map((digit) => (
          <button
            key={digit}
            type="button"
            className="codepad__key"
            onClick={() => press(digit)}
            disabled={disabled || value.length >= length}
            aria-label={digit}
          >
            {digit}
          </button>
        ))}

        <button
          type="button"
          className="codepad__key codepad__key--action"
          onClick={() => onChange('')}
          disabled={disabled || value.length === 0}
        >
          Clear
        </button>

        <button
          type="button"
          className="codepad__key"
          onClick={() => press('0')}
          disabled={disabled || value.length >= length}
          aria-label="0"
        >
          0
        </button>

        <button
          type="button"
          className="codepad__key codepad__key--action"
          onClick={back}
          disabled={disabled || value.length === 0}
          aria-label="Delete the last digit"
        >
          ⌫
        </button>
      </div>
    </div>
  );
}
