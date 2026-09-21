import { api } from '../client';
import type {
  AnalyticsCustomers, AnalyticsDelivery, AnalyticsOverview,
  AnalyticsProducts, AnalyticsSales,
} from '../types';

type Range = { from?: string; to?: string };

export const analyticsApi = {
  overview: (range: Range) => api.get<AnalyticsOverview>('/vendor/analytics/overview', { query: range }),

  sales: (range: Range & { groupBy?: 'day' | 'week' | 'month' }) =>
    api.get<AnalyticsSales>('/vendor/analytics/sales', { query: range }),

  products: (range: Range & { limit?: number }) =>
    api.get<AnalyticsProducts>('/vendor/analytics/products', { query: range }),

  customers: (range: Range) =>
    api.get<AnalyticsCustomers>('/vendor/analytics/customers', { query: range }),

  delivery: (range: Range) =>
    api.get<AnalyticsDelivery>('/vendor/analytics/delivery', { query: range }),
};
