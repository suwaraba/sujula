import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { reviews as reviewsApi } from '@/api/endpoints';
import { MODERATION_REASONS } from '@/api/enums';
import type { ReviewRow } from '@/api/types';
import { ActionModal } from '@/components/ActionModal';
import { DataTable, type Column } from '@/components/DataTable';
import { DecideButton } from '@/components/Decide';
import { Field, FilterBar, Select, TextArea, TriState } from '@/components/forms';
import { Pagination } from '@/components/Pagination';
import { Muted, PageHeader, Pill, humanise } from '@/components/primitives';
import { DateTime } from '@/components/Time';
import { keys, useAction, useFilters, usePaging } from '@/hooks';

const DEFAULTS = { hiddenOnly: undefined as boolean | undefined };

export function ReviewsPage() {
  const [filters, setFilters, reset] = useFilters(DEFAULTS);
  const { page, size, setPage, setSize } = usePaging();

  const query = useQuery({
    queryKey: [...keys.reviews, filters, page, size],
    queryFn: () => reviewsApi.queue({ ...filters, page, size }),
  });

  const columns: Column<ReviewRow>[] = [
    {
      key: 'review',
      header: 'Review',
      render: (row) => (
        <>
          <strong>{'★'.repeat(row.rating)}{'☆'.repeat(Math.max(0, 5 - row.rating))}</strong>{' '}
          {row.title && <strong>{row.title}</strong>}
          <br />
          <span className="review-comment">{row.comment ?? <Muted>no words, only a rating</Muted>}</span>
        </>
      ),
    },
    {
      key: 'about',
      header: 'About',
      render: (row) => (
        <>
          {row.productName}
          <br />
          <Muted>{row.storeName}</Muted>
        </>
      ),
    },
    {
      key: 'author',
      header: 'Written by',
      render: (row) => (
        <>
          {row.authorName ?? <Muted>—</Muted>}
          <br />
          {row.verifiedPurchase ? (
            <Pill tone="good">Bought it</Pill>
          ) : (
            <Pill tone="warn">No matching purchase</Pill>
          )}
        </>
      ),
    },
    {
      key: 'reports',
      header: 'Reported',
      render: (row) => (
        <>
          <strong>{row.reportCount}</strong> times
          <br />
          <Muted>{row.reportReasons.map(humanise).join(', ')}</Muted>
        </>
      ),
    },
    {
      key: 'visible',
      header: 'Visible',
      render: (row) => (row.hidden ? <Pill tone="bad">Hidden</Pill> : <Pill tone="good">Up</Pill>),
    },
    { key: 'created', header: 'Written', render: (row) => <DateTime value={row.createdAt} /> },
    {
      key: 'actions',
      header: '',
      render: (row) => (
        <div className="row-actions">
          <ModerateReview review={row} decision="publish" />
          <ModerateReview review={row} decision="reject" />
        </div>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        title="Reported reviews"
        description="A review somebody reported is not a review that is wrong. A seller reporting every two-star rating is the pattern this queue exists to make visible."
      />

      <FilterBar onReset={reset}>
        <Field label="Only ones taken down">
          <TriState
            value={filters.hiddenOnly}
            onChange={(hiddenOnly) => setFilters({ hiddenOnly })}
            yes="Hidden only"
            no="Everything"
          />
        </Field>
      </FilterBar>

      <DataTable
        columns={columns}
        rows={query.data?.content}
        rowKey={(row) => row.id}
        isLoading={query.isFetching}
        error={query.error}
        empty="Nothing has been reported."
      />

      {query.data && (
        <Pagination
          page={query.data.page}
          size={query.data.size}
          totalElements={query.data.totalElements}
          totalPages={query.data.totalPages}
          last={query.data.last}
          onPage={setPage}
          onSize={setSize}
        />
      )}
    </>
  );
}

function ModerateReview({
  review,
  decision,
}: {
  review: ReviewRow;
  decision: 'publish' | 'reject';
}) {
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState<string>('');
  const [note, setNote] = useState('');

  const action = useAction(
    () =>
      decision === 'publish'
        ? reviewsApi.publish(review.id, { reason: reason as never, note: note.trim() || null })
        : reviewsApi.reject(review.id, { reason: reason as never, note: note.trim() || null }),
    { invalidate: [keys.reviews, keys.dashboard], message: (made) => made.message },
  );

  return (
    <>
      <DecideButton
        variant={decision === 'publish' ? 'secondary' : 'ghost'}
        onClick={() => setOpen(true)}
      >
        {decision === 'publish' ? 'Leave it up' : 'Take it down'}
      </DecideButton>
      <ActionModal
        open={open}
        title={decision === 'publish' ? 'Leave this review up' : 'Take this review down'}
        description={
          decision === 'publish'
            ? 'Clears the reports against it. The review stays where buyers can read it.'
            : 'The review comes down. Do this for what it says, never for what it scores.'
        }
        submitLabel={decision === 'publish' ? 'Leave it up' : 'Take it down'}
        tone={decision === 'publish' ? 'primary' : 'danger'}
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
        disabled={!reason}
      >
        <Field label="Reason" required>
          <Select value={reason} required options={MODERATION_REASONS} placeholder="Choose" onChange={setReason} />
        </Field>
        <Field label="Note" hint="Internal.">
          <TextArea value={note} onChange={setNote} />
        </Field>
      </ActionModal>
    </>
  );
}
