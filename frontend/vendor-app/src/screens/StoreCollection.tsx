import { useEffect, useState, type FormEvent } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { storeApi } from '@/api/endpoints/store';
import { ApiError } from '@/api/errors';
import { useStore } from '@/store/StoreProvider';
import { useCountries } from '@/lib/hooks';
import { formatDateTime, humanise } from '@/lib/format';
import type { DayOfWeek, StoreAddress } from '@/api/types';
import { Badge, Button, Card, KeyValue, Notice, PageHeader, Skeleton } from '@/components/ui';
import { SelectField, TextArea, TextField, fieldError } from '@/components/form';
import { CoordinateFields, parseCoordinate } from '@/components/LocationPicker';
import { useToast } from '@/components/Toast';

const DAYS: DayOfWeek[] = [
  'MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY',
];

/**
 * The collection point, which is the most consequential address in the app.
 *
 * It is the origin every delivery leg is priced and routed from, and the
 * coordinate stamped onto every listing written afterwards. A shop whose pin is
 * a geocoder's guess quietly produces a catalogue of listings whose pins are
 * the same guess — so this screen says what state the pin is in rather than
 * showing two blank number fields.
 */
export function StoreCollection() {
  const { store, storeId, isLoading, collectionPoint } = useStore();
  const queryClient = useQueryClient();
  const toast = useToast();
  const countries = useCountries();

  const [separate, setSeparate] = useState(false);
  const [form, setForm] = useState({
    street: '', city: '', state: '', postalCode: '', countryCode: '',
    latitude: '', longitude: '', instructions: '',
  });
  const [hours, setHours] = useState<Record<DayOfWeek, { closed: boolean; opensAt: string; closesAt: string }>>(
    () => Object.fromEntries(
      DAYS.map((day) => [day, { closed: false, opensAt: '09:00', closesAt: '18:00' }]),
    ) as never,
  );
  const [error, setError] = useState<string | null>(null);
  const [fields, setFields] = useState<Record<string, string>>({});

  useEffect(() => {
    if (!store) return;
    const pickup = store.pickupAddress;
    setSeparate(pickup != null);
    const source = pickup ?? store.address;
    setForm({
      street: source?.street ?? '',
      city: source?.city ?? '',
      state: source?.state ?? '',
      postalCode: source?.postalCode ?? '',
      countryCode: source?.countryCode ?? '',
      latitude: source?.latitude != null ? String(source.latitude) : '',
      longitude: source?.longitude != null ? String(source.longitude) : '',
      instructions: source?.instructions ?? '',
    });

    const next = Object.fromEntries(
      DAYS.map((day) => {
        const existing = store.operatingHours?.find((h) => h.day === day);
        return [day, {
          closed: existing?.closed ?? false,
          opensAt: existing?.opensAt?.slice(0, 5) ?? '09:00',
          closesAt: existing?.closesAt?.slice(0, 5) ?? '18:00',
        }];
      }),
    ) as never;
    setHours(next);
  }, [store]);

  const save = useMutation({
    mutationFn: () => {
      const latitude = parseCoordinate(form.latitude, 90);
      const longitude = parseCoordinate(form.longitude, 180);

      return storeApi.update(storeId, {
        pickupAddress: separate
          ? {
              clear: false,
              ...(form.street.trim() ? { street: form.street.trim() } : {}),
              ...(form.city.trim() ? { city: form.city.trim() } : {}),
              ...(form.state.trim() ? { state: form.state.trim() } : {}),
              ...(form.postalCode.trim() ? { postalCode: form.postalCode.trim() } : {}),
              ...(form.countryCode ? { countryCode: form.countryCode } : {}),
              ...(latitude !== undefined ? { latitude } : {}),
              ...(longitude !== undefined ? { longitude } : {}),
              ...(form.instructions.trim() ? { instructions: form.instructions.trim() } : {}),
            }
          // `clear: true` is how the server is told to go back to collecting
          // from the shop address — an omitted object means "leave it alone".
          : { clear: true },
        operatingHours: DAYS.map((day) => ({
          day,
          closed: hours[day]!.closed,
          ...(hours[day]!.closed
            ? {}
            : { opensAt: `${hours[day]!.opensAt}:00`, closesAt: `${hours[day]!.closesAt}:00` }),
        })),
      });
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['store'] });
      toast.success('Collection point saved.');
    },
    onError: (cause) => {
      if (cause instanceof ApiError) {
        setError(cause.message);
        setFields(cause.fieldErrors);
      } else {
        setError('Could not save the collection point.');
      }
    },
  });

  if (isLoading || !store) {
    return <div className="page stack"><Skeleton height={32} width={220} /><Skeleton height={320} /></div>;
  }

  const set = (name: keyof typeof form) => (event: { target: { value: string } }) =>
    setForm((current) => ({ ...current, [name]: event.target.value }));

  function onSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setFields({});
    save.mutate();
  }

  return (
    <div className="page stack">
      <PageHeader
        title="Collection point"
        subtitle="Where a driver comes to collect — and where every listing you write is placed on the map."
      />

      {collectionPoint && <PinState address={collectionPoint} />}

      <form className="stack" onSubmit={onSubmit} noValidate>
        {error && <Notice tone="danger">{error}</Notice>}

        <Card title="Your shop's registered address">
          <KeyValue
            rows={[
              ['Street', store.address?.street ?? '—'],
              ['Town', store.address?.city ?? '—'],
              ['Country', store.address?.countryCode ?? '—'],
            ]}
          />
          <p className="small muted" style={{ marginTop: 'var(--space-3)' }}>
            Set when the shop opened. Contact support to change it — it is on your registration.
          </p>
        </Card>

        <Card title="Where drivers actually collect">
          <div className="stack">
            <label className="checkbox">
              <input
                type="checkbox"
                checked={separate}
                onChange={(event) => setSeparate(event.target.checked)}
              />
              <span className="checkbox__text">
                Collect from somewhere other than my registered address
                <span className="checkbox__hint">
                  A warehouse, a market stall, a relative's compound. Leave this off and drivers
                  come to the address above.
                </span>
              </span>
            </label>

            {separate && (
              <>
                <TextField
                  label="Street" value={form.street} onChange={set('street')}
                  error={fieldError(fields, 'pickupAddress.street', 'street')} maxLength={255}
                />
                <div className="grid grid--2">
                  <TextField
                    label="Town or city" value={form.city} onChange={set('city')}
                    error={fieldError(fields, 'pickupAddress.city', 'city')} maxLength={100}
                  />
                  <TextField
                    label="Region" value={form.state} onChange={set('state')}
                    error={fieldError(fields, 'pickupAddress.state', 'state')} maxLength={100}
                  />
                </div>
                <div className="grid grid--2">
                  <TextField
                    label="Postal code" value={form.postalCode} onChange={set('postalCode')}
                    error={fieldError(fields, 'pickupAddress.postalCode')} maxLength={20}
                  />
                  <SelectField
                    label="Country" value={form.countryCode} onChange={set('countryCode')}
                    error={fieldError(fields, 'pickupAddress.countryCode')}
                  >
                    <option value="">Choose a country</option>
                    {(countries.data?.countries ?? []).map((country) => (
                      <option key={country.code} value={country.code}>{country.name}</option>
                    ))}
                  </SelectField>
                </div>

                <CoordinateFields
                  latitude={form.latitude}
                  longitude={form.longitude}
                  onLatitude={(value) => setForm((c) => ({ ...c, latitude: value }))}
                  onLongitude={(value) => setForm((c) => ({ ...c, longitude: value }))}
                  label="Drop the pin"
                  hint="Stand where the driver will wait and tap the button."
                />

                <TextArea
                  label="How to find you"
                  hint="Read by the driver. 'Blue gate opposite the mosque, ask for Lamin.'"
                  value={form.instructions} onChange={set('instructions')}
                  maxLength={400} rows={3}
                />
              </>
            )}
          </div>
        </Card>

        <Card title="When you are open">
          <div className="stack stack--tight">
            <p className="small muted">
              Drivers are sent inside these hours. A parcel nobody is there to hand over is a
              wasted trip and a late delivery.
            </p>
            {DAYS.map((day) => (
              <div key={day} className="row" style={{ gap: 'var(--space-3)' }}>
                <span style={{ width: 96, fontWeight: 600, fontSize: 14 }}>
                  {humanise(day).slice(0, 3)}
                </span>
                <label className="row small" style={{ gap: 'var(--space-2)', cursor: 'pointer' }}>
                  <input
                    type="checkbox"
                    checked={!hours[day]!.closed}
                    onChange={(event) =>
                      setHours((current) => ({
                        ...current,
                        [day]: { ...current[day]!, closed: !event.target.checked },
                      }))
                    }
                  />
                  Open
                </label>
                {!hours[day]!.closed && (
                  <>
                    <input
                      type="time" className="input" style={{ width: 130 }}
                      value={hours[day]!.opensAt}
                      aria-label={`${humanise(day)} opens at`}
                      onChange={(event) =>
                        setHours((current) => ({
                          ...current,
                          [day]: { ...current[day]!, opensAt: event.target.value },
                        }))
                      }
                    />
                    <span className="muted">to</span>
                    <input
                      type="time" className="input" style={{ width: 130 }}
                      value={hours[day]!.closesAt}
                      aria-label={`${humanise(day)} closes at`}
                      onChange={(event) =>
                        setHours((current) => ({
                          ...current,
                          [day]: { ...current[day]!, closesAt: event.target.value },
                        }))
                      }
                    />
                  </>
                )}
              </div>
            ))}
          </div>
        </Card>

        <Button type="submit" variant="primary" busy={save.isPending}>Save collection point</Button>
      </form>
    </div>
  );
}

function PinState({ address }: { address: StoreAddress }) {
  if (!address.dispatchable) {
    return (
      <Notice tone="danger" title="No driver can be routed here">
        Nothing can be collected from this address as it stands. Check the street and town, then
        drop the pin by standing at the shop.
      </Notice>
    );
  }

  if (address.needsPinConfirmation) {
    return (
      <Notice tone="warn" title="This pin was guessed, not placed">
        We worked out roughly where this is from the street name — confidence{' '}
        {humanise(address.confidence).toLowerCase()}. Drivers are sent to that guess, and every
        listing you write inherits it. Drop the pin below.
      </Notice>
    );
  }

  return (
    <Notice tone="ok" title="Pin confirmed">
      <span className="mono">
        {address.latitude?.toFixed(6)}, {address.longitude?.toFixed(6)}
      </span>
      {address.geocodedAt && (
        <> · placed {formatDateTime(address.geocodedAt)}</>
      )}
      {' '}
      <Badge tone="ok">{humanise(address.confidence)}</Badge>
    </Notice>
  );
}
