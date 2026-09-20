import { api } from '../client';
import type {
  ImeiGrade, ImeiStatus, ImeiUnit, ImeiUnitPage, InventoryPage,
  StockAdjusted, StockMovementReason, StockMovements,
} from '../types';

export const inventoryApi = {
  list: (params: {
    lowStock?: boolean;
    outOfStock?: boolean;
    search?: string;
    page?: number;
    size?: number;
  }) => api.get<InventoryPage>('/vendor/inventory', { query: params }),

  /**
   * `setTo` or `delta`, not both. `version` is the optimistic lock read off the
   * row: sending it back means a second till that moved the same stock while
   * this form was open is refused rather than silently overwritten.
   */
  adjust: (
    variantId: number,
    input: {
      setTo?: number;
      delta?: number;
      version?: number | null;
      reason: StockMovementReason;
      reference?: string;
      note?: string;
    },
  ) => api.patch<StockAdjusted>(`/vendor/inventory/${variantId}`, input),

  bulkAdjust: (
    input: {
      lines: { variantId: number; delta: number }[];
      reason: StockMovementReason;
      reference?: string;
      note?: string;
    },
    idempotencyKey?: string,
  ) =>
    api.post<{ requested: number; applied: number; results: StockAdjusted[]; errors: { variantId: number; message: string }[] }>(
      '/vendor/inventory/bulk',
      input,
      { idempotent: idempotencyKey ?? true },
    ),

  movements: (variantId: number, params: { page?: number; size?: number }) =>
    api.get<StockMovements>(`/vendor/inventory/${variantId}/movements`, { query: params }),

  // ── Handsets ──────────────────────────────────────────────────────────────

  imeiUnits: (params: {
    variantId?: number;
    status?: ImeiStatus;
    page?: number;
    size?: number;
  }) => api.get<ImeiUnitPage>('/vendor/imei-units', { query: params }),

  registerImeiUnits: (
    input: {
      productId: number;
      variantId?: number;
      units: {
        imei: string;
        imei2?: string;
        serialNumber?: string;
        grade?: ImeiGrade;
        gradeNote?: string;
        costPrice?: number;
        batteryHealth?: number;
        warrantyExpiresOn?: string;
        note?: string;
      }[];
    },
    idempotencyKey?: string,
  ) => api.post<{ requested: number; registered: number; units: ImeiUnit[] }>('/vendor/imei-units', input, {
    idempotent: idempotencyKey ?? true,
  }),

  updateImeiUnit: (
    unitId: number,
    input: {
      grade?: ImeiGrade;
      gradeNote?: string;
      status?: ImeiStatus;
      batteryHealth?: number;
      warrantyExpiresOn?: string;
      note?: string;
    },
  ) => api.patch<ImeiUnit>(`/vendor/imei-units/${unitId}`, input),
};
