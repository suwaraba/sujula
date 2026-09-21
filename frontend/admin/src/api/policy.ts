import type { Decimal } from './types';

/**
 * Server rules the console has to know about before it sends a request.
 *
 * Everything here mirrors a constant or a check in the backend, and every one
 * of them is named with where it lives. A mirror drifts, so nothing in this
 * file is allowed to *block* a request: the server decides, and these only
 * decide what the form shows before it asks. When a mirror is out of date the
 * worst outcome is a box that appears a moment late, never a refusal the
 * console invented on its own.
 */

/**
 * `AdminMoneyServiceImpl.REFUND_STEP_UP_ABOVE`.
 *
 * A refund above this asks for the administrator's password again. Compared
 * against the amount in the buyer's display currency, and — as on the server —
 * as a bare number, without conversion: the threshold is the same figure
 * whatever currency it is read in.
 */
export const REFUND_STEP_UP_ABOVE = 5000;

/**
 * Whether a refund of this size will be asked to re-confirm.
 *
 * `null` means the console cannot tell — a partial refund entered in the
 * vendor's own currency, where knowing the display amount would mean
 * converting it here, which is exactly what C2 forbids. In that case the answer
 * is yes: offering the box and not needing it costs a moment, and needing it
 * and not offering it costs the whole form.
 */
export function refundNeedsStepUp(displayAmount: Decimal | null | undefined): boolean {
  if (displayAmount === null || displayAmount === undefined || displayAmount === '') return true;
  const value = typeof displayAmount === 'string' ? Number(displayAmount) : displayAmount;
  if (!Number.isFinite(value)) return true;
  return value > REFUND_STEP_UP_ABOVE;
}

/**
 * Reads the audit log — `AdminPlatformController.auditLog` calls
 * `staff.decider`, so it is the one read on this surface that support cannot
 * make. Everything else under `/admin` is readable by both roles.
 */
export const AUDIT_LOG_IS_ADMIN_ONLY = true;
