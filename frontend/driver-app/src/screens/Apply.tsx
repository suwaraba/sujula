/**
 * Applying to carry parcels, and waiting to hear back.
 *
 * The identity fields are required by the backend and the reason is worth
 * saying on the screen rather than hiding in a tooltip: a driver holds goods
 * worth more than they earn in a month and turns up at a buyer's family's
 * home. An application with nothing to check is not an application.
 *
 * One quirk of the platform shows up here and is handled rather than papered
 * over. `POST /driver/profile` is open to any signed-in account, but
 * `GET /driver/profile` needs the delivery role — which dispatch grants when it
 * approves the application. So between applying and being approved this app
 * cannot read its own profile back, and a 403 there means "still waiting"
 * rather than "something is broken".
 */

import { useState, type FormEvent } from 'react';
import { useApply, useDriverProfile } from '../api/queries';
import { ApiError } from '../api/http';
import { Banner, Button, Field, Spinner } from '../components/ui';
import { useSession } from '../auth/session';
import { useT } from '../i18n';
import type { ApplyRequest, VehicleType } from '../api/types';

const VEHICLES: Array<{ value: VehicleType; icon: string; label: string }> = [
  { value: 'MOTOR', icon: '🛵', label: 'Motorbike' },
  { value: 'BICI', icon: '🚲', label: 'Bicycle' },
  { value: 'TRICYCLE', icon: '🛺', label: 'Tricycle' },
  { value: 'CAR', icon: '🚗', label: 'Car' },
  { value: 'TAXI', icon: '🚕', label: 'Taxi' },
];

export function Apply() {
  const t = useT();
  const apply = useApply();
  const { signOut } = useSession();

  const [form, setForm] = useState<ApplyRequest>({
    phone: '',
    vehicleType: 'MOTOR',
    licenseNumber: '',
    idDocumentNumber: '',
    zone: '',
  });
  const [problem, setProblem] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  const set = <K extends keyof ApplyRequest>(key: K, value: ApplyRequest[K]) =>
    setForm((current) => ({ ...current, [key]: value }));

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setProblem(null);
    setFieldErrors({});
    try {
      await apply.mutateAsync({ body: form });
    } catch (failure) {
      if (failure instanceof ApiError) {
        setProblem(failure.message);
        setFieldErrors(failure.fieldErrors);
      } else {
        setProblem('Could not send your application. Try again when you have signal.');
      }
    }
  };

  if (apply.isSuccess) return <AwaitingReview />;

  return (
    <div className="screen">
      <h1>{t('apply.title')}</h1>
      <p className="muted">
        Dispatch checks these before you can carry anything. You are trusted with other people's
        goods and you will call at their families' homes.
      </p>

      {problem && <Banner tone="stop">{problem}</Banner>}

      <form onSubmit={submit}>
        <Field label="Your phone number" error={fieldErrors['phone']}>
          <input
            className="field__input"
            type="tel"
            inputMode="tel"
            autoComplete="tel"
            required
            value={form.phone}
            onChange={(event) => set('phone', event.target.value)}
          />
        </Field>

        <Field label="What do you drive?" error={fieldErrors['vehicleType']}>
          <div className="chips">
            {VEHICLES.map((vehicle) => (
              <button
                key={vehicle.value}
                type="button"
                className="chip"
                aria-pressed={form.vehicleType === vehicle.value}
                onClick={() => set('vehicleType', vehicle.value)}
              >
                <span aria-hidden="true">{vehicle.icon}</span>
                {vehicle.label}
              </button>
            ))}
          </div>
        </Field>

        <Field label="Plate number" hint="if it has one" error={fieldErrors['vehiclePlate']}>
          <input
            className="field__input"
            value={form.vehiclePlate ?? ''}
            onChange={(event) => set('vehiclePlate', event.target.value)}
          />
        </Field>

        <Field label="Driving licence number" error={fieldErrors['licenseNumber']}>
          <input
            className="field__input"
            required
            value={form.licenseNumber}
            onChange={(event) => set('licenseNumber', event.target.value)}
          />
        </Field>

        <Field label="Licence expires" error={fieldErrors['licenseExpiresOn']}>
          <input
            className="field__input"
            type="date"
            value={form.licenseExpiresOn ?? ''}
            onChange={(event) => set('licenseExpiresOn', event.target.value)}
          />
        </Field>

        <Field label="ID card or passport number" error={fieldErrors['idDocumentNumber']}>
          <input
            className="field__input"
            required
            value={form.idDocumentNumber}
            onChange={(event) => set('idDocumentNumber', event.target.value)}
          />
        </Field>

        <Field label="Which area do you cover?" hint="e.g. Serrekunda" error={fieldErrors['zone']}>
          <input
            className="field__input"
            required
            value={form.zone}
            onChange={(event) => set('zone', event.target.value)}
          />
        </Field>

        <Field label="Country" hint="two letters, like GM" error={fieldErrors['countryCode']}>
          <input
            className="field__input"
            maxLength={2}
            autoCapitalize="characters"
            value={form.countryCode ?? ''}
            onChange={(event) => set('countryCode', event.target.value.toUpperCase())}
          />
        </Field>

        <Field
          label="Someone who will answer if we cannot reach you"
          error={fieldErrors['nextOfKinName']}
        >
          <input
            className="field__input"
            placeholder="Name"
            value={form.nextOfKinName ?? ''}
            onChange={(event) => set('nextOfKinName', event.target.value)}
          />
        </Field>

        <Field label="Their phone number" error={fieldErrors['nextOfKinPhone']}>
          <input
            className="field__input"
            type="tel"
            inputMode="tel"
            value={form.nextOfKinPhone ?? ''}
            onChange={(event) => set('nextOfKinPhone', event.target.value)}
          />
        </Field>

        <Button tone="brand" major type="submit" busy={apply.isPending} icon="→">
          {t('apply.submit')}
        </Button>
      </form>

      <div style={{ marginTop: 24 }}>
        <Button tone="ghost" onClick={() => void signOut()}>
          {t('profile.signOut')}
        </Button>
      </div>
    </div>
  );
}

/** The wait between applying and being let in. */
export function AwaitingReview() {
  const t = useT();
  const { signOut } = useSession();
  const profile = useDriverProfile(true);

  return (
    <div className="screen screen--plain center">
      <span style={{ fontSize: 64 }} aria-hidden="true">
        ⏳
      </span>
      <h1>{t('apply.pending')}</h1>
      <p className="muted">
        {profile.data?.kyc?.whatIsNeeded ??
          profile.data?.message ??
          'You will get a message when this is done. You can close the app.'}
      </p>

      <div className="stack" style={{ marginTop: 24 }}>
        <Button tone="ghost" onClick={() => void profile.refetch()} busy={profile.isFetching}>
          Check again
        </Button>
        <Button tone="ghost" onClick={() => void signOut()}>
          {t('profile.signOut')}
        </Button>
      </div>
    </div>
  );
}

/** Suspended or rejected. Both end here, with the platform's own words. */
export function Blocked({ status, reason }: { status: 'SUSPENDED' | 'REJECTED'; reason?: string }) {
  const t = useT();
  const { signOut } = useSession();

  return (
    <div className="screen screen--plain center">
      <span style={{ fontSize: 64 }} aria-hidden="true">
        🚫
      </span>
      <h1>{status === 'SUSPENDED' ? t('apply.suspended') : t('apply.rejected')}</h1>
      {reason && <Banner tone="stop">{reason}</Banner>}
      <p className="muted">Call dispatch. They can tell you what happens next.</p>
      <Button tone="ghost" onClick={() => void signOut()}>
        {t('profile.signOut')}
      </Button>
    </div>
  );
}

export function ProfileLoading() {
  return (
    <div className="screen screen--plain">
      <Spinner />
    </div>
  );
}
