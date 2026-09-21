import { api } from './client';
import { clearTokens, getRefreshToken, storeTokens } from './tokens';
import type {
  ApplicationSubmitted, CodeResent, Countries, Currencies, Earnings, LoginResult, Me,
  OperatorPoint, OperatorPoints, ParcelAccepted, ParcelRejected, ParcelReleased,
  ParcelReturning, Parcels, PublicPoints, RejectReason, Tokens,
} from './types';

export const authApi = {
  /** `mfaRequired` is an answer, not an error: the password was right. */
  async login(email: string, password: string, totpCode?: string): Promise<LoginResult> {
    const result = await api.post<LoginResult>(
      '/auth/login',
      { email, password, ...(totpCode ? { totpCode } : {}) },
      { anonymous: true },
    );
    if (result.tokens) storeTokens(result.tokens);
    return result;
  },

  async register(input: {
    email: string; password: string; firstName: string; lastName: string; phone?: string;
  }): Promise<Tokens> {
    const tokens = await api.post<Tokens>('/auth/register', input, { anonymous: true });
    storeTokens(tokens);
    return tokens;
  },

  async logout(): Promise<void> {
    const refreshToken = getRefreshToken();
    try {
      if (refreshToken) await api.post<void>('/auth/logout', { refreshToken });
    } finally {
      // A failed call must not strand an operator in a half-signed-in tablet.
      clearTokens();
    }
  },

  me: () => api.get<Me>('/me'),

  forgotPassword: (email: string) =>
    api.post<void>('/auth/password/forgot', { email }, { anonymous: true }),
};

export const referenceApi = {
  /** Carries `minorUnits`, which is what correct rounding of commission depends on. */
  currencies: () => api.get<Currencies>('/currencies'),
  countries: () => api.get<Countries>('/countries'),
};

export type ApplyInput = {
  name: string;
  addressStreet: string;
  addressApartment?: string;
  city: string;
  state?: string;
  postalCode?: string;
  countryCode?: string;
  /**
   * Required, and not derivable from the address. Most addresses in this market
   * do not resolve to a point, and this is what a driver navigates to and what
   * the collection geofence is measured against.
   */
  lat: number;
  lng: number;
  contactPhone: string;
  contactEmail?: string;
  openingHours?: string;
  capacity?: number;
  profileImageUrl?: string;
};

export type UpdatePointInput = {
  name?: string;
  openingHours?: string;
  capacity?: number;
  storageDays?: number;
  contactPhone?: string;
  contactEmail?: string;
  profileImageUrl?: string;
  /** Null reopens. A closed counter keeps what it already holds. */
  closedUntil?: string | null;
  closureReason?: string;
};

/**
 * The counter.
 *
 * No operator id appears in any path — the point id does, and the operator is
 * resolved from the session beside it, so another operator's counter is not
 * found rather than refused. These rows lead to recipients' names.
 */
export const pickupApi = {
  myPoints: () => api.get<OperatorPoints>('/pickup/points'),

  apply: (input: ApplyInput, idempotencyKey?: string) =>
    api.post<ApplicationSubmitted>('/pickup/applications', input, {
      idempotent: idempotencyKey ?? true,
    }),

  updatePoint: (pointId: number, input: UpdatePointInput) =>
    api.patch<OperatorPoint>(`/pickup/points/${pointId}`, input),

  parcels: (pointId: number) => api.get<Parcels>(`/pickup/points/${pointId}/parcels`),

  /**
   * Taking a parcel in. The operator verifies the *driver's* code — the
   * receiving party checking the giving party, which is the only thing a code
   * can prove. Refused when the counter is closed, suspended or full, and the
   * refusal says which.
   */
  accept: (
    pointId: number,
    shipmentId: number,
    input: { code: string; shelfCode?: string; note?: string; qrToken?: string; clientEventId: string },
    idempotencyKey?: string,
  ) =>
    api.post<ParcelAccepted>(`/pickup/points/${pointId}/parcels/${shipmentId}/accept`, input, {
      idempotent: idempotencyKey ?? true,
    }),

  /**
   * Turning one away. Custody does not move: the driver still has it in their
   * hands, which is why the server records a failed attempt rather than the end
   * of the chain. Somebody stays accountable for the parcel.
   */
  reject: (
    pointId: number,
    shipmentId: number,
    input: { reason: RejectReason; note?: string; photoUrl?: string; clientEventId: string },
    idempotencyKey?: string,
  ) =>
    api.post<ParcelRejected>(`/pickup/points/${pointId}/parcels/${shipmentId}/reject`, input, {
      idempotent: idempotencyKey ?? true,
    }),

  /**
   * The end of the chain, and what releases the seller's money.
   *
   * Both checks are required. The code proves they were told it by whoever sent
   * the parcel; the name is what the operator reads off the shelf and compares
   * to the person in front of them. A code alone would let anybody who overheard
   * it collect; a name alone, anybody who read the label.
   */
  release: (
    pointId: number,
    shipmentId: number,
    input: {
      code: string;
      collectedByName: string;
      identityShown?: string;
      note?: string;
      signatureUrl?: string;
      photoUrl?: string;
      clientEventId: string;
    },
    idempotencyKey?: string,
  ) =>
    api.post<ParcelReleased>(`/pickup/points/${pointId}/parcels/${shipmentId}/release`, input, {
      idempotent: idempotencyKey ?? true,
    }),

  /** Only once the storage deadline has passed; refused before then. */
  returnParcel: (
    pointId: number,
    shipmentId: number,
    input: { note?: string; clientEventId: string },
    idempotencyKey?: string,
  ) =>
    api.post<ParcelReturning>(`/pickup/points/${pointId}/parcels/${shipmentId}/return`, input, {
      idempotent: idempotencyKey ?? true,
    }),

  /**
   * Sends the code again — to the buyer, who passes it on. The recipient may
   * have no account, no app and no email of her own. This screen never sees it.
   */
  resendCode: (pointId: number, shipmentId: number, idempotencyKey?: string) =>
    api.post<CodeResent>(`/pickup/points/${pointId}/parcels/${shipmentId}/resend-code`, undefined, {
      idempotent: idempotencyKey ?? true,
    }),

  earnings: (pointId: number, params: { from?: string; to?: string } = {}) =>
    api.get<Earnings>(`/pickup/points/${pointId}/earnings`, { query: params }),

  /** The open lookup, used by the application form to show what is already nearby. */
  search: (params: { lat?: number; lng?: number; radius?: number; city?: string }) =>
    api.get<PublicPoints>('/pickup-points', { query: params }),
};
