// Thin device-type outline. Tablets get a wide body; phones a narrow one.
// Kind is inferred from the model/description string.

export type DeviceKind = 'tablet' | 'phone';

export function deviceKind(name?: string): DeviceKind {
  const s = (name ?? '').toLowerCase();
  return /tab|pad|book|sm-[xt]|signage|kiosk|display/.test(s) ? 'tablet' : 'phone';
}

export function DeviceGlyph({
  name,
  size = 16,
  className,
}: {
  name?: string;
  size?: number;
  className?: string;
}) {
  const tablet = deviceKind(name) === 'tablet';
  return (
    <svg
      className={className}
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      aria-hidden="true"
    >
      {tablet ? (
        <rect x="4" y="4" width="16" height="16" rx="2" />
      ) : (
        <rect x="7" y="3" width="10" height="18" rx="2" />
      )}
    </svg>
  );
}

/**
 * The device as a small phone (or tablet) whose screen is lit when it is online and dark when it is not — the
 * at-a-glance connection state in lists.
 */
export function DeviceStatusIcon({ online, name, size = 30 }: { online: boolean; name?: string; size?: number }) {
  const tablet = deviceKind(name) === 'tablet';
  const w = tablet ? 26 : 18;
  const label = online ? 'En línea' : 'Sin conexión';
  return (
    <svg className={`dev-status ${online ? 'on' : 'off'}`} width={(size * w) / 30} height={size} viewBox={`0 0 ${w} 30`}
         role="img" aria-label={label}>
      <title>{label}</title>
      <rect className="body" x="0.5" y="0.5" width={w - 1} height="29" rx="3.2" />
      <rect className="screen" x="2.2" y="3.6" width={w - 4.4} height="21" rx="1.2" />
      <rect className="key" x={w / 2 - 2.2} y="26.3" width="4.4" height="1.3" rx="0.65" />
    </svg>
  );
}
