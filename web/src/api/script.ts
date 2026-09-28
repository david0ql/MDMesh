import type { QueueCommandRequest } from './commands';

// Device scripts (MobiControl-style "scripts"): one action per line, run in order.
//   open co.amovil.preventa        bring an app to the front
//   ring 20                        ring for N seconds (default 30)
//   stop ring
//   message Reunión a las 3pm      popup on the phone
//   lockscreen Propiedad de Amovil lock-screen message
//   lock | reboot
//   location accurate|battery      location mode
//   wait 10                        pause N seconds before the next line (while the console is open)
// Lines starting with # are comments.

export interface ScriptStep {
  line: number;
  text: string;
  request?: QueueCommandRequest;
  waitMs?: number;
}

export interface ParsedScript {
  steps: ScriptStep[];
  errors: string[];
}

const PKG = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$/;
const cmd = (type: string, payload?: unknown): QueueCommandRequest => ({
  type, requiresCapability: type, ...(payload === undefined ? {} : { payload: JSON.stringify(payload) }),
});

export function parseScript(src: string): ParsedScript {
  const steps: ScriptStep[] = [];
  const errors: string[] = [];
  src.split('\n').forEach((raw, i) => {
    const text = raw.trim();
    if (!text || text.startsWith('#')) return;
    const line = i + 1;
    const [verb0, ...rest] = text.split(/\s+/);
    const verb = verb0.toLowerCase();
    const arg = rest.join(' ');
    const bad = (why: string) => errors.push(`Line ${line}: ${why}`);
    switch (verb) {
      case 'open':
        if (!PKG.test(arg)) bad('open needs a package name, e.g. open co.amovil.preventa');
        else steps.push({ line, text, request: cmd('device.appLaunch', { packageName: arg }) });
        break;
      case 'ring': {
        const secs = arg ? Number(arg) : 30;
        if (!Number.isFinite(secs) || secs <= 0 || secs > 600) bad('ring takes seconds between 1 and 600');
        else steps.push({ line, text, request: cmd('device.ring', { durationMs: Math.round(secs * 1000) }) });
        break;
      }
      case 'stop':
        if (arg.toLowerCase() !== 'ring') bad('did you mean "stop ring"?');
        else steps.push({ line, text, request: cmd('device.ringStop') });
        break;
      case 'message':
        if (!arg) bad('message needs a text');
        else steps.push({ line, text, request: cmd('device.alert', { body: arg }) });
        break;
      case 'lockscreen':
        steps.push({ line, text, request: cmd('device.lockscreenMessage', { message: arg }) });
        break;
      case 'lock':
        steps.push({ line, text, request: cmd('device.lock') });
        break;
      case 'reboot':
        steps.push({ line, text, request: cmd('device.reboot') });
        break;
      case 'location': {
        const m = arg.toLowerCase();
        if (m !== 'accurate' && m !== 'battery') bad('location accurate | battery');
        else steps.push({ line, text, request: cmd('device.locationMode', { mode: m === 'accurate' ? 'active' : 'passive' }) });
        break;
      }
      case 'wait': {
        const secs = Number(arg);
        if (!Number.isFinite(secs) || secs <= 0 || secs > 3600) bad('wait takes seconds between 1 and 3600');
        else steps.push({ line, text, waitMs: Math.round(secs * 1000) });
        break;
      }
      default:
        bad(`unknown action "${verb0}"`);
    }
  });
  return { steps, errors };
}
