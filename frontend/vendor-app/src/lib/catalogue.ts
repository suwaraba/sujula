import type { ProductCondition } from '@/api/types';

export const CONDITIONS: { value: ProductCondition; label: string }[] = [
  { value: 'NEW', label: 'New' },
  { value: 'OPEN_BOX', label: 'Open box' },
  { value: 'REFURBISHED', label: 'Refurbished' },
  { value: 'USED', label: 'Used' },
  { value: 'FOR_PARTS', label: 'For parts' },
];

export const DELIVERY_SCOPES: { value: string; label: string }[] = [
  { value: 'DOMESTIC', label: 'Inside my country only' },
  { value: 'REGIONAL', label: 'My country and neighbours' },
  { value: 'INTERNATIONAL', label: 'Anywhere we ship' },
];

export const STOCK_REASONS: { value: string; label: string }[] = [
  { value: 'RESTOCK', label: 'New stock arrived' },
  { value: 'CORRECTION', label: 'Correcting a miscount' },
  { value: 'DAMAGE', label: 'Damaged' },
  { value: 'LOSS', label: 'Lost or stolen' },
  { value: 'RETURN', label: 'Returned by a buyer' },
  { value: 'MANUAL', label: 'Something else' },
];

/** Flattens the category tree into indented options — a `<select>` has no nesting. */
export type CategoryLike = { id: number; name: string; children?: CategoryLike[] | null };

export function flattenCategories(
  nodes: CategoryLike[] | null | undefined,
  depth = 0,
): { id: number; label: string }[] {
  const out: { id: number; label: string }[] = [];
  for (const node of nodes ?? []) {
    const indent = '\u00a0\u00a0'.repeat(depth);
    out.push({ id: node.id, label: `${indent}${depth > 0 ? '\u2514 ' : ''}${node.name}` });
    if (node.children?.length) out.push(...flattenCategories(node.children, depth + 1));
  }
  return out;
}

/**
 * Whether an IMEI's own check digit agrees with the rest of it.
 *
 * The server checks this and refuses the handset if it fails, with a message
 * saying one of the numbers is wrong. Checking it here too is not duplication
 * for its own sake: a seller pasting forty IMEIs off a spreadsheet should find
 * out which one has a typo before sending the batch, not after.
 *
 * It is the Luhn algorithm, the same one a card number uses: double every
 * second digit from the right, subtract nine from anything over nine, and the
 * total must divide by ten.
 */
export function imeiCheckDigitValid(imei: string): boolean {
  if (!/^\d{15}$/.test(imei)) return false;

  let total = 0;
  for (let index = 0; index < 15; index += 1) {
    let digit = Number(imei[14 - index]);
    if (index % 2 === 1) {
      digit *= 2;
      if (digit > 9) digit -= 9;
    }
    total += digit;
  }
  return total % 10 === 0;
}
