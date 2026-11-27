import { useEffect, useRef } from 'react';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import type { Zone } from '../api/geofences';
import { drawZones, zoneBounds, zoneShape } from '../ui/zoneLayer';

export type DrawMode = 'idle' | 'circle' | 'polygon';

const handle = (cls: string) =>
  L.divIcon({ className: `gz-handle ${cls}`, iconSize: [14, 14], iconAnchor: [7, 7] });

/** The point [metres] east of a position (for the circle's resize handle). */
function eastOf(lat: number, lon: number, metres: number): [number, number] {
  const dLon = (metres / (6_371_008.8 * Math.cos((lat * Math.PI) / 180))) * (180 / Math.PI);
  return [lat, lon + dLon];
}

/**
 * The zone editor's map, built on plain Leaflet (no drawing plugin): the other zones are shown faint; the zone
 * being edited has handles.
 *  - Circle: click the map to place the centre; drag the centre to move it and the edge handle to resize it.
 *  - Polygon: each click adds a vertex; drag a vertex to move it, click a small mid-point handle to add one
 *    there, right-click (or double-click) a vertex to remove it.
 * While a handle is dragged only the shape is updated; the change is reported when the drag ends.
 */
export function ZoneEditorMap({
  zones, draft, mode, onChange, focusKey,
}: {
  zones: Zone[];
  draft: Zone | null;
  mode: DrawMode;
  onChange: (patch: Partial<Zone>) => void;
  /** Changes when the map should fit to the draft / the zones again (e.g. the id being edited). */
  focusKey: string;
}) {
  const el = useRef<HTMLDivElement>(null);
  const map = useRef<L.Map | null>(null);
  const others = useRef<L.LayerGroup | null>(null);
  const editing = useRef<L.LayerGroup | null>(null);
  // The click handler is registered once; it reads the current values through refs.
  const live = useRef({ draft, mode, onChange });
  live.current = { draft, mode, onChange };

  useEffect(() => {
    if (!el.current || map.current) return;
    const m = L.map(el.current, { worldCopyJump: true, doubleClickZoom: false }).setView([4.6, -74.1], 6);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19, attribution: '© colaboradores de OpenStreetMap',
    }).addTo(m);
    others.current = L.layerGroup().addTo(m);
    editing.current = L.layerGroup().addTo(m);
    m.on('click', (e: L.LeafletMouseEvent) => {
      const { draft: d, mode: md, onChange: change } = live.current;
      if (!d) return;
      if (md === 'circle' && d.shape === 'circle' && d.centerLat == null) {
        // A first radius that is visible at the current zoom: about an eighth of the map's width.
        const b = m.getBounds();
        const width = m.distance(b.getSouthWest(), b.getSouthEast());
        const radius = Math.max(50, Math.min(50_000, Math.round(width / 8 / 10) * 10));
        change({ centerLat: e.latlng.lat, centerLon: e.latlng.lng, radiusM: d.radiusM || radius });
      } else if (md === 'polygon' && d.shape === 'polygon') {
        change({ points: [...(d.points ?? []), [e.latlng.lat, e.latlng.lng]] });
      }
    });
    map.current = m;
    setTimeout(() => m.invalidateSize(), 0);
    return () => { m.remove(); map.current = null; };
  }, []);

  // The other zones.
  useEffect(() => {
    if (!others.current) return;
    drawZones(others.current, zones.filter((z) => !draft || z.id !== draft.id));
  }, [zones, draft]);

  // The zone being edited, with its handles.
  useEffect(() => {
    const m = map.current, layer = editing.current;
    if (!m || !layer) return;
    layer.clearLayers();
    if (!draft) return;
    const change = (patch: Partial<Zone>) => live.current.onChange(patch);

    if (draft.shape === 'circle' && draft.centerLat != null && draft.centerLon != null && draft.radiusM) {
      const circle = zoneShape(draft, { interactive: false }) as L.Circle;
      circle.addTo(layer);
      let centre = L.latLng(draft.centerLat, draft.centerLon);
      let radius = draft.radiusM;
      const edge = L.marker(eastOf(centre.lat, centre.lng, radius), {
        icon: handle('gz-edge'), draggable: true, title: 'Arrastra para cambiar el radio',
      }).addTo(layer);
      const mid = L.marker(centre, {
        icon: handle('gz-centre'), draggable: true, title: 'Arrastra para mover la zona',
      }).addTo(layer);
      mid.on('drag', () => {
        centre = mid.getLatLng();
        circle.setLatLng(centre);
        edge.setLatLng(eastOf(centre.lat, centre.lng, radius));
      });
      mid.on('dragend', () => change({ centerLat: centre.lat, centerLon: centre.lng }));
      edge.on('drag', () => {
        radius = Math.max(20, m.distance(centre, edge.getLatLng()));
        circle.setRadius(radius);
      });
      edge.on('dragend', () => change({ radiusM: Math.round(radius) }));
    }

    if (draft.shape === 'polygon' && draft.points?.length) {
      const pts = draft.points.map((p) => [p[0], p[1]] as [number, number]);
      const shape = pts.length >= 3
        ? L.polygon(pts, { color: draft.color, weight: 2, fillOpacity: 0.12, interactive: false,
            dashArray: draft.rule === 'keepOut' ? '6 6' : undefined })
        : L.polyline(pts, { color: draft.color, weight: 2, dashArray: '4 4', interactive: false });
      shape.addTo(layer);
      pts.forEach((p, i) => {
        const v = L.marker(p, {
          icon: handle('gz-vertex'), draggable: true,
          title: 'Arrastra para mover · clic derecho para quitar',
        }).addTo(layer);
        v.on('drag', () => {
          const ll = v.getLatLng();
          pts[i] = [ll.lat, ll.lng];
          shape.setLatLngs(pts);
        });
        v.on('dragend', () => change({ points: pts.map((x) => [x[0], x[1]] as [number, number]) }));
        const remove = (e: L.LeafletMouseEvent) => {
          e.originalEvent?.preventDefault();
          if (pts.length <= 3) return;
          change({ points: pts.filter((_, k) => k !== i) });
        };
        v.on('contextmenu', remove);
        v.on('dblclick', remove);
      });
      if (pts.length >= 3) {
        pts.forEach((p, i) => {
          const q = pts[(i + 1) % pts.length];
          const at: [number, number] = [(p[0] + q[0]) / 2, (p[1] + q[1]) / 2];
          L.marker(at, { icon: handle('gz-mid'), title: 'Clic para agregar un vértice aquí' })
            .addTo(layer)
            .on('click', () => {
              const next = pts.map((x) => [x[0], x[1]] as [number, number]);
              next.splice(i + 1, 0, at);
              change({ points: next });
            });
        });
      }
    }
  }, [draft]);

  // Fit once per focus change: to the zone being edited, else to every zone.
  useEffect(() => {
    const m = map.current;
    if (!m) return;
    let bounds: L.LatLngBounds | null = draft ? zoneBounds(draft) : null;
    if (!bounds && !draft) {
      for (const z of zones) {
        const b = zoneBounds(z);
        if (b) bounds = bounds ? bounds.extend(b) : b;
      }
    }
    if (bounds?.isValid()) m.fitBounds(bounds, { padding: [48, 48], maxZoom: 17 });
    setTimeout(() => m.invalidateSize(), 0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [focusKey, zones.length]);

  return <div ref={el} className={`fm-map gz-map ${draft && mode !== 'idle' ? 'gz-drawing' : ''}`} />;
}
