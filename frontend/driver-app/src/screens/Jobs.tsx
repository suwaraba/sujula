/**
 * The home screen: am I working, what is on offer, and what am I carrying.
 *
 * The switch at the top is the whole app in one control. Nothing is offered to
 * a driver who has not said they are working, and nothing is tracked either —
 * `POST /driver/location` is refused while off duty, which is the platform
 * declining to follow somebody who has finished for the day.
 *
 * An offer carries a town, a distance and what it pays, and never an address.
 * The same leg goes to several drivers and one takes it; showing the
 * destination would hand a recipient's home to everybody who declined. That is
 * the backend's rule, and this screen is built so there is nothing to leak: the
 * offer object simply has no street in it.
 */

import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  useAcceptAssignment,
  useAssignments,
  useDeclineAssignment,
  useDriverProfile,
  useSetAvailability,
} from '../api/queries';
import { useLocationTracker } from '../location/tracker';
import { Banner, Button, Card, Empty, Pill, Spinner, useToast } from '../components/ui';
import { Sheet } from '../components/Sheet';
import { ConnectionBar, TopBar } from '../components/Chrome';
import { distance, money } from '../lib/format';
import { useT } from '../i18n';
import type { Assignment } from '../api/types';
import { ApiError } from '../api/http';

export function Jobs() {
  const t = useT();
  const toast = useToast();
  const navigate = useNavigate();

  const profile = useDriverProfile();
  const online = profile.data?.online ?? false;

  const availability = useSetAvailability();
  const assignments = useAssignments(online);
  const accept = useAcceptAssignment();
  const decline = useDeclineAssignment();

  const [declining, setDeclining] = useState<Assignment | null>(null);

  // The live position feed and the throttled upload behind it. Started here
  // rather than at the app root so it is tied to the screen a working driver
  // has open, and stops the moment they go off duty.
  const { problem: positionProblem } = useLocationTracker(online);

  const toggle = async () => {
    try {
      const result = await availability.mutateAsync(!online);
      toast(result.message ?? (result.online ? t('home.online') : t('home.offline')), 'go');
    } catch (failure) {
      toast(failure instanceof ApiError ? failure.message : 'Could not change that.', 'stop');
    }
  };

  const take = async (assignment: Assignment) => {
    try {
      await accept.mutateAsync({ legId: assignment.legId });
      toast('It is yours. Go to the shop.', 'go');
      navigate(`/jobs/${assignment.shipmentId}`);
    } catch (failure) {
      toast(
        failure instanceof ApiError ? failure.message : 'Could not take that job.',
        'stop',
      );
    }
  };

  const offers = assignments.data?.offered ?? [];
  const mine = assignments.data?.accepted ?? [];

  return (
    <>
      <ConnectionBar />
      <TopBar
        title={t('nav.jobs')}
        action={
          <Pill tone={online ? 'go' : 'plain'} icon={online ? '🟢' : '⚪'}>
            {online ? t('home.online') : t('home.offline')}
          </Pill>
        }
      />

      <div className="screen">
        <Button
          tone={online ? 'ghost' : 'go'}
          major
          icon={online ? '⏸' : '▶️'}
          busy={availability.isPending}
          onClick={() => void toggle()}
        >
          {online ? t('home.goOffline') : t('home.goOnline')}
        </Button>

        {online && positionProblem === 'denied' && (
          <Banner tone="stop" icon="📍">
            {t('position.denied')}
          </Banner>
        )}
        {online && positionProblem === 'insecure' && (
          <Banner tone="stop" icon="📍">
            This app is not on a secure address, so it cannot read your position. Tell dispatch.
          </Banner>
        )}

        {!online && (
          <Banner icon="💤" tone="warn">
            {t('home.mustBeOnline')}
          </Banner>
        )}

        {assignments.isLoading && <Spinner label={t('app.loading')} />}
        {assignments.isError && (
          <Banner tone="stop">
            {assignments.error instanceof ApiError
              ? assignments.error.message
              : 'Could not load your jobs.'}
          </Banner>
        )}

        {mine.length > 0 && (
          <>
            <h2 style={{ marginTop: 24 }}>{t('home.mine')}</h2>
            {mine.map((assignment) => (
              <AcceptedCard key={assignment.legId} assignment={assignment} />
            ))}
          </>
        )}

        {online && (
          <>
            <h2 style={{ marginTop: 24 }}>{t('home.offers')}</h2>
            {offers.length === 0 && !assignments.isLoading && (
              <Empty icon="🔔">{t('home.noOffers')}</Empty>
            )}
            {offers.map((assignment) => (
              <OfferCard
                key={assignment.legId}
                assignment={assignment}
                busy={accept.isPending}
                onAccept={() => void take(assignment)}
                onDecline={() => setDeclining(assignment)}
              />
            ))}
          </>
        )}

        {!online && mine.length === 0 && <Empty icon="📦">{t('home.noJobs')}</Empty>}
      </div>

      <DeclineSheet
        assignment={declining}
        busy={decline.isPending}
        onClose={() => setDeclining(null)}
        onDecline={async (reason) => {
          if (!declining) return;
          try {
            await decline.mutateAsync({ legId: declining.legId, reason });
            toast('Turned down.', 'plain');
          } catch (failure) {
            toast(
              failure instanceof ApiError ? failure.message : 'Could not send that.',
              'stop',
            );
          } finally {
            setDeclining(null);
          }
        }}
      />
    </>
  );
}

/**
 * How long is left to decide.
 *
 * Counted down locally from the server's `secondsToDecide` rather than from
 * `offerExpiresAt`: a phone's clock in this part of the world is routinely a
 * few minutes out, and an offer that looks expired on a wrong clock is a job a
 * driver never takes.
 */
function useCountdown(seconds: number | undefined): number | null {
  const [left, setLeft] = useState(seconds ?? null);

  useEffect(() => {
    setLeft(seconds ?? null);
    if (seconds === undefined) return undefined;
    const timer = window.setInterval(
      () => setLeft((current) => (current === null ? null : Math.max(0, current - 1))),
      1000,
    );
    return () => window.clearInterval(timer);
  }, [seconds]);

  return left;
}

function OfferCard({
  assignment,
  busy,
  onAccept,
  onDecline,
}: {
  assignment: Assignment;
  busy: boolean;
  onAccept: () => void;
  onDecline: () => void;
}) {
  const t = useT();
  const left = useCountdown(assignment.secondsToDecide ?? undefined);
  const urgent = left !== null && left <= 15;

  return (
    <Card>
      <div className="card__row">
        <span className="money">{money(assignment.earning, assignment.earningCurrency)}</span>
        {left !== null && (
          <Pill tone={urgent ? 'stop' : 'warn'} icon="⏱">
            {t('offer.expires', { seconds: left })}
          </Pill>
        )}
      </div>

      <div className="route">
        <span className="route__dot route__dot--from" aria-hidden="true" />
        <span className="route__label">{assignment.pickupFrom ?? '—'}</span>
        <span className="route__line" aria-hidden="true" />
        <span />
        <span className="route__dot route__dot--to" aria-hidden="true" />
        <span className="route__label">{assignment.dropTo ?? '—'}</span>
      </div>

      <p className="card__meta">
        {distance(assignment.distanceKm)}
        {assignment.distanceKm && ' · '}
        {t('offer.parcels', { count: assignment.parcelCount })}
      </p>

      <div className="button-row">
        <Button tone="ghost" icon="✕" onClick={onDecline} disabled={left === 0}>
          {t('offer.decline')}
        </Button>
        <Button tone="go" icon="✓" busy={busy} onClick={onAccept} disabled={left === 0}>
          {t('offer.accept')}
        </Button>
      </div>
    </Card>
  );
}

function AcceptedCard({ assignment }: { assignment: Assignment }) {
  const navigate = useNavigate();
  const t = useT();

  return (
    <button
      type="button"
      className="card card--tap"
      onClick={() => navigate(`/jobs/${assignment.shipmentId}`)}
    >
      <div className="card__row">
        <span className="card__title">{assignment.shipmentReference ?? `#${assignment.shipmentId}`}</span>
        <span className="money">{money(assignment.earning, assignment.earningCurrency)}</span>
      </div>

      <div className="route">
        <span className="route__dot route__dot--from" aria-hidden="true" />
        <span className="route__label">{assignment.pickupFrom ?? '—'}</span>
        <span className="route__line" aria-hidden="true" />
        <span />
        <span className="route__dot route__dot--to" aria-hidden="true" />
        <span className="route__label">{assignment.dropTo ?? '—'}</span>
      </div>

      <p className="card__meta">
        {t('offer.parcels', { count: assignment.parcelCount })} · {distance(assignment.distanceKm)}
      </p>
    </button>
  );
}

/**
 * Why the job was turned down.
 *
 * The backend requires a reason and uses it to decide whether to re-offer the
 * leg nearby or further away, so these are fixed choices rather than a text
 * box: a driver in a hurry types nothing useful, and "no" tells dispatch
 * nothing at all.
 */
function DeclineSheet({
  assignment,
  busy,
  onClose,
  onDecline,
}: {
  assignment: Assignment | null;
  busy: boolean;
  onClose: () => void;
  onDecline: (reason: string) => Promise<void>;
}) {
  const t = useT();
  const reasons = useMemo(
    () => [
      { icon: '📏', label: t('decline.tooFar') },
      { icon: '📦', label: t('decline.busy') },
      { icon: '💵', label: t('decline.payment') },
      { icon: '🛵', label: t('decline.vehicle') },
      { icon: '🌙', label: t('decline.finished') },
    ],
    [t],
  );

  return (
    <Sheet open={assignment !== null} onClose={onClose} title={t('decline.why')}>
      <div className="stack">
        {reasons.map((reason) => (
          <Button
            key={reason.label}
            tone="ghost"
            icon={reason.icon}
            busy={busy}
            onClick={() => void onDecline(reason.label)}
          >
            {reason.label}
          </Button>
        ))}
        <Button tone="plain" onClick={onClose}>
          {t('app.cancel')}
        </Button>
      </div>
    </Sheet>
  );
}
