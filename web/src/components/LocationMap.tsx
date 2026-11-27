import { useEffect, useRef } from 'react';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import type { LocationFix } from '../api/deviceLocations';
import { escapeHtml } from '../ui/html';
import type { Zone } from '../api/geofences';
import { drawZones } from '../ui/zoneLayer';

/** The fix at or before time [t] (binary search over an ASCENDING-by-time array). */
function positionAt(chrono: LocationFix[], t: number): LocationFix | null {
  let lo = 0, hi = chrono.length - 1, best = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (chrono[mid].capturedAt <= t) { best = mid; lo = mid + 1; } else hi = mid - 1;
  }
  return best < 0 ? null : chrono[best];
}

/**
 * Leaflet + OpenStreetMap breadcrumb map. [fixes] are newest-first (as the API returns them);
 * we draw the trail chronologically with a polyline + circle markers and highlight the latest fix.
 * Uses circleMarkers (no image assets) to avoid Leaflet's bundler icon-path issues.
 *
 * When [highlightAt] is set (the time slider), a big marker shows where the device was at that moment
 * (its last fix at or before it) and the map pans to it — same idea as the fleet map's time scrubber.
 *
 * [zones] (optional) are the geofences that apply to the device, drawn under the trail. The latest fix shows its
 * accuracy as a translucent circle; fixes tagged lowAccuracy are drawn grey.
 */
export function LocationMap({ fixes, highlightAt, zones }: { fixes: LocationFix[]; highlightAt?: number; zones?: Zone[] }) {
  const elRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<L.Map | null>(null);
  const layerRef = useRef<L.LayerGroup | null>(null);
  const hlRef = useRef<L.LayerGroup | null>(null);
  const zoneRef = useRef<L.LayerGroup | null>(null);

  useEffect(() => {
    const el = elRef.current;
    if (!el) return;
    if (!mapRef.current) {
      // Canvas: a long history is thousands of markers.
      mapRef.current = L.map(el, { worldCopyJump: true, preferCanvas: true });
      L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        maxZoom: 19,
        attribution: '© colaboradores de OpenStreetMap',
      }).addTo(mapRef.current);
      zoneRef.current = L.layerGroup().addTo(mapRef.current);
      layerRef.current = L.layerGroup().addTo(mapRef.current);
      hlRef.current = L.layerGroup().addTo(mapRef.current);
    }
    const map = mapRef.current;
    const layer = layerRef.current!;
    layer.clearLayers();

    const zoneBounds = zoneRef.current ? drawZones(zoneRef.current, zones ?? []) : null;
    if (!fixes.length) {
      if (zoneBounds?.isValid()) map.fitBounds(zoneBounds, { padding: [24, 24], maxZoom: 17 });
      else map.setView([20, 0], 2);
      setTimeout(() => map.invalidateSize(), 0);
      return;
    }
    const chrono = [...fixes].reverse();
    const pts = chrono.map((f) => [f.lat, f.lon] as [number, number]);
    L.polyline(pts, { color: '#3b82f6', weight: 3, opacity: 0.65 }).addTo(layer);
    // How precise the latest position is: a circle of its ± metres.
    if (fixes[0].accuracy != null && Number(fixes[0].accuracy) > 0) {
      L.circle([fixes[0].lat, fixes[0].lon], {
        radius: Number(fixes[0].accuracy), color: '#16a34a', weight: 1, opacity: 0.6, fillOpacity: 0.08, interactive: false,
      }).addTo(layer);
    }
    fixes.forEach((f, i) => {
      const latest = i === 0;
      const tone = latest ? '#16a34a' : f.lowAccuracy ? '#9ca3af' : '#3b82f6';
      L.circleMarker([f.lat, f.lon], {
        radius: latest ? 7 : 4,
        color: tone,
        fillColor: tone,
        fillOpacity: latest ? 0.9 : 0.5,
        weight: latest ? 2 : 1,
      })
        .bindPopup(
          // Leaflet renders popup strings as HTML and the provider comes from the device: escape everything.
          escapeHtml(new Date(f.capturedAt).toLocaleString()) +
            (f.accuracy != null ? ` · ±${escapeHtml(Math.round(Number(f.accuracy)))} m` : '') +
            (f.provider ? ` · ${escapeHtml(f.provider)}` : '') +
            (f.lowAccuracy ? ' · baja precisión' : ''),
        )
        .addTo(layer);
    });
    // Only auto-fit when the slider is not driving the view (otherwise it fights the pan-to).
    if (highlightAt == null) {
      const b = L.latLngBounds(pts);
      map.fitBounds(zoneBounds?.isValid() ? b.extend(zoneBounds) : b, { padding: [24, 24], maxZoom: 17 });
    }
    // Container is often sized by layout after first paint; recompute so tiles fill it.
    setTimeout(() => map.invalidateSize(), 0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fixes, zones]);

  // Time-slider highlight: a big marker at the position for [highlightAt], and pan to it.
  useEffect(() => {
    const map = mapRef.current, hl = hlRef.current;
    if (!map || !hl) return;
    hl.clearLayers();
    if (highlightAt == null || !fixes.length) return;
    const chrono = [...fixes].reverse();
    const f = positionAt(chrono, highlightAt);
    if (!f) return;
    const stale = highlightAt - f.capturedAt;
    L.circleMarker([f.lat, f.lon], {
      radius: 10, color: '#fff', weight: 3, fillColor: '#16a34a', fillOpacity: 0.95,
    })
      .bindPopup(
        escapeHtml(new Date(f.capturedAt).toLocaleString()) +
          (stale > 10 * 60 * 1000 ? ` (${Math.round(stale / 60000)} min antes)` : '') +
          (f.accuracy != null ? ` · ±${escapeHtml(Math.round(Number(f.accuracy)))} m` : ''),
      )
      .addTo(hl);
    map.panTo([f.lat, f.lon], { animate: true });
  }, [highlightAt, fixes]);

  useEffect(
    () => () => {
      mapRef.current?.remove();
      mapRef.current = null;
    },
    [],
  );

  return <div ref={elRef} className="loc-map" />;
}
