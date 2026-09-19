import { useQuery } from '@tanstack/react-query';
import { reference } from '@/api/endpoints';
import { keys } from '@/hooks';
import { Select } from './forms';

/**
 * Countries the marketplace actually buys from and ships to.
 *
 * A free-text country field is fine for narrowing a search — a typo there
 * returns nothing and the person tries again. It is not fine where the value is
 * stored: a pickup point saved under `GN` when `GM` was meant is a counter that
 * no serviceability check will ever find, and nobody discovers it until a
 * parcel has nowhere to go.
 *
 * `ship` and `buy` are separate flags on the server for the same reason the
 * two locations are separate everywhere else, so callers say which they mean.
 */
export function CountrySelect({
  value,
  onChange,
  purpose,
  required,
  placeholder = 'Choose a country',
}: {
  value: string;
  onChange(value: string): void;
  /** `ship` for a delivery location, `buy` for where somebody pays from. */
  purpose: 'ship' | 'buy' | 'any';
  required?: boolean;
  placeholder?: string;
}) {
  const { data } = useQuery({
    queryKey: keys.countries,
    queryFn: reference.countries,
    staleTime: 60 * 60 * 1000,
  });

  const countries = (data?.countries ?? []).filter((country) =>
    purpose === 'any' ? true : purpose === 'ship' ? country.ship : country.buy,
  );

  // Until the list loads — or if this deployment serves none — fall back to
  // typing the code, rather than presenting an empty dropdown that looks like
  // the platform serves nowhere.
  if (countries.length === 0) {
    return (
      <input
        className="input"
        value={value}
        required={required}
        maxLength={2}
        placeholder="GM"
        onChange={(event) => onChange(event.target.value.toUpperCase())}
      />
    );
  }

  return (
    <Select
      value={value}
      required={required}
      placeholder={placeholder}
      options={countries.map((country) => ({
        value: country.code,
        label: `${country.name} (${country.code})`,
      }))}
      onChange={onChange}
    />
  );
}
