import { api } from '../client';
import type {
  KycDocumentType, KycState, PresignedUpload, StaffList, StaffMember,
  Store, StorePermission, VendorProfile,
} from '../types';

export type CreateStoreInput = {
  storeName: string;
  description?: string;
  storeEmail?: string;
  storePhone?: string;
  website?: string;
  addressStreet: string;
  addressCity: string;
  addressState?: string;
  addressPostalCode?: string;
  addressCountryCode: string;
  /**
   * Sent when the seller dropped the pin themselves. Left off, the server
   * geocodes the address — and marks the result as needing confirmation,
   * because a guessed pin is where a driver gets sent.
   */
  latitude?: number;
  longitude?: number;
  settlementCurrency?: string;
  businessRegistrationNumber?: string;
  taxNumber?: string;
};

export type UpdateStoreInput = Partial<{
  storeName: string;
  description: string;
  storeEmail: string;
  storePhone: string;
  website: string;
  returnPolicy: string;
  shippingPolicy: string;
  storePolicy: string;
  handlingDays: number;
  vacationMode: boolean;
  vacationMessage: string;
  pickupAddress: {
    street?: string;
    city?: string;
    state?: string;
    postalCode?: string;
    countryCode?: string;
    latitude?: number;
    longitude?: number;
    instructions?: string;
    /** True to go back to collecting from the store address. */
    clear: boolean;
  };
  operatingHours: { day: string; closed: boolean; opensAt?: string; closesAt?: string }[];
}>;

export type BankAccountInput = {
  accountType: string;
  accountHolderName: string;
  bankName?: string;
  accountNumber?: string;
  routingNumber?: string;
  iban?: string;
  swiftCode?: string;
  mobileMoneyPhone?: string;
  mobileMoneyProvider?: string;
  /** Re-entered, every time. A bearer token only proves somebody held a credential an hour ago. */
  password: string;
  totpCode?: string;
};

export const storeApi = {
  create: (input: CreateStoreInput, idempotencyKey?: string) =>
    api.post<Store>('/vendor/stores', input, { idempotent: idempotencyKey ?? true }),

  get: (storeId: number) => api.get<Store>(`/vendor/stores/${storeId}`),

  update: (storeId: number, input: UpdateStoreInput) =>
    api.patch<Store>(`/vendor/stores/${storeId}`, input),

  kyc: (storeId: number) => api.get<KycState>(`/vendor/stores/${storeId}/kyc`),

  submitKyc: (
    storeId: number,
    documents: {
      type: KycDocumentType;
      fileUrl: string;
      originalFilename?: string;
      contentType?: string;
      sizeBytes?: number;
      expiresOn?: string;
    }[],
    idempotencyKey?: string,
  ) =>
    api.post<KycState>(
      `/vendor/stores/${storeId}/kyc`,
      { documents },
      { idempotent: idempotencyKey ?? true },
    ),

  putBankAccount: (storeId: number, input: BankAccountInput, idempotencyKey?: string) =>
    api.put<Store>(`/vendor/stores/${storeId}/bank-account`, input, {
      idempotent: idempotencyKey ?? true,
    }),

  staff: (storeId: number) => api.get<StaffList>(`/vendor/stores/${storeId}/staff`),

  inviteStaff: (
    storeId: number,
    input: { email: string; displayName?: string; permissions?: StorePermission[] },
    idempotencyKey?: string,
  ) =>
    api.post<StaffMember>(`/vendor/stores/${storeId}/staff`, input, {
      idempotent: idempotencyKey ?? true,
    }),

  updateStaff: (
    storeId: number,
    staffUserId: number,
    input: { permissions: StorePermission[]; displayName?: string },
  ) => api.patch<StaffMember>(`/vendor/stores/${storeId}/staff/${staffUserId}`, input),

  removeStaff: (storeId: number, staffUserId: number) =>
    api.delete<void>(`/vendor/stores/${storeId}/staff/${staffUserId}`),

  // ── The legacy `/api/vendors` surface ─────────────────────────────────────
  // `GET /vendor/stores/{id}` needs an id, and an account that has just signed
  // in does not have one until `/me` answers. `/api/vendors/me` resolves the
  // seller from the session, which is what the gate needs before it can route.

  myVendorProfile: () => api.get<VendorProfile>('/api/vendors/me'),

  presignLogo: (userId: number, contentType: string) =>
    api.post<PresignedUpload>(`/api/vendors/user/${userId}/logo/presign`, undefined, {
      query: { contentType },
    }),

  presignBanner: (userId: number, contentType: string) =>
    api.post<PresignedUpload>(`/api/vendors/user/${userId}/banner/presign`, undefined, {
      query: { contentType },
    }),
};
