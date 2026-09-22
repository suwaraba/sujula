import { api } from '../client';
import type {
  CatalogueJob, ImportTemplate, PresignedUpload, ProductCondition, ProductDetail,
  ProductPage, ProductRemoved, ProductSaved, ProductStatus,
} from '../types';

export type ProductInput = {
  name: string;
  shortDescription?: string;
  description?: string;
  price: number;
  compareAtPrice?: number;
  sku?: string;
  stock?: number;
  lowStockThreshold?: number;
  allowBackorder?: boolean;
  categoryId?: number;
  brandId?: number;
  condition?: ProductCondition;
  deliveryScope?: string;
  weightKg?: number;
  dimensions?: string;
  /**
   * Country of origin. Left blank, the server fills it from the store's
   * registered country — and fills the listing's pin from the store's pickup
   * coordinates either way. A seller does not retype their own address per
   * product, and a listing whose pin disagrees with its shop is a collection
   * sent to the wrong place.
   */
  country?: string;
};

export const catalogueApi = {
  list: (params: {
    status?: ProductStatus;
    search?: string;
    includeArchived?: boolean;
    page?: number;
    size?: number;
  }) => api.get<ProductPage>('/vendor/products', { query: params }),

  get: (productId: number) => api.get<ProductDetail>(`/vendor/products/${productId}`),

  /** Always creates a DRAFT. There is no status field on the way in, by design. */
  create: (input: ProductInput, idempotencyKey?: string) =>
    api.post<ProductDetail>('/vendor/products', input, { idempotent: idempotencyKey ?? true }),

  update: (productId: number, input: Partial<ProductInput>) =>
    api.patch<ProductSaved>(`/vendor/products/${productId}`, input),

  archive: (productId: number) => api.delete<ProductRemoved>(`/vendor/products/${productId}`),

  submitForReview: (productId: number, note?: string, idempotencyKey?: string) =>
    api.post<ProductSaved>(
      `/vendor/products/${productId}/submit-for-review`,
      { note: note ?? null },
      { idempotent: idempotencyKey ?? true },
    ),

  publish: (productId: number, idempotencyKey?: string) =>
    api.post<ProductSaved>(`/vendor/products/${productId}/publish`, undefined, {
      idempotent: idempotencyKey ?? true,
    }),

  unpublish: (productId: number, idempotencyKey?: string) =>
    api.post<ProductSaved>(`/vendor/products/${productId}/unpublish`, undefined, {
      idempotent: idempotencyKey ?? true,
    }),

  // ── Variants ──────────────────────────────────────────────────────────────

  createVariant: (
    productId: number,
    input: { sku?: string; stock?: number; priceOverride?: number; optionValueIds: number[] },
    idempotencyKey?: string,
  ) =>
    api.post<ProductDetail>(`/vendor/products/${productId}/variants`, input, {
      idempotent: idempotencyKey ?? true,
    }),

  updateVariant: (
    productId: number,
    variantId: number,
    input: {
      sku?: string;
      stock?: number;
      priceOverride?: number;
      active?: boolean;
      optionValueIds?: number[];
    },
  ) => api.patch<ProductDetail>(`/vendor/products/${productId}/variants/${variantId}`, input),

  deleteVariant: (productId: number, variantId: number) =>
    api.delete<ProductDetail>(`/vendor/products/${productId}/variants/${variantId}`),

  // ── Media ─────────────────────────────────────────────────────────────────
  //
  // The bytes never pass through the API. A client presigns an upload, PUTs the
  // file straight into object storage, and tells the application the key.

  /**
   * Asks for somewhere to put an image, and gets back a URL to PUT the bytes to
   * plus the public URL to hand back to `confirmMedia`. The bytes never pass
   * through the API: an image travelling through an application server is an
   * image in three access logs and a heap dump.
   */
  presignImage: (contentType: string) =>
    api.post<PresignedUpload>('/api/products/images/presign', undefined, {
      query: { contentType },
    }),

  confirmMedia: (
    productId: number,
    input: {
      fileUrl: string;
      originalFilename?: string;
      contentType?: string;
      sizeBytes?: number;
      altText?: string;
      makeDefault?: boolean;
    },
    idempotencyKey?: string,
  ) =>
    api.post<ProductDetail>(`/vendor/products/${productId}/media`, input, {
      idempotent: idempotencyKey ?? true,
    }),

  reorderMedia: (productId: number, mediaIdsInOrder: number[]) =>
    api.patch<ProductDetail>(`/vendor/products/${productId}/media/reorder`, { mediaIdsInOrder }),

  deleteMedia: (productId: number, mediaId: number) =>
    api.delete<ProductDetail>(`/vendor/products/${productId}/media/${mediaId}`),

  // ── Bulk ──────────────────────────────────────────────────────────────────
  //
  // Both directions are jobs rather than blocking calls: four hundred listings
  // is not something to hold a connection open for, and a seller on a market
  // stall's connection would lose it halfway.

  /** Which columns an import understands, required ones first. */
  importTemplate: () => api.get<ImportTemplate>('/vendor/products/import-template'),

  /**
   * Reads a spreadsheet already in storage into draft listings. The format is
   * checked against the file's own bytes rather than trusted, because a
   * filename is a claim.
   */
  bulkImport: (
    input: {
      fileUrl: string;
      originalFilename?: string;
      format?: string;
      /** Update listings whose SKU already exists rather than refusing them. */
      updateExisting?: boolean;
    },
    idempotencyKey?: string,
  ) => api.post<CatalogueJob>('/vendor/products/bulk-import', input, {
    idempotent: idempotencyKey ?? true,
  }),

  /** Asks for the catalogue as a file. Answers a job, not the bytes. */
  requestExport: (includeArchived = false) =>
    api.get<CatalogueJob>('/vendor/products/export', { query: { includeArchived } }),

  /** How an import or export went. Errors are paged: a wrong template makes one per row. */
  job: (reference: string, params: { page?: number; size?: number } = {}) =>
    api.get<CatalogueJob>(`/vendor/imports/${reference}`, { query: params }),

  putTranslation: (
    productId: number,
    locale: string,
    input: {
      name?: string;
      shortDescription?: string;
      description?: string;
      machineTranslated?: boolean;
    },
  ) => api.put<ProductDetail>(`/vendor/products/${productId}/translations/${locale}`, input),
};
