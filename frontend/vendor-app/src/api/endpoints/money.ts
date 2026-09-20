import { api } from '../client';
import type { Balance, PayoutRequested, Payouts, Transactions } from '../types';

export const moneyApi = {
  /**
   * Balances per currency. A seller who lists in GMD and has ever been paid for
   * an order placed in EUR holds two, and they are not addable — which is why
   * this comes back as a list rather than a number.
   */
  balance: () => api.get<Balance>('/vendor/balance'),

  transactions: (params: {
    currency?: string;
    type?: string;
    from?: string;
    to?: string;
    page?: number;
    size?: number;
  }) => api.get<Transactions>('/vendor/transactions', { query: params }),

  payouts: (params: { status?: string; page?: number; size?: number }) =>
    api.get<Payouts>('/vendor/payouts', { query: params }),

  requestPayout: (input: { currency?: string; note?: string }, idempotencyKey?: string) =>
    api.post<PayoutRequested>('/vendor/payouts/request', input, {
      idempotent: idempotencyKey ?? true,
    }),

  /** `period` is `YYYY-MM`. `format` picks CSV or PDF; JSON is the readable one. */
  statement: (period: string, params: { currency?: string; format?: 'json' | 'csv' | 'pdf' }) =>
    api.get<unknown>(`/vendor/statements/${period}`, {
      query: params,
      responseType: params.format && params.format !== 'json' ? 'blob' : 'json',
    }),
};
