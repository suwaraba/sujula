import type { CurrencyInfo, Decimal } from '@/api/types';

/**
 * Formatting money, and nothing else.
 *
 * There is no arithmetic in this file on purpose. Every total this console
 * shows was computed by the server, in one currency, against a rate that was
 * snapshotted when the order was placed. A sum done here would be a second
 * opinion about somebody's money, and the two would disagree the first time a
 * rate moved.
 *
 * What the client does own is *scale*. `CurrencyCatalogue` is the server's
 * authority and it publishes `minorUnits` on `GET /currencies`; XOF has none,
 * so 1250.50 CFA is not an amount that exists and must never be rendered.
 * Formatting to two places everywhere is how a console invents money.
 */

let catalogue: Map<string, CurrencyInfo> = new Map();

export function loadCurrencyCatalogue(currencies: CurrencyInfo[]): void {
  catalogue = new Map(currencies.map((currency) => [currency.code.toUpperCase(), currency]));
}

export function currencyInfo(code: string | null | undefined): CurrencyInfo | undefined {
  if (!code) return undefined;
  return catalogue.get(code.toUpperCase());
}

export function knownCurrencies(): CurrencyInfo[] {
  return [...catalogue.values()].sort((a, b) => a.code.localeCompare(b.code));
}

/**
 * Minor units for a currency.
 *
 * Falls back to `Intl`'s own table when the catalogue has not loaded — which
 * is right for XOF and JPY as well — and only then to 2. A wrong scale is
 * visible in the number, so this never silently guesses without a source.
 */
function minorUnits(code: string): number {
  const known = catalogue.get(code.toUpperCase());
  if (known) return known.minorUnits;
  try {
    const parts = new Intl.NumberFormat('en', { style: 'currency', currency: code }).resolvedOptions();
    return parts.maximumFractionDigits ?? 2;
  } catch {
    return 2;
  }
}

function toNumber(amount: Decimal | null | undefined): number | null {
  if (amount === null || amount === undefined) return null;
  const value = typeof amount === 'string' ? Number(amount) : amount;
  return Number.isFinite(value) ? value : null;
}

/**
 * Renders an amount in its own currency, at that currency's own scale.
 *
 * The currency code is always shown. On a marketplace where a vendor is paid
 * in GMD and a buyer charged in EUR, a bare number on a screen is a number
 * somebody will read as the wrong one.
 */
export function formatMoney(
  amount: Decimal | null | undefined,
  code: string | null | undefined,
  options: { locale?: string; signed?: boolean } = {},
): string {
  const value = toNumber(amount);
  if (value === null) return '—';
  if (!code) return value.toString();

  const digits = minorUnits(code);
  const locale = options.locale ?? 'en-GB';

  let body: string;
  try {
    body = new Intl.NumberFormat(locale, {
      style: 'currency',
      currency: code,
      minimumFractionDigits: digits,
      maximumFractionDigits: digits,
    }).format(value);
  } catch {
    body = `${value.toFixed(digits)} ${code.toUpperCase()}`;
  }

  if (options.signed && value > 0) return `+${body}`;
  return body;
}

/** A rate, which is not money and has its own precision. */
export function formatRate(rate: Decimal | null | undefined): string {
  const value = toNumber(rate);
  if (value === null) return '—';
  return value.toLocaleString('en-GB', { minimumFractionDigits: 4, maximumFractionDigits: 6 });
}

/** A commission rate held as a fraction (0.12) or a percentage (12). */
export function formatCommission(rate: Decimal | null | undefined): string {
  const value = toNumber(rate);
  if (value === null) return '—';
  const percent = value <= 1 ? value * 100 : value;
  return `${percent.toLocaleString('en-GB', { maximumFractionDigits: 2 })}%`;
}

export function formatBasisPoints(basisPoints: number | null | undefined): string {
  if (basisPoints === null || basisPoints === undefined) return '—';
  return `${basisPoints} bp (${(basisPoints / 100).toFixed(2)}%)`;
}
