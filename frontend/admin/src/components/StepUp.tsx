import { useAuth } from '@/auth/AuthContext';
import { Field, TextInput } from './forms';

export interface StepUpValue {
  password: string;
  totpCode: string;
}

export const EMPTY_STEP_UP: StepUpValue = { password: '', totpCode: '' };

/**
 * Re-asks for the administrator's own credentials.
 *
 * Six operations on this surface take these: a refund, preparing a payout run,
 * releasing one, deciding a dispute, clearing somebody's second factor, and
 * recording a handover nobody could prove. They are the ones that move money or
 * that the next person to look cannot undo.
 *
 * The code field is only shown when the signed-in administrator actually has a
 * second factor — the server asks for it only then — but it is never hidden as
 * a shortcut past it: an admin account without MFA on a console that can refund
 * a payment is a finding, and the note says so.
 */
export function StepUpFields({
  value,
  onChange,
  what,
}: {
  value: StepUpValue;
  onChange(value: StepUpValue): void;
  /** What is being confirmed, in the administrator's own words. */
  what: string;
}) {
  const { profile } = useAuth();
  const hasMfa = profile?.mfaEnabled ?? false;

  return (
    <fieldset className="step-up">
      <legend>Confirm it is you</legend>
      <p className="field-hint">
        {what} cannot be undone by the next person to look, so it is confirmed against your own
        credentials rather than your open session.
      </p>

      <Field label="Your password" required>
        <TextInput
          type="password"
          autoComplete="current-password"
          required
          value={value.password}
          onChange={(password) => onChange({ ...value, password })}
        />
      </Field>

      {hasMfa ? (
        <Field label="Your authenticator code" required>
          <TextInput
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={6}
            required
            value={value.totpCode}
            onChange={(totpCode) => onChange({ ...value, totpCode })}
          />
        </Field>
      ) : (
        <p className="notice notice-warning">
          This account has no second factor. Turn one on: a password alone is the whole of what
          stands between a stolen session and the money on this screen.
        </p>
      )}
    </fieldset>
  );
}

/** Shapes the fields for the wire: an empty code is absent, not blank. */
export function stepUpBody(value: StepUpValue): { password: string; totpCode?: string } {
  return value.totpCode.trim()
    ? { password: value.password, totpCode: value.totpCode.trim() }
    : { password: value.password };
}
