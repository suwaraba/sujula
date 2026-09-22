export function Pagination({
  page,
  size,
  totalElements,
  totalPages,
  last,
  onPage,
  onSize,
}: {
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  last: boolean;
  onPage(page: number): void;
  onSize?(size: number): void;
}) {
  const first = totalElements === 0 ? 0 : page * size + 1;
  const lastShown = Math.min((page + 1) * size, totalElements);

  return (
    <nav className="pagination" aria-label="Pagination">
      <span className="pagination-summary">
        {totalElements === 0
          ? 'No rows'
          : `${first.toLocaleString()}–${lastShown.toLocaleString()} of ${totalElements.toLocaleString()}`}
      </span>
      <div className="pagination-controls">
        {onSize && (
          <label className="pagination-size">
            Rows
            <select
              className="input input-small"
              value={size}
              onChange={(event) => onSize(Number(event.target.value))}
            >
              {[20, 50, 100].map((option) => (
                <option key={option} value={option}>
                  {option}
                </option>
              ))}
            </select>
          </label>
        )}
        <button
          type="button"
          className="button button-ghost"
          disabled={page <= 0}
          onClick={() => onPage(page - 1)}
        >
          Previous
        </button>
        <span className="pagination-page">
          Page {page + 1} of {Math.max(totalPages, 1)}
        </span>
        <button
          type="button"
          className="button button-ghost"
          disabled={last}
          onClick={() => onPage(page + 1)}
        >
          Next
        </button>
      </div>
    </nav>
  );
}
