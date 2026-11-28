#!/usr/bin/env bash
# Regenera todos los manuales en PDF a partir de docs/manuales/src/*.html.
#
#   docs/manuales/build.sh                       # todos
#   docs/manuales/build.sh 04-solucion-problemas # solo uno (nombre de la fuente, sin .html)
#
# Salida: docs/manuales/NN-nombre.pdf, y una copia con su índice en web/public/manuales/ (lo que sirve la página
# «Ayuda» de la consola). La marca (producto, empresa, edición) se define SOLO en docs/manuales/marca.env.
# Requisitos: macOS con Google Chrome, python3 y Poppler (pdfinfo, pdftotext, pdftoppm: `brew install poppler`).
# Falla (código 1) si un PDF queda sin pie o numeración, con marcadores o imágenes sin resolver, con datos sensibles
# o con una página casi vacía: menos del 35 % ocupada sin ser final de capítulo, o una cola de capítulo de menos
# del 12 %. La ocupación se mide sobre la imagen de cada página (cuenta texto, tablas y capturas).
# Las capturas de pantalla se regeneran aparte: scripts/manual-capturas.mjs.
set -euo pipefail
AQUI="$(CDPATH='' cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
[ -x "$CHROME" ] || { echo "No se encontró Google Chrome en: $CHROME (defina CHROME=/ruta)" >&2; exit 1; }
for h in python3 pdfinfo pdftotext pdftoppm; do
  command -v "$h" >/dev/null || { echo "Falta la herramienta '$h' (brew install poppler)" >&2; exit 1; }
done
CHROME="$CHROME" python3 "$AQUI/armar.py" "$@"
