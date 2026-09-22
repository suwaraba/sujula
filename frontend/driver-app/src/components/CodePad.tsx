/**
 * The six digits somebody reads out.
 *
 * This is the most important control in the app, and it is a keypad rather
 * than a text input on purpose. The driver is holding a parcel, standing in
 * front of the person who is reading the number to them, and a mobile keyboard
 * would cover two thirds of the screen with letters none of which are wanted.
 * Big numbered keys in a fixed layout can be worked by someone who cannot read
 * at all.
 *
 * The cells fill left to right so the driver can see how many digits have
 * landed without counting, and each key press buzzes — because in a noisy
 * street, with a helmet on, touch is the only feedback that gets through.
 */

import { useEffect, useRef } from 'react';

interface Props {
  value: string;
  onChange: (value: string) => void;
  /** The backend's codes are six digits; transfers accept four to eight. */
  length?: number;
  error?: boolean;
  /** Fires when the last digit lands, so a driver never hunts for a button. */
  onComplete?: (value: string) => void;
  label: string;
  /** Hides the digits, for a PIN rather than a handover code. */
  secret?: boolean;
}

const KEYS = ['1', '2', '3', '4', '5', '6', '7', '8', '9'] as const;

export function CodePad({
  value,
  onChange,
  length = 6,
  error = false,
  onComplete,
  label,
  secret = false,
}: Props) {
  const completed = useRef<string | null>(null);

  useEffect(() => {
    if (value.length === length && completed.current !== value) {
      completed.current = value;
      onComplete?.(value);
    }
    if (value.length < length) completed.current = null;
  }, [value, length, onComplete]);

  const press = (digit: string) => {
    if (value.length >= length) return;
    navigator.vibrate?.(12);
    onChange(value + digit);
  };

  const back = () => {
    navigator.vibrate?.(12);
    onChange(value.slice(0, -1));
  };

  return (
    <div>
      <p className="center" style={{ fontWeight: 700, fontSize: 19 }}>
        {label}
      </p>

      <div
        className="codepad__display"
        role="textbox"
        aria-label={label}
        aria-readonly="true"
        tabIndex={0}
        // A physical or Bluetooth keyboard still works: some fleets issue
        // rugged handsets with one, and the keypad is not the only way in.
        onKeyDown={(event) => {
          if (/^[0-9]$/.test(event.key)) press(event.key);
          else if (event.key === 'Backspace') back();
        }}
      >
        {Array.from({ length }).map((_, index) => {
          const digit = value[index];
          return (
            <span
              key={index}
              className={`codepad__cell ${digit ? 'codepad__cell--filled' : ''} ${
                error ? 'codepad__cell--error' : ''
              }`.trim()}
              aria-hidden="true"
            >
              {digit ? (secret ? '•' : digit) : ''}
            </span>
          );
        })}
      </div>

      <div className="codepad__keys">
        {KEYS.map((key) => (
          <button key={key} type="button" className="codepad__key" onClick={() => press(key)}>
            {key}
          </button>
        ))}
        <button
          type="button"
          className="codepad__key codepad__key--soft"
          onClick={() => onChange('')}
          aria-label="Clear"
        >
          ✕
        </button>
        <button type="button" className="codepad__key" onClick={() => press('0')}>
          0
        </button>
        <button
          type="button"
          className="codepad__key codepad__key--soft"
          onClick={back}
          aria-label="Delete one digit"
        >
          ⌫
        </button>
      </div>
    </div>
  );
}
