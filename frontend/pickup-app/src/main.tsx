import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import './styles/app.css';

/**
 * Registers the service worker, which is what makes this installable on a
 * counter tablet. It caches the app shell only — never an API response, which
 * carries recipients' names and is served `no-store` for that reason.
 *
 * A registration failure is not worth telling anybody about: the app runs the
 * same either way, it just cannot be installed to the home screen.
 */
if ('serviceWorker' in navigator && import.meta.env.PROD) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch(() => undefined);
  });
}

const container = document.getElementById('root');
if (!container) throw new Error('No #root element to mount into');

createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
