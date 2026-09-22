/**
 * The driver's own page: their vehicle, their score, what is still waiting to
 * be sent, and the way out.
 *
 * The queue is here rather than hidden in a settings screen because signing out
 * destroys it. A driver who ends their shift with four unsent handovers and
 * taps "sign out" has thrown away the only record that four parcels changed
 * hands — so this screen shows the count, names each one, and the sign-out
 * button says what it will cost before it does it.
 *
 * The score is shown for the same reason the backend returns it: it decides
 * what a driver is offered, and a score somebody is judged by without being
 * able to see it is not a score, it is a secret.
 */

import { useEffect, useState } from 'react';
import { useDriverProfile, useUpdateDriverProfile } from '../api/queries';
import { useSession } from '../auth/session';
import { Banner, Button, Card, Field, Pill, Spinner, useToast } from '../components/ui';
import { Sheet } from '../components/Sheet';
import { ConnectionBar, TopBar } from '../components/Chrome';
import { LanguagePicker } from './parts/LanguagePicker';
import * as outbox from '../offline/outbox';
import type { OutboxEvent } from '../offline/db';
import { sinceShort } from '../lib/format';
import { useOnline } from '../lib/connectivity';
import { useT } from '../i18n';
import type { VehicleType } from '../api/types';

const VEHICLES: Array<{ value: VehicleType; icon: string; label: string }> = [
  { value: 'MOTOR', icon: '🛵', label: 'Motorbike' },
  { value: 'BICI', icon: '🚲', label: 'Bicycle' },
  { value: 'TRICYCLE', icon: '🛺', label: 'Tricycle' },
  { value: 'CAR', icon: '🚗', label: 'Car' },
  { value: 'TAXI', icon: '🚕', label: 'Taxi' },
];

export function Me() {
  const t = useT();
  const toast = useToast();
  const online = useOnline();
  const { signOut } = useSession();
  const profile = useDriverProfile();
  const update = useUpdateDriverProfile();

  const [queue, setQueue] = useState<OutboxEvent[]>([]);
  const [editing, setEditing] = useState(false);
  const [confirmSignOut, setConfirmSignOut] = useState(false);
  const [zone, setZone] = useState('');
  const [vehicle, setVehicle] = useState<VehicleType>('MOTOR');

  useEffect(() => outbox.subscribe(setQueue), []);

  useEffect(() => {
    if (profile.data) {
      setZone(profile.data.zone ?? '');
      setVehicle(profile.data.vehicleType ?? 'MOTOR');
    }
  }, [profile.data]);

  const unsent = queue.filter((event) => event.state !== 'rejected');
  const rejected = queue.filter((event) => event.state === 'rejected');

  const save = async () => {
    try {
      await update.mutateAsync({ zone, vehicleType: vehicle });
      toast('Saved.', 'go');
      setEditing(false);
    } catch {
      toast('Could not save that.', 'stop');
    }
  };

  return (
    <>
      <ConnectionBar />
      <TopBar title={t('profile.title')} />

      <div className="screen">
        {profile.isLoading && <Spinner />}

        {profile.data && (
          <Card>
            <div className="card__row">
              <span className="card__title">{profile.data.phone ?? 'Driver'}</span>
              <Pill
                tone={
                  profile.data.status === 'APPROVED' || profile.data.status === 'ACTIVE'
                    ? 'go'
                    : profile.data.status === 'PENDING'
                      ? 'warn'
                      : 'stop'
                }
                icon={profile.data.status === 'PENDING' ? '⏳' : '🪪'}
              >
                {profile.data.status}
              </Pill>
            </div>
            <p className="card__meta">
              {VEHICLES.find((v) => v.value === profile.data?.vehicleType)?.icon}{' '}
              {profile.data.vehicleModel ?? profile.data.vehicleType}
              {profile.data.vehiclePlate && ` · ${profile.data.vehiclePlate}`}
            </p>
            <p className="card__meta">
              📍 {profile.data.zone ?? '—'}
              {profile.data.countryCode && ` (${profile.data.countryCode})`}
            </p>
            {profile.data.score?.acceptancePercent && (
              <p className="card__meta">
                {t('profile.score', { percent: profile.data.score.acceptancePercent })}
              </p>
            )}
            <Button tone="ghost" icon="✏️" onClick={() => setEditing(true)}>
              Change
            </Button>
          </Card>
        )}

        <h2 style={{ marginTop: 24 }}>{t('outbox.title')}</h2>

        {unsent.length === 0 && rejected.length === 0 && (
          <Banner tone="go" icon="✅">
            {t('outbox.empty')}
          </Banner>
        )}

        {unsent.length > 0 && (
          <Banner tone="warn" icon="⬆️">
            {t('app.pending', { count: unsent.length })}
            {!online && ' — waiting for signal'}
          </Banner>
        )}

        {queue.map((event) => (
          <Card key={event.clientEventId}>
            <div className="card__row">
              <div>
                <div style={{ fontWeight: 700 }}>{event.label ?? event.type}</div>
                <div className="card__meta">
                  {sinceShort(event.capturedAtMs)} ago
                  {event.hasPhoto && ' · 📷'}
                </div>
              </div>
              {event.state === 'rejected' ? (
                <Pill tone="stop" icon="⚠️">
                  Refused
                </Pill>
              ) : (
                <Pill tone="warn" icon="⏳">
                  Waiting
                </Pill>
              )}
            </div>

            {event.state === 'rejected' && (
              <>
                <Banner tone="stop">
                  {t('outbox.rejected', { problem: event.lastProblem ?? '' })}
                </Banner>
                <div className="button-row">
                  <Button tone="ghost" onClick={() => void outbox.retry(event.clientEventId)}>
                    {t('app.retry')}
                  </Button>
                  <Button tone="ghost" onClick={() => void outbox.discard(event.clientEventId)}>
                    {t('outbox.discard')}
                  </Button>
                </div>
              </>
            )}
          </Card>
        ))}

        {unsent.length > 0 && (
          <Button tone="brand" icon="⬆️" disabled={!online} onClick={() => void outbox.flush()}>
            {t('outbox.sendNow')}
          </Button>
        )}

        <h2 style={{ marginTop: 24 }}>Language</h2>
        <LanguagePicker />

        <div style={{ marginTop: 32 }}>
          <Button tone="stop" icon="🚪" onClick={() => setConfirmSignOut(true)}>
            {t('profile.signOut')}
          </Button>
        </div>
      </div>

      <Sheet open={editing} onClose={() => setEditing(false)} title="Change your details">
        <Field label={t('profile.vehicle')}>
          <div className="chips">
            {VEHICLES.map((option) => (
              <button
                key={option.value}
                type="button"
                className="chip"
                aria-pressed={vehicle === option.value}
                onClick={() => setVehicle(option.value)}
              >
                <span aria-hidden="true">{option.icon}</span>
                {option.label}
              </button>
            ))}
          </div>
        </Field>

        <Field label={t('profile.zone')}>
          <input
            className="field__input"
            value={zone}
            onChange={(event) => setZone(event.target.value)}
          />
        </Field>

        <Button tone="go" major busy={update.isPending} onClick={() => void save()}>
          Save
        </Button>
      </Sheet>

      <Sheet
        open={confirmSignOut}
        onClose={() => setConfirmSignOut(false)}
        title={t('profile.signOut')}
      >
        {unsent.length > 0 ? (
          <Banner tone="stop" icon="⚠️">
            {t('profile.signOutWarning', { count: unsent.length })}
          </Banner>
        ) : (
          <p className="muted">Everything you recorded has been sent.</p>
        )}

        <div className="stack">
          <Button tone="ghost" onClick={() => setConfirmSignOut(false)}>
            {t('app.cancel')}
          </Button>
          <Button tone="stop" icon="🚪" onClick={() => void signOut()}>
            {t('profile.signOut')}
          </Button>
        </div>
      </Sheet>
    </>
  );
}
