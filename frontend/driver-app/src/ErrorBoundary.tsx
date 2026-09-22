/**
 * The last thing between a bug and a blank screen.
 *
 * A driver standing at a gate with a white screen has no way to tell a crashed
 * app from a dead phone, and no way to report either. So a crash gets a button
 * that reloads, and — importantly — says that nothing recorded has been lost:
 * every custody event is written to IndexedDB before it is sent, and a reload
 * picks the queue straight back up.
 */

import { Component, type ErrorInfo, type ReactNode } from 'react';

interface State {
  failed: boolean;
}

export class ErrorBoundary extends Component<{ children: ReactNode }, State> {
  state: State = { failed: false };

  static getDerivedStateFromError(): State {
    return { failed: true };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    // Deliberately console only. Sending this anywhere would mean shipping a
    // component stack off a device whose screens carry recipients' addresses.
    console.error('[driver-app] crashed', error, info.componentStack);
  }

  render() {
    if (!this.state.failed) return this.props.children;

    return (
      <div className="screen screen--plain center">
        <span style={{ fontSize: 64 }} aria-hidden="true">
          🛠️
        </span>
        <h1>Something went wrong</h1>
        <p className="muted">
          Nothing you recorded has been lost. It is saved on this phone and will be sent when you
          have signal.
        </p>
        <button type="button" className="button button--brand button--major" onClick={() => window.location.reload()}>
          Start again
        </button>
      </div>
    );
  }
}
