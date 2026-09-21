import type { ReactNode } from 'react';
import { EmptyState, ErrorBanner, Loading } from './primitives';

export interface Column<Row> {
  key: string;
  header: ReactNode;
  render(row: Row): ReactNode;
  align?: 'left' | 'right';
  width?: string;
}

export function DataTable<Row>({
  columns,
  rows,
  rowKey,
  isLoading,
  error,
  empty = 'Nothing here.',
  onRowClick,
  rowClassName,
  caption,
}: {
  columns: Column<Row>[];
  rows: Row[] | undefined;
  rowKey(row: Row): string | number;
  isLoading?: boolean;
  error?: unknown;
  empty?: ReactNode;
  onRowClick?(row: Row): void;
  rowClassName?(row: Row): string | undefined;
  caption?: string;
}) {
  if (error) return <ErrorBanner error={error} />;
  if (isLoading && !rows) return <Loading />;
  if (!rows || rows.length === 0) return <EmptyState>{empty}</EmptyState>;

  return (
    <div className="table-scroll">
      <table className="table">
        {caption && <caption className="sr-only">{caption}</caption>}
        <thead>
          <tr>
            {columns.map((column) => (
              <th
                key={column.key}
                scope="col"
                style={column.width ? { width: column.width } : undefined}
                className={column.align === 'right' ? 'align-right' : undefined}
              >
                {column.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr
              key={rowKey(row)}
              className={[onRowClick ? 'clickable' : '', rowClassName?.(row) ?? '']
                .filter(Boolean)
                .join(' ')}
              onClick={onRowClick ? () => onRowClick(row) : undefined}
            >
              {columns.map((column) => (
                <td key={column.key} className={column.align === 'right' ? 'align-right' : undefined}>
                  {column.render(row)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
      {isLoading && <p className="table-refreshing">Refreshing…</p>}
    </div>
  );
}
