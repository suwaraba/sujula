/**
 * The gates, in the order they have to be passed.
 *
 *   signed in? ──► PIN set? ──► unlocked? ──► is this a driver account?
 *                                              ──► has dispatch approved it?
 *                                                    ──► the app
 *
 * Every one of them is answered by the server or by the vault, never by a flag
 * this app keeps. In particular "is this a driver account" is answered by
 * whether `GET /driver/profile` opens: the backend gates it on the delivery
 * role, which dispatch grants when it approves an application. A 403 there is
 * not an error to show — it is an application still waiting, and it is the only
 * signal there is, because a customer account genuinely cannot read a driver
 * profile that does not belong to it yet.
 */

import { useEffect } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import { isDriverAccount, useSession } from './auth/session';
import { useDriverProfile } from './api/queries';
import { ApiError } from './api/http';
import { Spinner } from './components/ui';
import { Tabs } from './components/Chrome';
import { SignIn } from './screens/SignIn';
import { SetPin, Unlock } from './screens/PinGate';
import { Apply, AwaitingReview, Blocked, ProfileLoading } from './screens/Apply';
import { Jobs } from './screens/Jobs';
import { Job } from './screens/Job';
import { Earnings } from './screens/Earnings';
import { History } from './screens/History';
import { Me } from './screens/Me';
import { startFlushLoop } from './offline/outbox';

export function App() {
  const { state, me } = useSession();

  // The queue keeps moving for as long as the app is open, whatever screen is
  // showing. Started once, at the top, because a driver who navigates away
  // from their job list has not stopped having unsent handovers.
  useEffect(() => startFlushLoop(), []);

  if (state === 'starting') {
    return (
      <div className="screen screen--plain">
        <Spinner label="Starting" />
      </div>
    );
  }

  if (state === 'signedOut') return <SignIn />;
  if (state === 'settingPin') return <SetPin />;
  if (state === 'locked') return <Unlock />;

  // Signed in and unlocked, but this may still be a customer account that has
  // applied and not yet been approved.
  if (!isDriverAccount(me)) return <Apply />;

  return <Approved />;
}

function Approved() {
  const profile = useDriverProfile();

  if (profile.isLoading) return <ProfileLoading />;

  if (profile.isError) {
    const error = profile.error;
    // 403: the role is not granted yet. 404: no profile exists, so apply.
    if (error instanceof ApiError && error.status === 403) return <AwaitingReview />;
    if (error instanceof ApiError && error.isNotFound) return <Apply />;
    return <AwaitingReview />;
  }

  const status = profile.data?.status;
  if (status === 'PENDING') return <AwaitingReview />;
  if (status === 'SUSPENDED' || status === 'REJECTED') {
    return <Blocked status={status} reason={profile.data?.kyc?.rejectionReason} />;
  }

  return (
    <div className="app">
      <Routes>
        <Route path="/" element={<Navigate to="/jobs" replace />} />
        <Route path="/jobs" element={<Jobs />} />
        <Route path="/jobs/:id" element={<Job />} />
        <Route path="/earnings" element={<Earnings />} />
        <Route path="/history" element={<History />} />
        <Route path="/me" element={<Me />} />
        <Route path="*" element={<Navigate to="/jobs" replace />} />
      </Routes>
      <Tabs />
    </div>
  );
}
