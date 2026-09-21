/**
 * A step that takes over the whole screen.
 *
 * Recording a handover is not a page the driver browses away from. It covers
 * the tab bar for two reasons, one of which is a bug this replaced: a fixed
 * action button and a fixed tab bar were fighting for the same forty pixels at
 * the bottom of the phone, and the tab bar won — so the button that finishes a
 * collection could not be tapped at all.
 *
 * The other reason is that it should not be tapped away from. A driver halfway
 * through a delivery, with a code typed and a photograph taken, has nothing to
 * gain from reaching "Earnings" by accident with a parcel under one arm.
 *
 * The footer scrolls with nothing: it is a flex row at the bottom of the layer
 * rather than a fixed element, so it cannot end up underneath anything.
 */

import type { ReactNode } from 'react';

export function FocusLayer({ children, footer }: { children: ReactNode; footer?: ReactNode }) {
  return (
    <div className="focus-layer">
      <div className="focus-layer__body">{children}</div>
      {footer && <div className="focus-layer__footer">{footer}</div>}
    </div>
  );
}
