import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { storeApi } from '@/api/endpoints/store';
import { useAuth } from '@/auth/AuthProvider';
import { ApiError } from '@/api/errors';
import { useCountries, useCurrencies, useIdempotencyKey } from '@/lib/hooks';
import { Button, Card, Notice, PageHeader } from '@/components/ui';
import { SelectField, TextArea, TextField, fieldError } from '@/components/form';
import { CoordinateFields, parseCoordinate } from '@/components/LocationPicker';

/**
 * Opening a shop.
 *
 * Where a seller who has no store lands. Two things on this form are load
 * bearing beyond the obvious:
 *
 * The **address and its pin** are not contact details. They are the origin of
 * every collection, the coordinate stamped onto every listing written
 * afterwards, and what decides whether a driver can be routed here at all.
 *
 * The **settlement currency** is fixed at application, and the form says so.
 * It is the currency every listing is priced in and every payout is made in —
 * the same thing by construction — so it cannot be changed later without
 * re-denominating a catalogue and a ledger.
 */
export function Apply() {
  const { me, reload } = useAuth();
  const navigate = useNavigate();
  const countries = useCountries();
  const { settlementCurrencies } = useCurrencies();
  const [idempotencyKey, resetKey] = useIdempotencyKey();

  // An account can reach this screen with a store already pending — the gate
  // only sends people here when `vendorId` is absent, but a refresh mid-flight
  // could race it. Asking is cheaper than creating a second shop.
  const existing = useQuery({
    queryKey: ['vendor-profile'],
    queryFn: () => storeApi.myVendorProfile(),
    retry: false,
  });

  const [form, setForm] = useState({
    storeName: '',
    description: '',
    storeEmail: me?.profile.email ?? '',
    storePhone: me?.profile.phone ?? '',
    website: '',
    addressStreet: '',
    addressCity: '',
    addressState: '',
    addressPostalCode: '',
    addressCountryCode: me?.profile.countryCode ?? 'GM',
    latitude: '',
    longitude: '',
    settlementCurrency: '',
    businessRegistrationNumber: '',
    taxNumber: '',
  });

  const [error, setError] = useState<string | null>(null);
  const [fields, setFields] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  const set = (key: keyof typeof form) => (event: { target: { value: string } }) =>
    setForm((current) => ({ ...current, [key]: event.target.value }));

  if (existing.data) {
    return <AlreadyApplied storeName={existing.data.storeName} status={existing.data.status} />;
  }

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setFields({});
    setBusy(true);

    const latitude = parseCoordinate(form.latitude, 90);
    const longitude = parseCoordinate(form.longitude, 180);

    if ((form.latitude.trim() !== '' || form.longitude.trim() !== '') &&
        (latitude === undefined || longitude === undefined)) {
      setError('A pin needs both a latitude and a longitude, each within range.');
      setBusy(false);
      return;
    }

    try {
      await storeApi.create(
        {
          storeName: form.storeName.trim(),
          ...(form.description.trim() ? { description: form.description.trim() } : {}),
          ...(form.storeEmail.trim() ? { storeEmail: form.storeEmail.trim() } : {}),
          ...(form.storePhone.trim() ? { storePhone: form.storePhone.trim() } : {}),
          ...(form.website.trim() ? { website: form.website.trim() } : {}),
          addressStreet: form.addressStreet.trim(),
          addressCity: form.addressCity.trim(),
          ...(form.addressState.trim() ? { addressState: form.addressState.trim() } : {}),
          ...(form.addressPostalCode.trim() ? { addressPostalCode: form.addressPostalCode.trim() } : {}),
          addressCountryCode: form.addressCountryCode,
          ...(latitude !== undefined && longitude !== undefined ? { latitude, longitude } : {}),
          ...(form.settlementCurrency ? { settlementCurrency: form.settlementCurrency } : {}),
          ...(form.businessRegistrationNumber.trim()
            ? { businessRegistrationNumber: form.businessRegistrationNumber.trim() } : {}),
          ...(form.taxNumber.trim() ? { taxNumber: form.taxNumber.trim() } : {}),
        },
        idempotencyKey,
      );

      // The gate reads `vendorId` off `/me`, so the app only re-routes once
      // that has been re-read.
      await reload();
      navigate('/store/verification', { replace: true });
    } catch (cause) {
      resetKey();
      if (cause instanceof ApiError) {
        setError(cause.message);
        setFields(cause.fieldErrors);
      } else {
        setError('Could not open the shop. Try again.');
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="shell">
      <header className="shell__header">
        <div className="shell__brand">
          <span className="shell__brand-mark" aria-hidden="true">S</span>
          <span>Sujula</span>
        </div>
        <div className="shell__spacer" />
        <SignOutLink />
      </header>

      <main className="shell__main" style={{ paddingBottom: 'var(--space-7)' }}>
        <div className="page" style={{ maxWidth: 720, margin: '0 auto' }}>
          <PageHeader
            title="Open your shop"
            subtitle="Tell us about the business. Once your documents are accepted you can list and sell."
          />

          <form className="stack" onSubmit={onSubmit} noValidate>
            {error && <Notice tone="danger">{error}</Notice>}

            <Card title="The shop">
              <div className="stack">
                <TextField
                  label="Shop name" value={form.storeName} onChange={set('storeName')}
                  hint="What buyers see on your listings."
                  error={fieldError(fields, 'storeName')} maxLength={100} required
                />
                <TextArea
                  label="What you sell" value={form.description} onChange={set('description')}
                  error={fieldError(fields, 'description')} maxLength={2000} rows={3}
                />
                <div className="grid grid--2">
                  <TextField
                    label="Shop email" type="email" value={form.storeEmail} onChange={set('storeEmail')}
                    inputMode="email" autoCapitalize="none" error={fieldError(fields, 'storeEmail')}
                  />
                  <TextField
                    label="Shop phone" type="tel" value={form.storePhone} onChange={set('storePhone')}
                    inputMode="tel" error={fieldError(fields, 'storePhone')}
                  />
                </div>
                <TextField
                  label="Website" type="url" value={form.website} onChange={set('website')}
                  placeholder="https://" inputMode="url" autoCapitalize="none"
                  error={fieldError(fields, 'website')}
                />
              </div>
            </Card>

            <Card title="Where the goods are">
              <div className="stack">
                <Notice tone="info" title="This is not just a contact address">
                  Every collection starts here, and every listing you write is placed on the map
                  here unless you say otherwise. Get it right once and you never retype it.
                </Notice>

                <TextField
                  label="Street" value={form.addressStreet} onChange={set('addressStreet')}
                  hint="The compound, shop or market stall a driver walks up to."
                  error={fieldError(fields, 'addressStreet')} maxLength={255} required
                />
                <div className="grid grid--2">
                  <TextField
                    label="Town or city" value={form.addressCity} onChange={set('addressCity')}
                    error={fieldError(fields, 'addressCity')} maxLength={100} required
                  />
                  <TextField
                    label="Region" value={form.addressState} onChange={set('addressState')}
                    error={fieldError(fields, 'addressState')} maxLength={100}
                  />
                </div>
                <div className="grid grid--2">
                  <TextField
                    label="Postal code" value={form.addressPostalCode} onChange={set('addressPostalCode')}
                    error={fieldError(fields, 'addressPostalCode')} maxLength={20}
                  />
                  <SelectField
                    label="Country" value={form.addressCountryCode} onChange={set('addressCountryCode')}
                    error={fieldError(fields, 'addressCountryCode')} required
                  >
                    {(countries.data?.countries ?? [])
                      .filter((country) => country.buy || country.ship)
                      .map((country) => (
                        <option key={country.code} value={country.code}>{country.name}</option>
                      ))}
                  </SelectField>
                </div>

                <CoordinateFields
                  latitude={form.latitude}
                  longitude={form.longitude}
                  onLatitude={(value) => setForm((c) => ({ ...c, latitude: value }))}
                  onLongitude={(value) => setForm((c) => ({ ...c, longitude: value }))}
                  hint="Stand in your shop and tap the button — that is the most accurate this will ever be."
                />
              </div>
            </Card>

            <Card title="Money and registration">
              <div className="stack">
                <SelectField
                  label="You price and are paid in"
                  value={form.settlementCurrency}
                  onChange={set('settlementCurrency')}
                  hint="Fixed when the shop opens. Every listing is priced in it and every payout is made in it — a buyer paying in another currency is converted at the rate on the day, and that rate is recorded on the order."
                  error={fieldError(fields, 'settlementCurrency')}
                >
                  <option value="">Use the platform default</option>
                  {settlementCurrencies.map((currency) => (
                    <option key={currency.code} value={currency.code}>
                      {currency.code} — {currency.name}
                    </option>
                  ))}
                </SelectField>

                <div className="grid grid--2">
                  <TextField
                    label="Business registration number"
                    value={form.businessRegistrationNumber}
                    onChange={set('businessRegistrationNumber')}
                    error={fieldError(fields, 'businessRegistrationNumber')} maxLength={60}
                  />
                  <TextField
                    label="Tax number" value={form.taxNumber} onChange={set('taxNumber')}
                    error={fieldError(fields, 'taxNumber')} maxLength={60}
                  />
                </div>
              </div>
            </Card>

            <Button type="submit" variant="primary" block busy={busy}>
              Open the shop
            </Button>
            <p className="small muted center">
              Next you will send identity documents. Nothing goes on sale until they are accepted.
            </p>
          </form>
        </div>
      </main>
    </div>
  );
}

function AlreadyApplied({ storeName, status }: { storeName: string; status: string }) {
  const { reload } = useAuth();
  return (
    <div className="auth-screen">
      <div className="auth-card">
        <Card title="You already have a shop">
          <div className="stack">
            <p>
              <strong>{storeName}</strong> is registered and currently{' '}
              {status.replace(/_/g, ' ').toLowerCase()}.
            </p>
            <Button variant="primary" block onClick={() => void reload()}>
              Continue to your shop
            </Button>
          </div>
        </Card>
      </div>
    </div>
  );
}

function SignOutLink() {
  const { signOut } = useAuth();
  return (
    <Button variant="ghost" size="sm" onClick={() => void signOut()}>
      Sign out
    </Button>
  );
}
