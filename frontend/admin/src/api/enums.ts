/**
 * The server's enums, transcribed.
 *
 * Every one of these is a closed set the API will reject values outside of, so
 * they are written as const tuples: the tuple drives the `<select>` options and
 * the union type drives the compiler. Adding a case to the backend and not here
 * is then a type error at the first place the new value is switched on, rather
 * than a silently blank dropdown.
 */

export const USER_ROLES = [
  'CUSTOMER',
  'VENDOR',
  'DELIVERY',
  'PICKUP_OPERATOR',
  'SUPPORT',
  'ADMIN',
] as const;
export type UserRole = (typeof USER_ROLES)[number];

/** Whether an account may reach the administrative surface at all. */
export function isStaff(role: UserRole | null | undefined): boolean {
  return role === 'ADMIN' || role === 'SUPPORT';
}

/**
 * Whether an account may decide something rather than only read it.
 *
 * The single test behind every write on `/admin` — `StaffCaller.decider` on the
 * server. Mirrored here so the console can render the queues for support and
 * disable the buttons, rather than offering an action the API then refuses.
 * It is a rendering rule, never an authorisation one.
 */
export function canDecide(role: UserRole | null | undefined): boolean {
  return role === 'ADMIN';
}

export const PERMISSIONS = [
  'PROFILE_READ',
  'PROFILE_WRITE',
  'SESSION_MANAGE',
  'DATA_EXPORT',
  'DATA_ERASURE',
  'CART_MANAGE',
  'ORDER_PLACE',
  'ORDER_READ_OWN',
  'ORDER_CANCEL_OWN',
  'ADDRESS_MANAGE',
  'REVIEW_WRITE',
  'VENDOR_PROFILE_READ',
  'VENDOR_PROFILE_WRITE',
  'CATALOGUE_WRITE',
  'VENDOR_ORDER_READ',
  'VENDOR_ORDER_FULFIL',
  'VENDOR_PAYOUT_READ',
  'DELIVERY_READ',
  'DELIVERY_UPDATE',
  'PAYMENT_COLLECT',
  'USER_ADMIN',
  'VENDOR_ADMIN',
  'CATALOGUE_ADMIN',
  'ORDER_ADMIN',
  'PAYMENT_ADMIN',
  'EXCHANGE_RATE_ADMIN',
  'AUDIT_READ',
  'STAFF_READ',
] as const;
export type Permission = (typeof PERMISSIONS)[number];

export const ORDER_STATUSES = [
  'PENDING',
  'CONFIRMED',
  'PROCESSING',
  'SHIPPED',
  'DELIVERED',
  'CANCELLED',
  'REFUNDED',
] as const;
export type OrderStatus = (typeof ORDER_STATUSES)[number];

export const VENDOR_ORDER_STATUSES = [
  'PENDING',
  'PREPARING',
  'READY_FOR_PICKUP',
  'SHIPPED',
  'DELIVERED',
  'CANCELLED',
  'REFUNDED',
] as const;
export type VendorOrderStatus = (typeof VENDOR_ORDER_STATUSES)[number];

export const SHIPMENT_STATUSES = [
  'AWAITING_COLLECTION',
  'DRIVER_OFFERED',
  'DRIVER_ASSIGNED',
  'AT_ORIGIN',
  'IN_TRANSIT',
  'AT_PICKUP_POINT',
  'OUT_FOR_DELIVERY',
  'DELIVERED',
  'ATTEMPT_FAILED',
  'RETURNED',
  'CANCELLED',
] as const;
export type ShipmentStatus = (typeof SHIPMENT_STATUSES)[number];

export const CUSTODY_EVENT_TYPES = [
  'ARRIVED_AT_ORIGIN',
  'COLLECTED',
  'DEPOSITED',
  'REDISPATCHED',
  'RELEASED',
  'TRANSFERRED',
  'FAILED_ATTEMPT',
  'RETURNED',
] as const;
export type CustodyEventType = (typeof CUSTODY_EVENT_TYPES)[number];

export const LEG_ASSIGNMENT_STATUSES = [
  'UNASSIGNED',
  'OFFERED',
  'ACCEPTED',
  'DECLINED',
  'EXPIRED',
  'IN_PROGRESS',
  'COMPLETED',
  'CANCELLED',
] as const;
export type LegAssignmentStatus = (typeof LEG_ASSIGNMENT_STATUSES)[number];

export const PAYMENT_STATUSES = [
  'PENDING',
  'AUTHORIZED',
  'PAID',
  'FAILED',
  'CANCELLED',
  'PARTIALLY_REFUNDED',
  'REFUNDED',
] as const;
export type PaymentStatus = (typeof PAYMENT_STATUSES)[number];

export const PAYMENT_METHODS = [
  'CARD',
  'PAYPAL',
  'BANK_TRANSFER',
  'CASH_IN_STORE',
  'PAY_AT_PICKUP',
  'PAY_ON_DELIVERY',
] as const;
export type PaymentMethod = (typeof PAYMENT_METHODS)[number];

export const LEDGER_ENTRY_TYPES = [
  'SALE',
  'COMMISSION',
  'REFUND',
  'COMMISSION_REVERSAL',
  'PAYOUT',
  'PAYOUT_REVERSAL',
  'DISPUTE_HOLD',
  'DISPUTE_HOLD_RELEASE',
  'ADJUSTMENT',
] as const;
export type LedgerEntryType = (typeof LEDGER_ENTRY_TYPES)[number];

export const PAYOUT_STATUSES = [
  'REQUESTED',
  'ON_HOLD',
  'PENDING',
  'PROCESSING',
  'COMPLETED',
  'FAILED',
  'CANCELLED',
] as const;
export type PayoutStatus = (typeof PAYOUT_STATUSES)[number];

export const PAYOUT_BATCH_STATUSES = [
  'DRAFT',
  'AWAITING_APPROVAL',
  'APPROVED',
  'SETTLED',
  'CANCELLED',
] as const;
export type PayoutBatchStatus = (typeof PAYOUT_BATCH_STATUSES)[number];

export const REPORT_TYPES = [
  'LEDGER',
  'REVENUE',
  'PAYOUTS',
  'PAYMENTS',
  'RECONCILIATION',
] as const;
export type ReportType = (typeof REPORT_TYPES)[number];

export const REPORT_EXPORT_STATUSES = [
  'QUEUED',
  'BUILDING',
  'READY',
  'FAILED',
  'EXPIRED',
] as const;
export type ReportExportStatus = (typeof REPORT_EXPORT_STATUSES)[number];

export const PARTNER_STATUSES = [
  'PENDING_KYC',
  'PENDING',
  'APPROVED',
  'SUSPENDED',
  'REJECTED',
  'ACTIVE',
] as const;
export type PartnerStatus = (typeof PARTNER_STATUSES)[number];

export const KYC_DOCUMENT_STATUSES = ['SUBMITTED', 'ACCEPTED', 'REJECTED'] as const;
export type KycDocumentStatus = (typeof KYC_DOCUMENT_STATUSES)[number];

export const PRODUCT_STATUSES = [
  'DRAFT',
  'IN_REVIEW',
  'APPROVED',
  'PUBLISHED',
  'UNPUBLISHED',
  'REJECTED',
  'SUSPENDED',
  'ARCHIVED',
] as const;
export type ProductStatus = (typeof PRODUCT_STATUSES)[number];

export const MODERATION_CASE_STATUSES = [
  'OPEN',
  'IN_REVIEW',
  'RESOLVED',
  'DISMISSED',
] as const;
export type ModerationCaseStatus = (typeof MODERATION_CASE_STATUSES)[number];

export const MODERATION_REASONS = [
  'PROHIBITED_ITEM',
  'MISLEADING_LISTING',
  'INTELLECTUAL_PROPERTY',
  'RATING_MANIPULATION',
  'OFF_PLATFORM_PAYMENT',
  'ABUSIVE_CONDUCT',
  'NON_DELIVERY',
  'IDENTITY_FRAUD',
  'OTHER',
] as const;
export type ModerationReason = (typeof MODERATION_REASONS)[number];

export const SANCTION_TYPES = [
  'WARNING',
  'FEATURE_RESTRICTION',
  'SUSPENSION',
  'BAN',
] as const;
export type SanctionType = (typeof SANCTION_TYPES)[number];

export const DISPUTE_STATUSES = ['OPEN', 'UNDER_REVIEW', 'RESOLVED', 'WITHDRAWN'] as const;
export type DisputeStatus = (typeof DISPUTE_STATUSES)[number];

export const DISPUTE_REASONS = [
  'NOT_RECEIVED',
  'NOT_AS_DESCRIBED',
  'DAMAGED',
  'RETURN_REFUSED',
  'REFUND_NOT_RECEIVED',
  'UNAUTHORISED_CHARGE',
] as const;
export type DisputeReason = (typeof DISPUTE_REASONS)[number];

export const DISPUTE_OUTCOMES = ['FOR_BUYER', 'FOR_VENDOR', 'SPLIT', 'NO_DECISION'] as const;
export type DisputeOutcome = (typeof DISPUTE_OUTCOMES)[number];

export const CALLBACK_OUTCOMES = [
  'SPOKE',
  'NO_ANSWER',
  'UNREACHABLE',
  'RESCHEDULED',
  'DECLINED',
] as const;
export type CallbackOutcome = (typeof CALLBACK_OUTCOMES)[number];

export const NOTIFICATION_EVENTS = [
  'ORDER_PLACED',
  'ORDER_UPDATE',
  'ORDER_CANCELLED',
  'REFUND_ISSUED',
  'PARCEL_COLLECTED',
  'PARCEL_OUT_FOR_DELIVERY',
  'PARCEL_ATTEMPT_FAILED',
  'PARCEL_AT_PICKUP_POINT',
  'PARCEL_CODE',
  'PARCEL_DELIVERED',
  'RETURN_UPDATE',
  'DISPUTE_UPDATE',
  'MESSAGE_RECEIVED',
  'REVIEW_REPLY',
  'SALE_MADE',
  'ORDER_TO_FULFIL',
  'PAYOUT_SENT',
  'PAYOUT_FAILED',
  'LOW_STOCK',
  'DELIVERY_OFFERED',
  'PICKUP_PARCEL_ARRIVED',
  'PICKUP_PARCEL_OVERDUE',
  'SECURITY_ALERT',
  'ACCOUNT_UPDATE',
  'PROMOTION',
  'PLATFORM_NOTICE',
  'GENERAL',
] as const;
export type NotificationEvent = (typeof NOTIFICATION_EVENTS)[number];

export const DRIVER_STATUSES = ['PENDING', 'APPROVED', 'SUSPENDED', 'REJECTED', 'ACTIVE'] as const;
export type DriverStatus = (typeof DRIVER_STATUSES)[number];

export const DELIVERY_MODES = ['HOME_DELIVERY', 'PICKUP_POINT', 'VENDOR_PICKUP'] as const;
export type DeliveryMode = (typeof DELIVERY_MODES)[number];

export const JOB_RUN_STATUSES = ['RUNNING', 'SUCCEEDED', 'FAILED', 'ABANDONED'] as const;
export type JobRunStatus = (typeof JOB_RUN_STATUSES)[number];

/**
 * Audit actions, for the audit-log filter. Long, and deliberately complete:
 * a filter that offers a subset teaches the person reading it that the subset
 * is all there is.
 */
export const AUDIT_ACTIONS = [
  'USER_BLOCKED',
  'USER_UNBLOCKED',
  'USER_FRAUD_FLAGGED',
  'USER_FRAUD_CLEARED',
  'USER_ENABLED',
  'USER_DISABLED',
  'USER_UNLOCKED',
  'USER_DELETED',
  'USER_PURGED',
  'VENDOR_STATUS_CHANGED',
  'VENDOR_STORE_CREATED',
  'VENDOR_KYC_SUBMITTED',
  'VENDOR_PAYOUT_DESTINATION_CHANGED',
  'VENDOR_STAFF_CHANGED',
  'PAYMENT_TRANSFER_CONFIRMED',
  'PAYMENT_COLLECTED_IN_PERSON',
  'PAYMENT_REFUNDED',
  'PAYMENT_CANCELLED',
  'PAYMENT_MARKED_FAILED',
  'ADMIN_BOOTSTRAPPED',
  'USER_CREATED_BY_ADMIN',
  'USER_PROFILE_EDITED_BY_ADMIN',
  'USER_ROLE_GRANTED',
  'USER_ROLE_REVOKED',
  'USER_SESSIONS_ENDED_BY_ADMIN',
  'USER_MFA_RESET',
  'USER_IMPERSONATED',
  'USER_IMPERSONATION_ENDED',
  'SANCTION_ISSUED',
  'SANCTION_LIFTED',
  'MODERATION_CASE_RAISED',
  'MODERATION_CASE_ASSIGNED',
  'MODERATION_CASE_RESOLVED',
  'STORE_APPROVED',
  'STORE_REJECTED',
  'STORE_SUSPENDED',
  'STORE_COMMISSION_CHANGED',
  'KYC_APPROVED',
  'KYC_REJECTED',
  'PRODUCT_APPROVED',
  'PRODUCT_REJECTED',
  'PRODUCT_SUSPENDED',
  'PRODUCT_CREATED_BY_ADMIN',
  'PRODUCT_EDITED_BY_ADMIN',
  'REVIEW_PUBLISHED',
  'REVIEW_REJECTED',
  'ORDER_CANCELLED_BY_ADMIN',
  'ORDER_PLACED_BY_ADMIN',
  'VENDOR_ORDER_STATUS_FORCED',
  'SHIPMENT_ASSIGNED',
  'SHIPMENT_UNASSIGNED',
  'SHIPMENT_REASSIGNED',
  'SHIPMENT_CANCELLED_BY_ADMIN',
  'SHIPMENT_HANDOFF_OVERRIDDEN',
  'DRIVER_APPROVED',
  'DRIVER_SUSPENDED',
  'DRIVER_ZONES_CHANGED',
  'PICKUP_POINT_CREATED_BY_ADMIN',
  'PICKUP_POINT_EDITED',
  'PICKUP_POINT_SUSPENDED',
  'ZONE_CREATED',
  'ZONE_EDITED',
  'RATE_CARD_CHANGED',
  'PAYOUT_BATCH_CREATED',
  'PAYOUT_BATCH_APPROVED',
  'PAYOUT_BATCH_CANCELLED',
  'PAYOUT_ITEM_RETRIED',
  'FX_REFRESHED',
  'FX_SPREAD_CHANGED',
  'REPORT_EXPORTED',
  'DISPUTE_ASSIGNED',
  'DISPUTE_RESOLVED',
  'DISPUTE_CALLBACK_REQUESTED',
  'DISPUTE_CALLBACK_RECORDED',
  'DISPUTE_NOTE_ADDED',
  'ANNOUNCEMENT_SENT',
  'NOTIFICATION_SENT_BY_ADMIN',
  'FEATURE_FLAG_CHANGED',
  'JOB_TRIGGERED',
  'ADMIN_PROMOTED',
] as const;
export type AuditAction = (typeof AUDIT_ACTIONS)[number];
