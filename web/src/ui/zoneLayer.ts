import L from 'leaflet';
import type { Zone } from '../api/geofences';
import { escapeHtml } from './html';

export const RULE_LABEL: Record<string, string> = {
  stayInside: 'Debe permanecer dentro',
  keepOut: 'No debe entrar',
};

/** The Leaflet shape of a zone (circle or polygon), or null when its geometry is incomplete. */
export function zoneShape(z: Zone, opts: L.PathOptions = {}): L.Circle | L.Polygon | null {
  const style: L.PathOptions = {
    color: z.color || '#2563eb',
    weight: 2,
    opacity: z.active ? 0.9 : 0.45,
    fillColor: z.color || '#2563eb',
    fillOpacity: z.active ? 0.12 : 0.04,
    // "No debe entrar" zones are drawn dashed so the two rules read differently at a glance.
    dashArray: z.rule === 'keepOut' ? '6 6' : undefined,
    ...opts,
  };
  if (z.shape === 'polygon') {
    if (!z.points || z.points.length < 3) return null;
    return L.polygon(z.points, style);
  }
  if (z.centerLat == null || z.centerLon == null || !z.radiusM) return null;
  return L.circle([z.centerLat, z.centerLon], { ...style, radius: z.radiusM });
}

/** Draw the zones into a layer (cleared first). Returns their combined bounds, or null when none was drawn. */
export function drawZones(layer: L.LayerGroup, zones: Zone[], opts: { interactive?: boolean } = {}): L.LatLngBounds | null {
  layer.clearLayers();
  let bounds: L.LatLngBounds | null = null;
  for (const z of zones) {
    const shape = zoneShape(z, { interactive: opts.interactive ?? true });
    if (!shape) continue;
    shape.addTo(layer);
    if (opts.interactive ?? true) {
      // Leaflet renders tooltip strings as HTML and the name is typed by a user: escape it.
      shape.bindTooltip(
        `<strong>${escapeHtml(z.name)}</strong><br>${escapeHtml(RULE_LABEL[z.rule] ?? z.rule)}${z.active ? '' : ' · inactiva'}`,
        { sticky: true },
      );
    }
    const b = shape.getBounds();
    bounds = bounds ? bounds.extend(b) : L.latLngBounds(b.getSouthWest(), b.getNorthEast());
  }
  return bounds;
}
