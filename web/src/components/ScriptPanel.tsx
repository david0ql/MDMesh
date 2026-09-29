import { useState } from 'react';
import { useToast } from '../ui/toast';
import { queueCommand } from '../api/commands';
import { parseScript } from '../api/script';

const EXAMPLE = `# Una acción por línea
open co.amovil.preventa
ring 15
message Por favor comunícate con soporte`;

/** Run a small script of actions on the device, in order (see api/script.ts for the verbs). */
export function ScriptPanel({ device, onQueued }: { device: { number: string }; onQueued: () => void }) {
  const toast = useToast();
  const [src, setSrc] = useState('');
  const [running, setRunning] = useState<string | null>(null);
  const parsed = parseScript(src);

  async function run() {
    if (parsed.errors.length || parsed.steps.length === 0) return;
    let queued = 0;
    try {
      for (const s of parsed.steps) {
        setRunning(s.text);
        if (s.waitMs) await new Promise((r) => setTimeout(r, s.waitMs));
        else if (s.request) { await queueCommand(device.number, s.request); queued++; onQueued(); }
      }
      toast.push('ok', 'Script enviado', `${queued} ${queued === 1 ? 'acción enviada' : 'acciones enviadas'} al dispositivo, en orden.`);
    } catch (e) {
      toast.push('err', `El script se detuvo en “${running ?? ''}”`, e instanceof Error ? e.message : '');
    } finally {
      setRunning(null);
    }
  }

  return (
    <section className="action-group" data-testid="script-panel">
      <h3 className="action-group-title">Script</h3>
      <textarea
        className="dcp-list mono"
        rows={5}
        placeholder={EXAMPLE}
        value={src}
        aria-label="Script del dispositivo"
        onChange={(e) => setSrc(e.target.value)}
      />
      <p className="note" style={{ margin: '6px 0' }}>
        <span className="mono">open &lt;paquete&gt;</span> · <span className="mono">ring [s]</span> · <span className="mono">stop ring</span> ·{' '}
        <span className="mono">message &lt;texto&gt;</span> · <span className="mono">lockscreen &lt;texto&gt;</span> · <span className="mono">lock</span> ·{' '}
        <span className="mono">reboot</span> · <span className="mono">location accurate|battery</span> · <span className="mono">wait &lt;s&gt;</span>
      </p>
      {parsed.errors.length > 0 && src.trim() && (
        <div className="banner banner-alert">{parsed.errors.map((e) => <div key={e}>{e}</div>)}</div>
      )}
      <button className="btn" disabled={!!running || parsed.errors.length > 0 || parsed.steps.length === 0} onClick={() => void run()}>
        {running
          ? <span key="running">{`Ejecutando: ${running}`}</span>
          : <span key="run">{`Ejecutar script (${parsed.steps.length} ${parsed.steps.length === 1 ? 'paso' : 'pasos'})`}</span>}
      </button>
    </section>
  );
}
