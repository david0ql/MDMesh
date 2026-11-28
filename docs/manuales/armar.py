#!/usr/bin/env python3
"""
Arma los manuales: lo llama docs/manuales/build.sh (no se ejecuta suelto).

Por cada fuente docs/manuales/src/NN-nombre.html:
  1. sustituye la marca ({{PRODUCTO}}, {{EMPRESA}}, {{EDICION}}, {{VERSION_DOC}}) con los valores de marca.env;
  2. genera la portada (<!--PORTADA-->) y el índice (<!--INDICE-->) a partir de los <meta name="doc-…"> y de las
     secciones <section class="sec …" id="…">;
  3. escribe el pie de página del documento ("EMPRESA · Título") en una hoja adicional, sin tocar la plantilla;
  4. imprime a PDF con Chrome sin interfaz (dos pasadas: la segunda ya conoce la página de cada sección y la
     escribe en el índice);
  5. revisa el resultado como pide la plantilla: pie en todas las páginas menos la portada, páginas casi vacías
     (ocupación de cada página medida sobre su imagen: menos del 35 % sin ser final de capítulo es un error),
     marcadores sin resolver, imágenes que faltan y datos sensibles (direcciones IP, correos que no son de ejemplo);
  6. publica: PDF en docs/manuales/ y copia en web/public/manuales/ con indice.json (lo lee la página Ayuda de la
     consola: título, descripción, secciones y página de cada una).

Sale con código distinto de cero si alguna revisión obligatoria falla.
"""
import html
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unicodedata

AQUI = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(AQUI, "src")
TMP = os.path.join(AQUI, ".build")
WEB = os.path.normpath(os.path.join(AQUI, "..", "..", "web", "public", "manuales"))
CHROME = os.environ.get("CHROME", "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")
MIN_OCUPACION = 0.35        # una página intermedia ocupada por debajo de esto es un defecto de maquetación
MIN_COLA = 0.12             # una última página de capítulo con menos que esto es una cola suelta: también


def marca():
    m = {}
    with open(os.path.join(AQUI, "marca.env"), encoding="utf-8") as f:
        for linea in f:
            linea = linea.strip()
            if linea and not linea.startswith("#") and "=" in linea:
                k, v = linea.split("=", 1)
                m[k.strip()] = v.strip().strip('"')
    for k in ("PRODUCTO", "EMPRESA", "EDICION", "VERSION_DOC"):
        if not m.get(k):
            sys.exit("marca.env: falta %s" % k)
    return m


def meta(fuente, nombre, obligatorio=True):
    r = re.search(r'<meta name="doc-%s" content="([^"]*)"' % re.escape(nombre), fuente)
    if not r:
        if obligatorio:
            sys.exit('falta <meta name="doc-%s"> en la fuente' % nombre)
        return ""
    return html.unescape(r.group(1))


def limpio(t):
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", "", t))).strip()


def secciones(fuente):
    """Secciones con id: (id, número, título, temas, es_capítulo)."""
    out = []
    for r in re.finditer(r'<section class="([^"]*)" id="([^"]+)"(?: data-temas="([^"]*)")?>', fuente):
        if r.group(2) == "contenido":       # la página del índice no se lista a sí misma
            continue
        resto = fuente[r.end():r.end() + 1500]
        k = re.search(r'<p class="k">(.*?)</p>', resto, re.S)
        h = re.search(r"<h([12])[^>]*>(.*?)</h\1>", resto, re.S)
        if not h:
            sys.exit("la sección #%s no tiene título" % r.group(2))
        numero = ""
        if k:
            numero = limpio(k.group(1)).split("·")[0].strip()
        out.append({"id": r.group(2), "n": numero, "titulo": limpio(h.group(2)),
                    "temas": html.unescape(r.group(3) or ""), "capitulo": h.group(1) == "1"})
    return out


def portada(m, titulo, subtitulo, publico, etiqueta):
    e = html.escape
    return """<section class="sec cover">
  <div class="title-block">
    <p class="k">%s</p>
    <h1 style="font-size:28pt;line-height:33pt;max-width:150mm">%s</h1>
    <p style="font-size:12pt;line-height:18pt;color:#5F6B7A;max-width:140mm">%s</p>
  </div>
  <div style="position:absolute;bottom:0;left:0;right:0">
    <div class="meta-row"><span>Dirigido a</span><span>%s</span></div>
    <div class="meta-row"><span>Producto</span><span>%s</span></div>
    <div class="meta-row"><span>Edición</span><span>%s</span></div>
    <div class="meta-row" style="border-bottom:0"><span>Versión del documento</span><span>%s</span></div>
  </div>
</section>""" % (e(etiqueta), e(titulo), e(subtitulo), e(publico), e(m["PRODUCTO"]),
                 e(m["EDICION"]), e(m["VERSION_DOC"]))


def indice(secs, paginas):
    filas = []
    for s in secs:
        if not s["capitulo"]:
            continue
        filas.append('<li><span class="n">%s</span><a href="#%s">%s</a><span class="p">%s</span></li>' % (
            html.escape(s["n"]), s["id"], html.escape(s["titulo"]), paginas.get(s["id"], "")))
    return '<ol class="toc%s">\n%s\n</ol>' % (" one" if len(filas) <= 9 else "", "\n".join(filas))


def anclas_ocultas(secs, todas=False):
    # Chrome solo crea un destino con nombre en el PDF para los elementos a los que apunta algún enlace: las
    # subsecciones no salen en el índice impreso, así que se enlazan aquí (texto invisible de 1 pt).
    subs = [s for s in secs if todas or not s["capitulo"]]
    if not subs:
        return ""
    return '<p style="font-size:1pt;line-height:1pt;margin:0;color:#fff">%s</p>' % "".join(
        '<a href="#%s" style="color:#fff">.</a>' % s["id"] for s in subs)


def compactar(fuente, secs, niveles):
    """Añade la clase ap1/ap2 a las secciones de los capítulos indicados (y a sus subsecciones)."""
    if not niveles:
        return fuente
    nivel = 0
    for x in secs:
        if x["capitulo"]:
            nivel = niveles.get(x["id"], 0)
        if nivel:
            fuente = re.sub(r'(<section class="[^"]*)(" id="%s")' % re.escape(x["id"]), r"\1 ap%d\2" % nivel, fuente, count=1)
    return fuente


def imprimir(html_path, pdf_path):
    perfil = tempfile.mkdtemp(prefix="manuales-chrome-")
    try:
        subprocess.run([CHROME, "--headless", "--disable-gpu", "--no-pdf-header-footer",
                        "--allow-file-access-from-files", "--user-data-dir=" + perfil,
                        "--print-to-pdf=" + pdf_path, "file://" + html_path],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=600)
    finally:
        shutil.rmtree(perfil, ignore_errors=True)
    if not os.path.exists(pdf_path) or os.path.getsize(pdf_path) < 1000:
        sys.exit("Chrome no generó %s" % pdf_path)


def destinos(pdf):
    out = subprocess.run(["pdfinfo", "-dests", pdf], check=True, capture_output=True, text=True).stdout
    d = {}
    for linea in out.splitlines():
        r = re.match(r'\s*(\d+)\s+\[.*\]\s+"(.*)"\s*$', linea)
        if r:
            d[r.group(2)] = int(r.group(1))
    return d


def n_paginas(pdf):
    out = subprocess.run(["pdfinfo", pdf], check=True, capture_output=True, text=True).stdout
    return int(re.search(r"Pages:\s+(\d+)", out).group(1))


def texto_paginas(pdf):
    out = subprocess.run(["pdftotext", "-layout", pdf, "-"], check=True, capture_output=True, text=True).stdout
    return out.split("\f")


def imagenes_por_pagina(pdf):
    out = subprocess.run(["pdfimages", "-list", pdf], capture_output=True, text=True).stdout
    pags = {}
    for linea in out.splitlines()[2:]:
        c = linea.split()
        if c and c[0].isdigit():
            pags[int(c[0])] = pags.get(int(c[0]), 0) + 1
    return pags


def ocupacion(pdf):
    """Fracción ocupada de cada página: hasta dónde llega el contenido (texto, tablas o figuras) dentro del área
    útil, medido sobre la página dibujada en gris a 24 ppp. No depende de cuánto texto haya: una captura cuenta."""
    tmp = tempfile.mkdtemp(prefix="manuales-pgm-")
    try:
        subprocess.run(["pdftoppm", "-gray", "-r", "24", pdf, os.path.join(tmp, "p")], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        out = []
        for f in sorted(os.listdir(tmp)):
            with open(os.path.join(tmp, f), "rb") as fh:
                data = fh.read()
            partes = data.split(None, 4)          # P5, ancho, alto, máximo, píxeles
            w, h = int(partes[1]), int(partes[2])
            px = partes[4][-w * h:]
            arriba, abajo = int(h * 0.105), int(h * 0.925)      # bajo el encabezado y sobre el pie
            ultima = arriba
            for y in range(abajo - 1, arriba, -1):
                if min(px[y * w + int(w * 0.08): y * w + int(w * 0.92)]) < 200:
                    ultima = y
                    break
            out.append((ultima - arriba) / float(abajo - arriba))
        return out
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def sin_tildes(t):
    return "".join(c for c in unicodedata.normalize("NFD", t) if unicodedata.category(c) != "Mn").lower()


def revisar(nombre, pdf, fuente_final, pie, con_portada=True, inicios=()):
    errores, avisos = [], []
    total = n_paginas(pdf)
    pags = texto_paginas(pdf)[:total]
    llenos = ocupacion(pdf)
    for i, t in enumerate(pags, start=1):
        if i == 1 and con_portada:
            if re.search(r"Página \d+ de \d+", t):
                errores.append("la portada lleva pie")
            continue
        if not re.search(r"Página %d de %d" % (i, total), t):
            errores.append("página %d sin numeración" % i)
        if pie not in re.sub(r"\s+", " ", t):
            errores.append("página %d sin el pie «%s»" % (i, pie))
        cuerpo = re.sub(r"\s+", " ", t).replace(pie, "").strip()
        lleno = llenos[i - 1] if i - 1 < len(llenos) else 1.0
        fin_capitulo = i == total or (i + 1) in inicios
        if lleno < MIN_OCUPACION and not fin_capitulo:
            errores.append("página %d ocupada al %d %% sin ser final de capítulo: %s…" % (i, lleno * 100, cuerpo[:80]))
        elif lleno < MIN_COLA and fin_capitulo:
            errores.append("página %d: cola de capítulo de solo %d %% (reacomodar): %s…" % (i, lleno * 100, cuerpo[:80]))
        elif lleno < MIN_OCUPACION:
            avisos.append("página %d: final de capítulo ocupado al %d %%" % (i, lleno * 100))
    todo = "\n".join(pags)
    if re.search(r"\{\{[A-Z_]+\}\}", fuente_final + todo):
        errores.append("quedan marcadores {{…}} sin resolver")
    for ip in set(re.findall(r"\b(?:\d{1,3}\.){3}\d{1,3}\b", todo)):
        errores.append("dirección IP en el texto: %s" % ip)
    for correo in set(re.findall(r"[\w.+-]+@[\w-]+(?:\.[\w-]+)+", todo)):
        if not re.search(r"@(ejemplo\.com|example\.com)$", correo):
            errores.append("correo que no es de ejemplo: %s" % correo)
    for src in re.findall(r'<img src="([^"]+)"', fuente_final):
        if not os.path.exists(os.path.normpath(os.path.join(TMP, src))):
            errores.append("falta la imagen %s" % src)
    return total, errores, avisos


def main():
    m = marca()
    solo = sys.argv[1:]
    os.makedirs(TMP, exist_ok=True)
    os.makedirs(WEB, exist_ok=True)
    enlace = os.path.join(TMP, "img")
    if not os.path.islink(enlace):
        os.symlink(os.path.join("..", "src", "img"), enlace)
    shutil.copy(os.path.join(SRC, "manual.css"), os.path.join(TMP, "manual.css"))

    fuentes = sorted(f for f in os.listdir(SRC) if re.match(r"\d\d-.*\.html$", f))
    catalogo, fallo = [], False
    for f in fuentes:
        nombre = f[:-5]
        with open(os.path.join(SRC, f), encoding="utf-8") as fh:
            fuente = fh.read()
        for k, v in m.items():
            fuente = fuente.replace("{{%s}}" % k, html.escape(v))
        # Las figuras se numeran solas, en orden de aparición: insertar una no obliga a renumerar el resto.
        cuenta = [0]
        def _fig(m_):
            cuenta[0] += 1
            return '<p class="cap">Figura %d.' % cuenta[0]
        fuente = re.sub(r'<p class="cap">Figura \d+\.', _fig, fuente)
        titulo = meta(fuente, "titulo")
        pie = "%s · %s" % (m["EMPRESA"], titulo)
        secs = secciones(fuente)
        pdf = os.path.join(AQUI, nombre + ".pdf")
        if solo and nombre not in solo and f not in solo:
            if os.path.exists(pdf):
                catalogo.append(entrada(nombre, fuente, secs, pdf, destinos(pdf), m))
            continue

        with open(os.path.join(TMP, "pie-%s.css" % nombre), "w", encoding="utf-8") as fh:
            fh.write('@page { @bottom-left { content: "%s"; } }\n' % pie.replace('"', '\\"'))
        cabeza = ('<link rel="stylesheet" href="../_plantilla/plantilla.css">\n'
                  '<link rel="stylesheet" href="manual.css">\n'
                  '<link rel="stylesheet" href="pie-%s.css">' % nombre)
        base = fuente.replace("<!--ESTILOS-->", cabeza).replace(
            "<!--PORTADA-->", portada(m, titulo, meta(fuente, "subtitulo"), meta(fuente, "publico"),
                                      meta(fuente, "etiqueta")))
        salida_html = os.path.join(TMP, nombre + ".html")
        paginas, final = {}, base
        # Cada capítulo empieza en página nueva, así que su maquetación no depende de los demás. Se arma el documento
        # con tres niveles de compactación (0 = normal, 1 y 2 = figuras y espacios algo menores) y para cada capítulo
        # se elige el que ocupa menos páginas; a igualdad, el normal si su última página queda bien ocupada y, si no,
        # el que deja la última página más llena. Así una cola de pocas líneas vuelve a la página anterior.
        caps = [x for x in secs if x["capitulo"]]

        def armar_con(niveles):
            pags, final_ = {}, base
            for _ in range(3):      # la pasada siguiente escribe en el índice las páginas que midió la anterior
                final_ = compactar(base, secs, niveles).replace("<!--INDICE-->", indice(secs, pags) + anclas_ocultas(secs))
                if "<!--INDICE-->" not in base:
                    final_ = final_.replace("</body>", anclas_ocultas(secs, True) + "</body>")
                with open(salida_html, "w", encoding="utf-8") as fh:
                    fh.write(final_)
                imprimir(salida_html, pdf)
                nuevas = destinos(pdf)
                if nuevas == pags:
                    break
                pags = nuevas
            return pags, final_

        def por_capitulo(pags):
            llenos = ocupacion(pdf)
            out = {}
            for k, c in enumerate(caps):
                ini = pags.get(c["id"])
                fin = (pags.get(caps[k + 1]["id"]) - 1) if k + 1 < len(caps) and pags.get(caps[k + 1]["id"]) else len(llenos)
                if ini:
                    out[c["id"]] = (fin - ini + 1, llenos[fin - 1])
            return out

        medidas = {}
        for nivel in (0, 1, 2):
            pags_n, _f = armar_con({c["id"]: nivel for c in caps} if nivel else {})
            medidas[nivel] = por_capitulo(pags_n)
        apretar = {}
        for c in caps:
            opciones = [(medidas[n_][c["id"]][0], 0 if (n_ == 0 and medidas[0][c["id"]][1] >= MIN_OCUPACION) else 1,
                         -medidas[n_][c["id"]][1], n_) for n_ in (0, 1, 2) if c["id"] in medidas[n_]]
            if opciones:
                mejor = min(opciones)[3]
                if mejor:
                    apretar[c["id"]] = mejor
        paginas, final = armar_con(apretar)
        faltan = [s["id"] for s in secs if s["id"] not in paginas]
        inicios = set(paginas.get(x["id"]) for x in secs if x["capitulo"])
        total, errores, avisos = revisar(nombre, pdf, final, pie, "<!--PORTADA-->" in fuente, inicios)
        if faltan:
            errores.append("secciones sin destino en el PDF: %s" % ", ".join(faltan))
        print("%-34s %3d páginas  %5.1f MB  pie: «%s»" % (nombre + ".pdf", total, os.path.getsize(pdf) / 1e6, pie))
        for a in avisos:
            print("    AVISO  " + a)
        for e in errores:
            print("    ERROR  " + e)
            fallo = True
        catalogo.append(entrada(nombre, fuente, secs, pdf, paginas, m))

    # Publicación para la página Ayuda de la consola (archivos estáticos; no entran en el bundle).
    for viejo in os.listdir(WEB):
        if viejo.endswith(".pdf") and viejo[:-4] not in [c["id"] for c in catalogo]:
            os.remove(os.path.join(WEB, viejo))
    for c in catalogo:
        shutil.copy(os.path.join(AQUI, c["archivo"]), os.path.join(WEB, c["archivo"]))
    with open(os.path.join(WEB, "indice.json"), "w", encoding="utf-8") as fh:
        json.dump({"producto": m["PRODUCTO"], "empresa": m["EMPRESA"], "edicion": m["EDICION"],
                   "version": m["VERSION_DOC"], "manuales": catalogo}, fh, ensure_ascii=False, indent=1)
        fh.write("\n")
    print("Publicados %d manuales en %s" % (len(catalogo), os.path.relpath(WEB, os.path.join(AQUI, "..", ".."))))
    sys.exit(1 if fallo else 0)


def entrada(nombre, fuente, secs, pdf, paginas, m):
    return {
        "id": nombre,
        "archivo": nombre + ".pdf",
        "titulo": meta(fuente, "titulo"),
        "descripcion": meta(fuente, "subtitulo"),
        "publico": meta(fuente, "publico"),
        "paginas": n_paginas(pdf),
        "kb": round(os.path.getsize(pdf) / 1024),
        "secciones": [{"id": s["id"], "titulo": s["titulo"], "pagina": paginas.get(s["id"], 1),
                       "temas": s["temas"]} for s in secs],
    }


if __name__ == "__main__":
    main()
