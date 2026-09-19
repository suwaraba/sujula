import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { shipments as shipmentsApi } from '@/api/endpoints';
import type { DriverCandidate, UnassignedShipment } from '@/api/types';
import { ActionModal } from '@/components/ActionModal';
import { DecideButton } from '@/components/Decide';
import { Field, NumberInput, TextArea } from '@/components/forms';
import {
  Card,
  EmptyState,
  ErrorBanner,
  KeyValue,
  KeyValueList,
  Loading,
  Muted,
  PageHeader,
  Pill,
  StatusPill,
} from '@/components/primitives';
import { Hours } from '@/components/Time';
import { keys, useAction } from '@/hooks';

/**
 * Parcels nobody is carrying, each with who could carry it.
 *
 * The candidate list comes from the server and is ranked against the parcel's
 * own zone — not against where anybody involved in paying for it is. The `why`
 * on each candidate is the server's own reasoning, shown rather than
 * re-derived, so an administrator overriding the order can see what they are
 * overriding.
 */
export function UnassignedPage() {
  const query = useQuery({
    queryKey: keys.unassigned,
    queryFn: () => shipmentsApi.unassigned(25),
    refetchInterval: 60_000,
  });

  return (
    <>
      <PageHeader
        title="Parcels nobody is carrying"
        description="Ranked by how long they have been waiting. Each carries the drivers the server would offer it to, and why."
      />

      <ErrorBanner error={query.error} />
      {query.isLoading && <Loading what="Looking for parcels" />}

      {query.data?.length === 0 && <EmptyState>Every parcel has somebody carrying it.</EmptyState>}

      {query.data?.map((entry) => (
        <UnassignedCard key={entry.parcel.id} entry={entry} />
      ))}
    </>
  );
}

function UnassignedCard({ entry }: { entry: UnassignedShipment }) {
  const { parcel, candidates, note } = entry;

  return (
    <Card
      title={
        <>
          <span className="mono">{parcel.reference}</span> <StatusPill status={parcel.status} />
        </>
      }
      subtitle={note ?? undefined}
    >
      <KeyValueList>
        <KeyValue label="Store">{parcel.storeName ?? <Muted>—</Muted>}</KeyValue>
        <KeyValue label="Goes to">
          {parcel.destinationCity ?? '—'}, {parcel.destinationCountry ?? '—'}
        </KeyValue>
        <KeyValue label="Waiting">
          <Hours value={parcel.hoursWaiting} overdue={parcel.overdue} />
        </KeyValue>
        <KeyValue label="Failed attempts">{parcel.failedAttempts}</KeyValue>
      </KeyValueList>

      {candidates.length === 0 ? (
        <EmptyState>
          No driver is available for this parcel's zone. Approve one, widen a driver's zones, or take
          it on a leg by hand.
        </EmptyState>
      ) : (
        <table className="table table-compact">
          <thead>
            <tr>
              <th scope="col">Driver</th>
              <th scope="col">Zone</th>
              <th scope="col" className="align-right">
                Distance
              </th>
              <th scope="col" className="align-right">
                Accepts
              </th>
              <th scope="col" className="align-right">
                Open jobs
              </th>
              <th scope="col">Why</th>
              <th scope="col" />
            </tr>
          </thead>
          <tbody>
            {candidates.map((candidate) => (
              <CandidateRow key={candidate.driverId} shipmentId={parcel.id} candidate={candidate} />
            ))}
          </tbody>
        </table>
      )}
    </Card>
  );
}

function CandidateRow({
  shipmentId,
  candidate,
}: {
  shipmentId: number;
  candidate: DriverCandidate;
}) {
  const [open, setOpen] = useState(false);
  const [minutes, setMinutes] = useState<number | ''>(15);
  const [note, setNote] = useState('');

  const action = useAction(
    () =>
      shipmentsApi.assign(shipmentId, {
        driverId: candidate.driverId,
        acceptanceMinutes: minutes === '' ? null : minutes,
        note: note.trim() || null,
      }),
    {
      invalidate: [keys.unassigned, keys.shipments, keys.dashboard],
      message: (made) => made.message,
    },
  );

  return (
    <tr>
      <td>
        <strong>{candidate.name}</strong>
        <br />
        <Muted>{candidate.phone ?? '—'}</Muted>
        {!candidate.available && <Pill tone="warn">Offline</Pill>}
      </td>
      <td>{candidate.zone ?? <Muted>—</Muted>}</td>
      <td className="align-right">
        {candidate.distanceKm === null ? <Muted>—</Muted> : `${candidate.distanceKm.toFixed(1)} km`}
      </td>
      <td className="align-right">
        {candidate.acceptanceScore === null ? (
          <Muted>—</Muted>
        ) : (
          `${Number(candidate.acceptanceScore).toFixed(0)}%`
        )}
      </td>
      <td className="align-right">{candidate.openJobs}</td>
      <td>
        <Muted>{candidate.why ?? '—'}</Muted>
      </td>
      <td>
        <DecideButton variant="secondary" onClick={() => setOpen(true)}>
          Offer
        </DecideButton>
        <ActionModal
          open={open}
          title={`Offer this parcel to ${candidate.name}`}
          description="An offer, not an assignment: the driver accepts it, and the parcel stays unassigned until they do. It expires on its own so a parcel is never held by somebody who has stopped looking at their phone."
          submitLabel="Send the offer"
          onClose={() => setOpen(false)}
          onSubmit={() => action.mutateAsync()}
        >
          <Field label="Expires after" hint="minutes">
            <NumberInput value={minutes} min={1} max={240} onChange={setMinutes} />
          </Field>
          <Field label="Note for the driver">
            <TextArea value={note} onChange={setNote} />
          </Field>
        </ActionModal>
      </td>
    </tr>
  );
}
