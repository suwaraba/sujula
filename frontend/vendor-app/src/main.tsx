import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import './styles/app.css';

/**
 * Safe-area insets as CSS variables.
 *
 * `env(safe-area-inset-*)` works in the WebView, but only once the viewport is
 * declared `viewport-fit=cover` — which index.html does. This just makes the
 * values readable from anywhere in the stylesheet under one name, so the tab
 * bar clears the home indicator on an iPhone and the header clears the notch.
 */
function applySafeAreas() {
  const root = document.documentElement;
  const styles = getComputedStyle(root);
  for (const edge of ['top', 'bottom'] as const) {
    const value = styles.getPropertyValue(`--safe-${edge}`);
    if (!value.trim()) root.style.setProperty(`--safe-${edge}`, '0px');
  }
}

applySafeAreas();

const container = document.getElementById('root');
if (!container) throw new Error('No #root element to mount into');

createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
