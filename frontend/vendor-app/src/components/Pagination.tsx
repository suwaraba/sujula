import { Button } from './ui';

export function Pagination({
  page, totalPages, totalElements, onChange, unit = 'items',
}: {
  page: number;
  totalPages: number;
  totalElements: number;
  onChange: (page: number) => void;
  unit?: string;
}) {
  if (totalPages <= 1) {
    return totalElements > 0 ? (
      <div className="pagination small muted">
        {totalElements} {unit}
      </div>
    ) : null;
  }

  return (
    <div className="pagination">
      <Button
        size="sm"
        variant="secondary"
        onClick={() => onChange(page - 1)}
        disabled={page <= 0}
      >
        ← Previous
      </Button>
      <span className="small muted num">
        Page {page + 1} of {totalPages} · {totalElements} {unit}
      </span>
      <Button
        size="sm"
        variant="secondary"
        onClick={() => onChange(page + 1)}
        disabled={page >= totalPages - 1}
      >
        Next →
      </Button>
    </div>
  );
}
