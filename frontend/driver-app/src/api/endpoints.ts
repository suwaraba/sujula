/**
 * Every endpoint the driver app is allowed to call, and no others.
 *
 * The whole `/driver` surface is here, plus the parts of `/auth`, `/me` and
 * `/notifications` a courier needs. Nothing else: this app has no business
 * reaching the catalogue, another driver's round, or the admin surface, and a
 * module that simply cannot express those calls is a stronger guarantee than a
 * rule about not making them.
 *
 * Note what does **not** appear anywhere below: a driver id. Every one of these
 * paths takes its subject from the session, so there is no parameter a tampered
 * client could change to open somebody else's parcel.
 */

import { api, request } from './http';
import type {
  ApplyRequest,
  ArrivedRequest,
  AssignmentAnswered,
  Assignments,
  AttemptFailed,
  AvailabilitySet,
  CustodyRecorded,
  DeliveryFailedRequest,
  DriverProfile,
  Earnings,
  HandoverRequest,
  History,
  LocationAccepted,
  LoginResult,
  Me,
  PingRequest,
  PresignedUpload,
  PublicConfig,
  RecipientCodeRequested,
  ShipmentDetail,
  SyncBatch,
  SyncResult,
  Tokens,
  TransferRequest,
  UpdateProfileRequest,
} from './types';

// ── Identity ─────────────────────────────────────────────────────────────────

export const auth = {
  login: (body: {
    email: string;
    password: string;
    totpCode?: string;
    recoveryCode?: string;
    deviceLabel?: string;
  }) => api.post<LoginResult>('/auth/login', body, { anonymous: true }),

  refresh: (refreshToken: string) =>
    api.post<Tokens>('/auth/refresh', { refreshToken }, { anonymous: true }),

  /** Ends this device's session only. The refresh token names which one. */
  logout: (refreshToken: string) => api.post<unknown>('/auth/logout', { refreshToken }),

  forgotPassword: (email: string) =>
    api.post<unknown>('/auth/password/forgot', { email }, { anonymous: true }),

  me: () => api.get<Me>('/me'),
};

// ── Reference ────────────────────────────────────────────────────────────────

export const reference = {
  publicConfig: () => api.get<PublicConfig>('/config/public', { anonymous: true }),
};

// ── The driver ───────────────────────────────────────────────────────────────

export const driver = {
  /** Applying to carry parcels. Creates a profile awaiting review. */
  apply: (body: ApplyRequest, idempotencyKey: string) =>
    api.post<DriverProfile>('/driver/profile', body, { idempotencyKey }),

  profile: () => api.get<DriverProfile>('/driver/profile'),

  updateProfile: (body: UpdateProfileRequest) =>
    api.patch<DriverProfile>('/driver/profile', body),

  /**
   * Going online or off.
   *
   * The driver's own decision, separate from whether the platform has approved
   * them — and anything already accepted stays theirs to finish.
   */
  setAvailability: (online: boolean) =>
    api.put<AvailabilitySet>('/driver/availability', {
      availability: online ? 'ONLINE' : 'OFFLINE',
    }),

  /** Accepted only while online, and throttled to one every twenty seconds. */
  ping: (body: PingRequest) => api.post<LocationAccepted>('/driver/location', body),

  assignments: () => api.get<Assignments>('/driver/assignments'),

  accept: (legId: number, idempotencyKey: string) =>
    api.post<AssignmentAnswered>(`/driver/assignments/${legId}/accept`, undefined, {
      idempotencyKey,
    }),

  decline: (legId: number, reason: string, idempotencyKey: string) =>
    api.post<AssignmentAnswered>(
      `/driver/assignments/${legId}/decline`,
      { reason },
      { idempotencyKey },
    ),

  shipment: (id: number) => api.get<ShipmentDetail>(`/driver/shipments/${id}`),

  arrived: (id: number, body: ArrivedRequest, idempotencyKey: string) =>
    api.post<CustodyRecorded>(`/driver/shipments/${id}/arrived-at-origin`, body, {
      idempotencyKey,
    }),

  collect: (id: number, body: HandoverRequest, idempotencyKey: string) =>
    api.post<CustodyRecorded>(`/driver/shipments/${id}/collect`, body, { idempotencyKey }),

  depositAtPickup: (id: number, body: HandoverRequest, idempotencyKey: string) =>
    api.post<CustodyRecorded>(`/driver/shipments/${id}/deposit-at-pickup`, body, {
      idempotencyKey,
    }),

  deliver: (id: number, body: HandoverRequest, idempotencyKey: string) =>
    api.post<CustodyRecorded>(`/driver/shipments/${id}/deliver`, body, { idempotencyKey }),

  deliveryFailed: (id: number, body: DeliveryFailedRequest, idempotencyKey: string) =>
    api.post<AttemptFailed>(`/driver/shipments/${id}/delivery-failed`, body, { idempotencyKey }),

  /**
   * Has the recipient's code sent to the buyer, who passes it on.
   *
   * The driver never sees it, and this response does not contain it — a driver
   * who could read the code could mark a parcel delivered without meeting
   * anybody, which is the one thing the code exists to prevent.
   */
  requestRecipientCode: (id: number, idempotencyKey: string) =>
    api.post<RecipientCodeRequested>(
      `/driver/shipments/${id}/request-recipient-code`,
      undefined,
      { idempotencyKey },
    ),

  transfer: (id: number, body: TransferRequest, idempotencyKey: string) =>
    api.post<CustodyRecorded>(`/driver/shipments/${id}/transfer`, body, { idempotencyKey }),

  /** A day's worth of events recorded with no signal, applied oldest first. */
  sync: (body: SyncBatch, idempotencyKey: string) =>
    api.post<SyncResult>('/driver/custody-events/sync', body, { idempotencyKey }),

  earnings: (from?: string, to?: string) =>
    api.get<Earnings>('/driver/earnings', { query: { from, to } }),

  history: (page = 0, size = 20) =>
    api.get<History>('/driver/history', { query: { page, size } }),

  /**
   * A short-lived URL to put a photograph at, plus the public URL it will have.
   *
   * The bytes go straight from the phone to storage and never pass through the
   * application server, which is how the rest of this platform uploads too.
   */
  presignEvidence: (contentType: string) =>
    api.post<PresignedUpload>(
      `${import.meta.env.VITE_MEDIA_PRESIGN_PATH || '/driver/evidence/presign'}`,
      undefined,
      { query: { contentType } },
    ),
};

// ── Push ─────────────────────────────────────────────────────────────────────

export const notifications = {
  registerDevice: (token: string, label?: string) =>
    api.post<unknown>('/notifications/devices', { token, platform: 'WEB', label }),

  unregisterDevice: (deviceId: number) => api.delete<unknown>(`/notifications/devices/${deviceId}`),
};

/** For the rare call this module has no wrapper for. Typed, but unconstrained. */
export { request as rawRequest };
