import { useEffect, useRef } from 'react';
import QRCode from 'qrcode';

/** Renders [text] as a QR code on a canvas (client-side; no server round-trip). */
export function QrCanvas({ text, size = 320 }: { text: string; size?: number }) {
  const ref = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    const c = ref.current;
    if (!c) return;
    void QRCode.toCanvas(c, text, {
      width: size,
      margin: 2,
      errorCorrectionLevel: 'M',
    })
      .then(() => {
        // qrcode sets an inline width/height in px on the canvas, which overrides the stylesheet and
        // overflows narrow containers. Re-set it responsively (inline wins) so it always stays contained.
        c.style.width = '100%';
        c.style.height = 'auto';
        c.style.maxWidth = `${size}px`;
        c.style.display = 'block';
      })
      .catch(() => undefined);
  }, [text, size]);
  return <canvas ref={ref} width={size} height={size} style={{ maxWidth: '100%', height: 'auto', display: 'block' }} />;
}

/**
 * Save [text] as a print-ready PNG: a large QR with the [caption] lines under it (folder, code, expiry…), so a QR sent
 * by chat or printed still says what it enrolls into.
 */
export async function downloadQrPng(text: string, fileName: string, caption: string[] = []): Promise<void> {
  const qr = document.createElement('canvas');
  await QRCode.toCanvas(qr, text, { width: 900, margin: 4, errorCorrectionLevel: 'M' });
  const lines = caption.map((l) => l.trim()).filter(Boolean);
  const lineH = 44;
  const out = document.createElement('canvas');
  out.width = qr.width;
  out.height = qr.height + (lines.length ? lines.length * lineH + 40 : 0);
  const g = out.getContext('2d');
  if (!g) return;
  g.fillStyle = '#ffffff';
  g.fillRect(0, 0, out.width, out.height);
  g.drawImage(qr, 0, 0);
  g.fillStyle = '#111111';
  g.textAlign = 'center';
  lines.forEach((l, i) => {
    g.font = i === 0 ? '600 34px system-ui, sans-serif' : '28px system-ui, sans-serif';
    g.fillText(l, out.width / 2, qr.height + 10 + (i + 1) * lineH - 12, out.width - 60);
  });
  const a = document.createElement('a');
  a.href = out.toDataURL('image/png');
  a.download = fileName.replace(/[^A-Za-z0-9._-]+/g, '_');
  document.body.appendChild(a);
  a.click();
  a.remove();
}
