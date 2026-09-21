/**
 * Google Maps, loaded once and only when a map is actually on screen.
 *
 * The key is optional on purpose. A driver's phone in Serrekunda may be on a
 * prepaid bundle where the map tiles cost more than the job pays, and a
 * deployment may not have a key at all — so the whole app is built to work
 * without one. Where there is no key the map becomes a card with the address
 * and the distance, and every "Navigate" button opens the Google Maps app by
 * URL, which needs no key and no script.
 *
 * Loaded lazily rather than in `index.html` for the same reason: this is the
 * single heaviest thing the app can fetch, and it should not be on the path
 * between a driver tapping the icon and seeing their jobs.
 */

const API_KEY = import.meta.env.VITE_GOOGLE_MAPS_API_KEY ?? '';
export const MAP_ID = import.meta.env.VITE_GOOGLE_MAPS_MAP_ID ?? '';

export function mapsConfigured(): boolean {
  return API_KEY.length > 0;
}

let loading: Promise<typeof google.maps> | null = null;

export function loadMaps(): Promise<typeof google.maps> {
  if (!mapsConfigured()) {
    return Promise.reject(new Error('No Google Maps key is configured.'));
  }
  if (loading) return loading;

  loading = new Promise<typeof google.maps>((resolve, reject) => {
    if (typeof google !== 'undefined' && google.maps?.Map) {
      resolve(google.maps);
      return;
    }

    const script = document.createElement('script');
    const params = new URLSearchParams({
      key: API_KEY,
      v: 'weekly',
      libraries: 'marker,geometry',
      loading: 'async',
    });
    script.src = `https://maps.googleapis.com/maps/api/js?${params.toString()}`;
    script.async = true;
    script.defer = true;
    // Nothing on this page should be reachable from a third-party script's
    // referrer, and the key should be origin-restricted in the Google console
    // on top of this.
    script.referrerPolicy = 'origin';
    script.onerror = () => {
      loading = null;
      reject(new Error('The map could not be loaded.'));
    };
    script.onload = () => {
      if (google?.maps?.Map) resolve(google.maps);
      else {
        loading = null;
        reject(new Error('The map loaded but is not usable.'));
      }
    };
    document.head.appendChild(script);
  });

  return loading;
}
