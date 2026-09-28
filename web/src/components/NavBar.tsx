import type { ReactElement, RefObject } from 'react';

// X11 keysyms droidVNC-NG maps to Android system actions (its default chords, pinned by the agent through
// managed restrictions — DroidVncController.restrictions()).
const K = {
  escape: 0xff1b, home: 0xff50, end: 0xff57, pageUp: 0xff55, pageDown: 0xff56, del: 0xffff,
  ctrl: 0xffe3, shift: 0xffe1, alt: 0xffe9,
};

interface Action { key: string; label: string; keys: number[]; icon: ReactElement }

const icon = (d: ReactElement) => (
  <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true" fill="none" stroke="currentColor"
       strokeWidth="2" strokeLinejoin="round" strokeLinecap="round">{d}</svg>
);

/** The Nexus-style soft keys: Back ◁, Home ○, Recents □. */
const PRIMARY: Action[] = [
  { key: 'back', label: 'Back', keys: [K.escape], icon: icon(<path d="M16 5 7 12l9 7z" />) },
  { key: 'home', label: 'Home', keys: [K.home], icon: icon(<circle cx="12" cy="12" r="7" />) },
  { key: 'recents', label: 'Recent apps', keys: [K.ctrl, K.shift, K.escape], icon: icon(<rect x="6" y="6" width="12" height="12" rx="1.5" />) },
];

const SECONDARY: Action[] = [
  { key: 'power', label: 'Power menu', keys: [K.end], icon: icon(<><path d="M12 3v8" /><path d="M6.3 7.3a8 8 0 1 0 11.4 0" /></>) },
  { key: 'vol-down', label: 'Volume down', keys: [K.ctrl, K.alt, K.pageDown], icon: icon(<><path d="M4 9v6h4l5 4V5L8 9z" /><path d="M17 12h4" /></>) },
  { key: 'vol-up', label: 'Volume up', keys: [K.ctrl, K.alt, K.pageUp], icon: icon(<><path d="M4 9v6h4l5 4V5L8 9z" /><path d="M17 12h4M19 10v4" /></>) },
  { key: 'rotate', label: 'Rotate screen', keys: [K.ctrl, K.alt, K.del], icon: icon(<><path d="M20 12a8 8 0 1 1-2.3-5.7" /><path d="M20 4v4h-4" /></>) },
];

interface Rfb { sendKey(keysym: number, code: string | null, down?: boolean): void }

type ViewerWindow = Window & { __dallycontrolRfb?: () => Rfb | null };

/**
 * The live noVNC connection inside the viewer iframe (same origin). noVNC keeps it in its UI module;
 * /remote-keys.js, added to the viewer once as a module script, re-imports that module in the iframe's realm and
 * exposes it. (Not eval: the viewer's CSP has no 'unsafe-eval', which silently broke the soft keys.)
 */
async function rfbOf(frame: HTMLIFrameElement | null): Promise<Rfb | null> {
  const win = frame?.contentWindow as ViewerWindow | null;
  const doc = frame?.contentDocument;
  if (!win || !doc) return null;
  if (!win.__dallycontrolRfb) {
    const loaded = await new Promise<boolean>((resolve) => {
      const s = doc.createElement('script');
      s.type = 'module';
      s.src = '/remote-keys.js';
      s.onload = () => resolve(true);
      s.onerror = () => resolve(false);
      doc.head.appendChild(s);
    });
    if (!loaded) return null;
  }
  try {
    return win.__dallycontrolRfb?.() ?? null;
  } catch {
    return null;
  }
}

/** Press a chord: modifiers down in order, the last key down + up, modifiers up in reverse. */
async function press(frame: HTMLIFrameElement | null, keys: number[]): Promise<boolean> {
  const rfb = await rfbOf(frame);
  if (!rfb) return false;
  keys.forEach((k) => rfb.sendKey(k, null, true));
  [...keys].reverse().forEach((k) => rfb.sendKey(k, null, false));
  return true;
}

export function NavBar({ frame, onUnavailable }: {
  frame: RefObject<HTMLIFrameElement | null>;
  onUnavailable: () => void;
}) {
  const run = (a: Action) => {
    void press(frame.current, a.keys).then((ok) => { if (!ok) onUnavailable(); });
  };
  return (
    <div className="rp-nav" role="toolbar" aria-label="Device buttons">
      <div className="rp-nav-side">
        {SECONDARY.map((a) => (
          <button key={a.key} type="button" className="rp-nav-btn small" title={a.label} aria-label={a.label}
                  onClick={() => run(a)}>{a.icon}</button>
        ))}
      </div>
      <div className="rp-nav-main">
        {PRIMARY.map((a) => (
          <button key={a.key} type="button" className="rp-nav-btn" title={a.label} aria-label={a.label}
                  onClick={() => run(a)}>{a.icon}</button>
        ))}
      </div>
      <div className="rp-nav-side" />
    </div>
  );
}
