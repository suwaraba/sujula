import { api } from '../client';
import type { CategoryTree, Countries, Currencies } from '../types';

export const referenceApi = {
  /** Carries `minorUnits` per currency, which is what correct rounding depends on. */
  currencies: () => api.get<Currencies>('/currencies'),

  countries: () => api.get<Countries>('/countries'),

  /**
   * The public tree. `/api/categories` is the administrative surface and
   * answers a flat list; this one is what a listing form should offer.
   */
  categories: () => api.get<CategoryTree>('/categories'),

  publicConfig: () =>
    api.get<{
      features: Record<string, boolean>;
      minimumAppVersions: Record<string, string>;
      support: { email?: string; phone?: string; whatsapp?: string } | null;
      baseCurrency: string;
    }>('/config/public'),
};
