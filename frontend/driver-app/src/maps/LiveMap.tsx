/**
 * The map on a job screen: the shop, the door, and where the driver is now.
 *
 * Deliberately small in scope. Turn-by-turn belongs in the Google Maps app —
 * see `navigate.ts` — and this view answers the two questions a driver asks
 * before they set off: which way is it, and how far.
 *
 * It renders nothing at all when no key is configured; the caller shows the
 * address card instead. That is the supported configuration, not a degraded
 * one.
 */

import { useEffect, useRef, useState } from 'react';
import { loadMaps, MAP_ID, mapsConfigured } from './loader';

export interface MapPoint {
  lat: number;
  lng: number;
  label: string;
  kind: 'origin' | 'destination' | 'driver';
}

interface Props {
  points: MapPoint[];
  /**
   * Draws the driving route. Off by default: every render of a route is a
   * billed Directions request, and the driver is about to open the Maps app
   * anyway, where the route is free and live.
   */
  route?: boolean;
  height?: number;
}

const PIN_COLOUR: Record<MapPoint['kind'], string> = {
  origin: '#F5A623',
  destination: '#22C55E',
  driver: '#3B82F6',
};

export function LiveMap({ points, route = false, height = 220 }: Props) {
  const container = useRef<HTMLDivElement | null>(null);
  const map = useRef<google.maps.Map | null>(null);
  const markers = useRef<google.maps.Marker[]>([]);
  const line = useRef<google.maps.Polyline | null>(null);
  const renderer = useRef<google.maps.DirectionsRenderer | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    if (!mapsConfigured() || !container.current) return;
    let cancelled = false;

    void loadMaps()
      .then((maps) => {
        if (cancelled || !container.current) return;
        map.current = new maps.Map(container.current, {
          center: { lat: points[0]?.lat ?? 13.45, lng: points[0]?.lng ?? -16.58 },
          zoom: 13,
          disableDefaultUI: true,
          zoomControl: true,
          gestureHandling: 'greedy',
          clickableIcons: false,
          ...(MAP_ID ? { mapId: MAP_ID } : {}),
        });
      })
      .catch(() => {
        if (!cancelled) setFailed(true);
      });

    return () => {
      cancelled = true;
    };
    // The map instance is created once; points are applied by the effect below.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    const instance = map.current;
    if (!instance || typeof google === 'undefined') return;

    markers.current.forEach((marker) => marker.setMap(null));
    markers.current = [];

    const bounds = new google.maps.LatLngBounds();
    points.forEach((point) => {
      const marker = new google.maps.Marker({
        position: { lat: point.lat, lng: point.lng },
        map: instance,
        title: point.label,
        icon: {
          path: google.maps.SymbolPath.CIRCLE,
          scale: point.kind === 'driver' ? 8 : 10,
          fillColor: PIN_COLOUR[point.kind],
          fillOpacity: 1,
          strokeColor: '#0B1220',
          strokeWeight: 3,
        },
      });
      markers.current.push(marker);
      bounds.extend(marker.getPosition()!);
    });

    if (points.length === 1) {
      instance.setCenter({ lat: points[0]!.lat, lng: points[0]!.lng });
      instance.setZoom(15);
    } else if (points.length > 1) {
      instance.fitBounds(bounds, 48);
    }

    line.current?.setMap(null);
    line.current = null;

    const from = points.find((point) => point.kind === 'driver') ?? points[0];
    const to = points.find((point) => point.kind === 'destination');

    if (route && from && to) {
      renderer.current ??= new google.maps.DirectionsRenderer({
        suppressMarkers: true,
        polylineOptions: { strokeColor: '#3B82F6', strokeWeight: 5, strokeOpacity: 0.9 },
      });
      renderer.current.setMap(instance);
      new google.maps.DirectionsService()
        .route({
          origin: { lat: from.lat, lng: from.lng },
          destination: { lat: to.lat, lng: to.lng },
          travelMode: google.maps.TravelMode.DRIVING,
        })
        .then((result) => renderer.current?.setDirections(result))
        // No route is not an error worth showing: the markers still say where
        // the two ends are, and the Maps app will route it properly.
        .catch(() => undefined);
    } else if (from && to) {
      line.current = new google.maps.Polyline({
        path: [
          { lat: from.lat, lng: from.lng },
          { lat: to.lat, lng: to.lng },
        ],
        map: instance,
        strokeColor: '#3B82F6',
        strokeOpacity: 0.5,
        strokeWeight: 3,
      });
    }
  }, [points, route]);

  if (!mapsConfigured() || failed) return null;

  return (
    <div
      ref={container}
      className="map"
      style={{ height }}
      role="img"
      aria-label="Map of this job"
    />
  );
}
