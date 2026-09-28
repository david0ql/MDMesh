// Loaded by the console (NavBar) into the noVNC viewer iframe as a module. Importing noVNC's UI module again in
// the viewer's own realm returns the live instance, whose `rfb` is the open connection the soft keys send on.
// A same-origin file rather than eval: the viewer's CSP allows 'self' scripts, not 'unsafe-eval'.
import UI from '/remote/vnc/app/ui.js';

window.__dallycontrolRfb = () => UI.rfb ?? null;
