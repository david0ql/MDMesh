/**
 * Escape text for insertion into an HTML string (Leaflet popups/tooltips take HTML). Anything a device reports —
 * names, providers, labels, details — is untrusted and must go through this before it reaches markup.
 */
export function escapeHtml(value: unknown): string {
  return String(value ?? '').replace(/[&<>"'`]/g, (c) => `&#${c.charCodeAt(0)};`);
}
