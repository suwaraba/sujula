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
