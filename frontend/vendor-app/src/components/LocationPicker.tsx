import { useState } from 'react';
import { Button, Notice } from './ui';
import { TextField } from './form';

/**
 * Where the shop actually is.
 *
 * This matters more than it looks. The coordinate set on a store is copied onto
 * every listing written from it — `VendorCatalogueServiceImpl` stamps each new
 * product with the store's pickup pin, falling back to the store address — and
 * it is the origin every delivery leg is priced and routed from. A seller who
 * leaves it blank gets a pin a geocoder guessed from a street name, and a
 * driver sent to that guess.
 *
 * The two numbers are held by the parent form as strings, because that is what
 * an input holds and because "empty" has to stay distinguishable from zero.
 */
export function CoordinateFields({
  latitude, longitude, onLatitude, onLongitude, label = 'Where drivers collect from', hint,
}: {
  latitude: string;
  longitude: string;
  onLatitude: (value: string) => void;
  onLongitude: (value: string) => void;
  label?: string;
  hint?: string;
}) {
  const [locating, setLocating] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const hasPin = latitude.trim() !== '' && longitude.trim() !== '';

  function useMyLocation() {
    if (!navigator.geolocation) {
      setError('This device cannot report its location. Type the coordinates instead.');
      return;
    }
    setLocating(true);
    setError(null);
    navigator.geolocation.getCurrentPosition(
      (position) => {
        onLatitude(position.coords.latitude.toFixed(6));
        onLongitude(position.coords.longitude.toFixed(6));
        setLocating(false);
      },
      (cause) => {
        setLocating(false);
        setError(
          cause.code === cause.PERMISSION_DENIED
            ? 'Location permission was refused. Allow location and try again, or type the coordinates.'
            : 'Could not read your location. Type the coordinates instead.',
        );
      },
      { enableHighAccuracy: true, timeout: 15_000, maximumAge: 0 },
    );
  }

  return (
    <fieldset
      className="stack stack--tight"
      style={{ border: 'none', margin: 0, padding: 0, minInlineSize: 0 }}
    >
      <legend className="field__label" style={{ padding: 0 }}>{label}</legend>
      {hint && <div className="field__hint">{hint}</div>}

      <div className="row">
        <Button type="button" variant="secondary" size="sm" onClick={useMyLocation} busy={locating}>
          ◉ Use my current location
        </Button>
        <span className="small muted">
          Or long-press your shop in a map app and copy the two numbers.
        </span>
      </div>

      <div className="grid grid--2">
        <TextField
          label="Latitude" inputMode="decimal" placeholder="13.452500"
          value={latitude} onChange={(event) => onLatitude(event.target.value)}
        />
        <TextField
          label="Longitude" inputMode="decimal" placeholder="-16.578900"
          value={longitude} onChange={(event) => onLongitude(event.target.value)}
        />
      </div>

      {error && <div className="field__error">{error}</div>}

      {!hasPin && !error && (
        <Notice tone="warn">
          Without a pin we place your shop by guessing from the street address. Drivers are sent to
          that guess, and every listing you write inherits it.
        </Notice>
      )}
    </fieldset>
  );
}

/** Parses a coordinate field, rejecting anything outside its real range. */
export function parseCoordinate(value: string, max: number): number | undefined {
  const trimmed = value.trim();
  if (trimmed === '') return undefined;
  const parsed = Number(trimmed);
  if (!Number.isFinite(parsed) || Math.abs(parsed) > max) return undefined;
  return parsed;
}
