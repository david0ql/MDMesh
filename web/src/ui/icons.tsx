// Minimal hairline icon set (stroke = currentColor), 16px grid.
import type { ReactNode } from 'react';

type P = { className?: string };

function Svg({ children, className }: { children: ReactNode } & P) {
  return (
    <svg
      className={className}
      width="16"
      height="16"
      viewBox="0 0 16 16"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.4"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {children}
    </svg>
  );
}

export const IconDashboard = (p: P) => (
  <Svg {...p}>
    <rect x="2" y="2" width="5" height="5" />
    <rect x="9" y="2" width="5" height="5" />
    <rect x="2" y="9" width="5" height="5" />
    <rect x="9" y="9" width="5" height="5" />
  </Svg>
);

export const IconDevices = (p: P) => (
  <Svg {...p}>
    <rect x="4" y="1.5" width="8" height="13" rx="1.4" />
    <line x1="6.5" y1="12.5" x2="9.5" y2="12.5" />
  </Svg>
);

export const IconEnroll = (p: P) => (
  <Svg {...p}>
    <path d="M8 2v8" />
    <path d="M5 7l3 3 3-3" />
    <path d="M2.5 13.5h11" />
  </Svg>
);

export const IconAnnounce = (p: P) => (
  <Svg {...p}>
    <path d="M2.5 6.5v3h2l5 3v-9l-5 3z" />
    <path d="M12 5.5a3.5 3.5 0 0 1 0 5" />
  </Svg>
);

export const IconSettings = (p: P) => (
  <Svg {...p}>
    <circle cx="8" cy="8" r="2.2" />
    <path d="M8 1.5v1.6M8 12.9v1.6M14.5 8h-1.6M3.1 8H1.5M12.6 3.4l-1.1 1.1M4.5 11.5l-1.1 1.1M12.6 12.6l-1.1-1.1M4.5 4.5L3.4 3.4" />
  </Svg>
);

export const IconUsers = (p: P) => (
  <Svg {...p}>
    <circle cx="6" cy="5.2" r="2.4" />
    <path d="M1.8 13.5c.4-2.4 2.2-3.8 4.2-3.8s3.8 1.4 4.2 3.8" />
    <circle cx="11.4" cy="5.8" r="1.8" />
    <path d="M11.2 9.4c1.6.1 2.8 1.2 3.1 3" />
  </Svg>
);

export const IconRoles = (p: P) => (
  <Svg {...p}>
    <path d="M8 1.5l5 2v4c0 3-2.2 5.2-5 6.5-2.8-1.3-5-3.5-5-6.5v-4l5-2z" />
    <path d="M6 7.8l1.4 1.4L10.2 6" />
  </Svg>
);

export const IconSignOut = (p: P) => (
  <Svg {...p}>
    <path d="M6 2.5H3.5A1.5 1.5 0 0 0 2 4v8a1.5 1.5 0 0 0 1.5 1.5H6" />
    <path d="M10 4.5L13.5 8 10 11.5" />
    <line x1="13.5" y1="8" x2="6.5" y2="8" />
  </Svg>
);

export const IconCopy = (p: P) => (
  <Svg {...p}>
    <rect x="5.5" y="5.5" width="8" height="8" rx="1.2" />
    <path d="M3.5 10.5H3A1 1 0 0 1 2 9.5V3a1 1 0 0 1 1-1h6.5a1 1 0 0 1 1 1v.5" />
  </Svg>
);

export const IconArrowLeft = (p: P) => (
  <Svg {...p}>
    <path d="M9.5 3.5L5 8l4.5 4.5" />
    <line x1="5" y1="8" x2="13" y2="8" />
  </Svg>
);

export const IconMenu = (p: P) => (
  <Svg {...p}>
    <line x1="2.5" y1="4" x2="13.5" y2="4" />
    <line x1="2.5" y1="8" x2="13.5" y2="8" />
    <line x1="2.5" y1="12" x2="13.5" y2="12" />
  </Svg>
);

export const IconConfig = (p: P) => (
  <Svg {...p}>
    <line x1="2.5" y1="4" x2="13.5" y2="4" />
    <circle cx="6" cy="4" r="1.7" />
    <line x1="2.5" y1="8" x2="13.5" y2="8" />
    <circle cx="10.5" cy="8" r="1.7" />
    <line x1="2.5" y1="12" x2="13.5" y2="12" />
    <circle cx="5" cy="12" r="1.7" />
  </Svg>
);

export const IconReports = (p: P) => (
  <Svg {...p}>
    <rect x="3" y="2.5" width="10" height="11" rx="1.5" />
    <line x1="5.5" y1="6" x2="10.5" y2="6" />
    <line x1="5.5" y1="8.5" x2="10.5" y2="8.5" />
    <line x1="5.5" y1="11" x2="8.5" y2="11" />
  </Svg>
);

export const IconAlert = (p: P) => (
  <Svg {...p}>
    <path d="M8 2.5a3.5 3.5 0 0 0-3.5 3.5c0 3-1.2 4-1.2 4h9.4s-1.2-1-1.2-4A3.5 3.5 0 0 0 8 2.5Z" />
    <path d="M6.6 13a1.5 1.5 0 0 0 2.8 0" />
  </Svg>
);

export const IconScript = (p: P) => (
  <Svg {...p}>
    <path d="M4 2.5h8v11H4z" />
    <path d="M6 6l2 2-2 2" />
    <line x1="9" y1="10.5" x2="11" y2="10.5" />
  </Svg>
);

export const IconApps = (p: P) => (
  <Svg {...p}>
    <path d="M3.5 5h9l-.7 7.8a1 1 0 0 1-1 .9H5.2a1 1 0 0 1-1-.9z" />
    <path d="M5.8 5a2.2 2.2 0 0 1 4.4 0" />
  </Svg>
);

export const IconSun = (p: P) => (
  <Svg {...p}>
    <circle cx="8" cy="8" r="3" />
    <path d="M8 1v1.5M8 13.5V15M1 8h1.5M13.5 8H15M3 3l1 1M12 12l1 1M13 3l-1 1M4 12l-1 1" />
  </Svg>
);

export const IconMoon = (p: P) => (
  <Svg {...p}>
    <path d="M13.5 9.2A5.5 5.5 0 0 1 6.8 2.5 5.5 5.5 0 1 0 13.5 9.2Z" />
  </Svg>
);

export const IconMap = (p: P) => (
  <Svg {...p}>
    <path d="M2 4l4-1.5 4 1.5 4-1.5v9.5L10 13.5 6 12 2 13.5z" />
    <path d="M6 2.5V12M10 4v9.5" />
  </Svg>
);

export const IconGroups = (p: P) => (
  <Svg {...p}>
    <rect x="1.5" y="6" width="5.5" height="7.5" rx="1" />
    <rect x="9" y="2.5" width="5.5" height="11" rx="1" />
    <path d="M3.5 8.5h1.5M3.5 11h1.5M11 5h1.5M11 7.5h1.5M11 10h1.5" />
  </Svg>
);

export const IconBoard = (p: P) => (
  <Svg {...p}>
    <rect x="1.8" y="2.5" width="12.4" height="11" rx="1.5" />
    <path d="M1.8 6h12.4M6.5 6v7.5" />
  </Svg>
);

export const IconZone = (p: P) => (
  <Svg {...p}>
    <path d="M3 4.5 8 2.5l5 2v5.5l-5 3.5-5-3.5z" strokeDasharray="2 1.5" />
    <circle cx="8" cy="7.5" r="1.6" />
  </Svg>
);

export const IconAudit = (p: P) => (
  <Svg {...p}>
    <path d="M4 2.5h6l2.5 2.5v8.5H4z" />
    <path d="M10 2.5V5h2.5" />
    <path d="M6 8h4.5M6 10.5h3" />
  </Svg>
);

export const IconSupport = (p: P) => (
  <Svg {...p}>
    <path d="M3 9V7.5a5 5 0 0 1 10 0V9" />
    <rect x="2" y="8.5" width="2.6" height="4" rx="1" />
    <rect x="11.4" y="8.5" width="2.6" height="4" rx="1" />
    <path d="M12.7 12.5c0 1.1-1.3 1.7-3.2 1.7" />
  </Svg>
);
