import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { pickupApi } from '@/api/endpoints';
import { ApiError } from '@/api/errors';
import { useSession } from '@/auth/session';
import { useCountries, useEventId } from '@/lib/hooks';
import { Button, Card, Notice, PageHeader } from '@/components/ui';
import { SelectField, TextArea, TextField, fieldError } from '@/components/form';

/**
 * Applying to hold other people's parcels.
 *
 * The position is required and is not the address. In this market most
 * addresses do not resolve to a point, so asking for one and deriving the other
 * would fail on the ordinary case — and the position is what a driver navigates
 * to and what the collection geofence is measured against. That is why the
 * button that takes it from the device is the prominent one: an operator
 * standing in their own shop is the most accurate this will ever be.
 */
export function Apply() {
  const { me, signOut } = useSession();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const countries = useCountries();
  const [eventId, resetEventId] = useEventId();

  const [form, setForm] = useState({
    name: '',
    addressStreet: '',
    addressApartment: '',
    city: '',
    state: '',
    postalCode: '',
    countryCode: me?.profile.countryCode ?? 'GM',
    lat: '',
    lng: '',
    contactPhone: me?.profile.phone ?? '',
    contactEmail: me?.profile.email ?? '',
    openingHours: '',
    capacity: '50',
  });
  const [error, setError] = useState<string | null>(null);
  const [fields, setFields] = useState<Record<string, string>>({});
  const [locating, setLocating] = useState(false);
  const [locationError, setLocationError] = useState<string | null>(null);
  const [submitted, setSubmitted] = useState<string | null>(null);

  const set = (name: keyof typeof form) => (event: { target: { value: string } }) =>
    setForm((current) => ({ ...current, [name]: event.target.value }));

  function useMyLocation() {
    if (!navigator.geolocation) {
      setLocationError('This device cannot report its position. Type the numbers instead.');
      return;
    }
    setLocating(true);
    setLocationError(null);
    navigator.geolocation.getCurrentPosition(
      (position) => {
        setForm((current) => ({
          ...current,
          lat: position.coords.latitude.toFixed(6),
          lng: position.coords.longitude.toFixed(6),
        }));
        setLocating(false);
      },
      (cause) => {
        setLocating(false);
        setLocationError(
          cause.code === cause.PERMISSION_DENIED
            ? 'Location permission was refused. Allow it and try again, or type the numbers.'
            : 'Could not read the position. Type the numbers instead.',
        );
      },
      { enableHighAccuracy: true, timeout: 15_000, maximumAge: 0 },
    );
  }

  const apply = useMutation({
    mutationFn: () =>
      pickupApi.apply(
        {
          name: form.name.trim(),
          addressStreet: form.addressStreet.trim(),
          ...(form.addressApartment.trim() ? { addressApartment: form.addressApartment.trim() } : {}),
          city: form.city.trim(),
          ...(form.state.trim() ? { state: form.state.trim() } : {}),
          ...(form.postalCode.trim() ? { postalCode: form.postalCode.trim() } : {}),
          countryCode: form.countryCode,
          lat: Number(form.lat),
          lng: Number(form.lng),
          contactPhone: form.contactPhone.trim(),
          ...(form.contactEmail.trim() ? { contactEmail: form.contactEmail.trim() } : {}),
          ...(form.openingHours.trim() ? { openingHours: form.openingHours.trim() } : {}),
          ...(form.capacity.trim() ? { capacity: Number(form.capacity) } : {}),
        },
        eventId,
      ),
    onSuccess: (result) => {
      setSubmitted(result.whatHappensNext ?? 'Somebody will check the counter is real.');
      void queryClient.invalidateQueries({ queryKey: ['my-points'] });
    },
    onError: (cause) => {
      resetEventId();
      if (cause instanceof ApiError) {
        setError(cause.message);
        setFields(cause.fieldErrors);
      } else {
        setError('Could not send the application.');
      }
    },
  });

  const hasPin = form.lat.trim() !== '' && form.lng.trim() !== '';

  function onSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setFields({});
    apply.mutate();
  }

  if (submitted) {
    return (
      <div className="auth-screen">
        <div className="auth-card">
          <Card title="Application sent">
            <div className="stack">
              <Notice tone="ok">{submitted}</Notice>
              <p className="muted">
                Nothing is sent to your counter until it has been approved. You can sign in again
                any time to see where it has got to.
              </p>
              <Button variant="primary" block onClick={() => navigate('/', { replace: true })}>
                Continue
              </Button>
            </div>
          </Card>
        </div>
      </div>
    );
  }

  return (
    <div className="shell">
      <header className="shell__header">
        <div className="shell__brand">
          <span className="shell__mark" aria-hidden="true">S</span>
          <span>Counter</span>
        </div>
        <div className="shell__spacer" />
        <Button variant="ghost" size="sm" onClick={() => void signOut()}>Sign out</Button>
      </header>

      <main className="shell__main" style={{ paddingBottom: 'var(--space-7)' }}>
        <div className="page" style={{ maxWidth: 680, margin: '0 auto' }}>
          <PageHeader
            title="Run a pickup counter"
            subtitle="Hold parcels for people near you, and earn a commission on each one collected."
          />

          <form className="stack" onSubmit={onSubmit} noValidate>
            {error && <Notice tone="danger">{error}</Notice>}

            <Card title="The counter">
              <div className="stack">
                <TextField
                  label="What it is called"
                  hint="What shoppers will look for. Usually the shop's own name."
                  value={form.name} onChange={set('name')}
                  error={fieldError(fields, 'name')} maxLength={150} required
                />
                <TextField
                  label="Street" value={form.addressStreet} onChange={set('addressStreet')}
                  error={fieldError(fields, 'addressStreet')} maxLength={300} required
                />
                <TextField
                  label="Unit or shop number" value={form.addressApartment}
                  onChange={set('addressApartment')}
                  error={fieldError(fields, 'addressApartment')} maxLength={100}
                />
                <div className="grid grid--2">
                  <TextField
                    label="Town" value={form.city} onChange={set('city')}
                    error={fieldError(fields, 'city')} maxLength={120} required
                  />
                  <TextField
                    label="Region" value={form.state} onChange={set('state')}
                    error={fieldError(fields, 'state')} maxLength={120}
                  />
                </div>
                <div className="grid grid--2">
                  <TextField
                    label="Postal code" value={form.postalCode} onChange={set('postalCode')}
                    error={fieldError(fields, 'postalCode')} maxLength={20}
                  />
                  <SelectField
                    label="Country" value={form.countryCode} onChange={set('countryCode')}
                    error={fieldError(fields, 'countryCode')}
                  >
                    {(countries.data?.countries ?? [])
                      .filter((country) => country.ship || country.buy)
                      .map((country) => (
                        <option key={country.code} value={country.code}>{country.name}</option>
                      ))}
                  </SelectField>
                </div>
              </div>
            </Card>

            <Card title="Exactly where it is">
              <div className="stack">
                <Notice tone="info" title="A position, not just an address">
                  Most addresses here do not resolve to a point on a map. This is what a driver
                  navigates to, so stand at the counter and take it from the device.
                </Notice>

                <Button
                  type="button" variant="secondary" onClick={useMyLocation} busy={locating}
                >
                  ◉ Use my position
                </Button>

                <div className="grid grid--2">
                  <TextField
                    label="Latitude" inputMode="decimal" placeholder="13.442900"
                    value={form.lat} onChange={set('lat')}
                    error={fieldError(fields, 'lat')} required
                  />
                  <TextField
                    label="Longitude" inputMode="decimal" placeholder="-16.677600"
                    value={form.lng} onChange={set('lng')}
                    error={fieldError(fields, 'lng')} required
                  />
                </div>

                {locationError && <div className="field__error">{locationError}</div>}
                {!hasPin && !locationError && (
                  <Notice tone="warn">
                    Without this, no driver can be sent to you.
                  </Notice>
                )}
              </div>
            </Card>

            <Card title="How you run it">
              <div className="stack">
                <div className="grid grid--2">
                  <TextField
                    label="Phone" hint="How drivers reach you."
                    type="tel" inputMode="tel"
                    value={form.contactPhone} onChange={set('contactPhone')}
                    error={fieldError(fields, 'contactPhone')} maxLength={30} required
                  />
                  <TextField
                    label="Email" type="email" inputMode="email" autoCapitalize="none"
                    value={form.contactEmail} onChange={set('contactEmail')}
                    error={fieldError(fields, 'contactEmail')} maxLength={200}
                  />
                </div>
                <TextArea
                  label="Opening hours"
                  placeholder="Mon–Sat 08:00–20:00, Sun closed"
                  value={form.openingHours} onChange={set('openingHours')}
                  error={fieldError(fields, 'openingHours')} maxLength={500} rows={2}
                />
                <TextField
                  label="How many parcels you can hold at once"
                  hint="Be honest — a counter that turns parcels away stops being offered them."
                  type="number" inputMode="numeric" min="1" max="5000"
                  value={form.capacity} onChange={set('capacity')}
                  error={fieldError(fields, 'capacity')}
                />
              </div>
            </Card>

            <Button type="submit" variant="primary" block busy={apply.isPending} disabled={!hasPin}>
              Send the application
            </Button>
            <p className="small muted center">
              The counter is switched off until somebody has checked it is real.
            </p>
          </form>
        </div>
      </main>
    </div>
  );
}
