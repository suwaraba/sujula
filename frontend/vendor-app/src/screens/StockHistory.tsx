import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { inventoryApi } from '@/api/endpoints/inventory';
import { formatDateTime, humanise } from '@/lib/format';
import { Card, EmptyState, PageHeader, SkeletonList, Stat } from '@/components/ui';
import { Pagination } from '@/components/Pagination';

export function StockHistory() {
  const { variantId } = useParams<{ variantId: string }>();
  const id = Number(variantId);
  const [page, setPage] = useState(0);

  const history = useQuery({
    queryKey: ['inventory', 'movements', id, page],
    queryFn: () => inventoryApi.movements(id, { page, size: 30 }),
    enabled: Number.isFinite(id),
  });

  const data = history.data;

  return (
    <div className="page stack">
      <PageHeader
        title="Stock history"
        subtitle={
          data ? <span className="mono">{data.sku ?? `variant ${data.variantId}`}</span> : undefined
        }
        actions={<Link to="/inventory" className="btn btn--secondary">← Back to stock</Link>}
      />

      {data && (
        <Card flush>
          <Stat
            label="In stock now"
            value={data.currentStock}
            note="Every movement below adds up to this"
          />
        </Card>
      )}

      <Card flush>
        {history.isLoading ? (
          <SkeletonList rows={6} />
        ) : (data?.movements.length ?? 0) === 0 ? (
          <EmptyState icon="▤" title="No movements recorded">
            Nothing has moved this item's stock yet.
          </EmptyState>
        ) : (
          <>
            <div className="table-wrap">
              <table className="table">
                <thead>
                  <tr>
                    <th>When</th>
                    <th>Why</th>
                    <th className="num">Change</th>
                    <th className="num">Left</th>
                    <th>Who</th>
                    <th>Note</th>
                  </tr>
                </thead>
                <tbody>
                  {data!.movements.map((movement) => (
                    <tr key={movement.id}>
                      <td className="small">{formatDateTime(movement.recordedAt)}</td>
                      <td>{humanise(movement.reason)}</td>
                      <td
                        className="num"
                        style={{
                          color: movement.quantityChange >= 0 ? 'var(--ok)' : 'var(--danger)',
                          fontWeight: 650,
                        }}
                      >
                        {movement.quantityChange >= 0 ? '+' : ''}{movement.quantityChange}
                      </td>
                      <td className="num">{movement.stockAfter}</td>
                      <td className="small muted">{movement.recordedBy ?? '—'}</td>
                      <td className="small muted" style={{ whiteSpace: 'normal', maxWidth: 280 }}>
                        {movement.note ?? movement.reference ?? '—'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pagination
              page={data!.page}
              totalPages={data!.totalPages}
              totalElements={data!.totalElements}
              onChange={setPage}
              unit="movements"
            />
          </>
        )}
      </Card>
    </div>
  );
}
