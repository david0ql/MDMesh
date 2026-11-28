# Manuales: qué falta completar

Los manuales (`docs/manuales/src/*.html` → PDF con `docs/manuales/build.sh`) documentan lo que está en
`integration/mobicontrol`: enrolamiento, conectividad, quiosco reforzado, permisos por app, hardware y puertos, Wi-Fi
corporativo, soporte remoto con voz, FRP / modo perdido / borrado, datos móviles, auditoría, operación. La única
función con la marca **«Llega con…»** (clase `pend`) es el **despertador FCM**, aún sin fusionar.

## Cómo regenerar

```bash
docker compose --env-file docker/dev.env up -d --build && scripts/dev-seed.sh
scripts/manual-demo-data.sh                    # flota de demostración (datos ficticios); --refrescar si pasaron 15 min
(cd scripts/shots && npm ci)                   # una vez
node scripts/manual-capturas.mjs --web http://localhost:8088 --apk <un .apk pequeño de prueba>
scripts/manual-demo-data.sh --refrescar        # le pone nombre a la app subida; repetir capturas con --solo a13,a15,p04
docs/manuales/build.sh                         # PDF + copia e índice para la página Ayuda
node scripts/ayuda-console-e2e.mjs --web http://localhost:8088    # centro de ayuda
node scripts/ayuda-pdf-check.mjs --web http://localhost:8088      # los PDF abren y descargan tras el borde (Chrome)
```

- La marca (producto, empresa, edición) está **solo** en `docs/manuales/marca.env`; el logo, en `_plantilla/logo.jpeg`.
- Las capturas se toman como `https://mdm.ejemplo.com` (`--como`): el script responde esa dirección con el stack local.
- `build.sh` numera las figuras, elige por capítulo el nivel de compactación que evita colas sueltas y **falla** si
  una página intermedia queda por debajo del 35 % o una última página de capítulo por debajo del 12 %.
- `build.sh` copia los PDF y `indice.json` a `web/public/manuales/` (menú **Ayuda**): confirmar ambos lugares juntos.
- Si se renombra el `id` de una sección, revisar `web/src/data/help.ts` (mapa pantalla → sección del «?»).

## Al fusionar el despertador FCM (`worktree-agent-aa96983bce1627e55`, MDM-07)

| Documento | Qué completar |
|---|---|
| Arquitectura §2 «Despertador» y §4 | Flujo real; salida del servidor a los servicios de mensajería de Google; **qué necesita el teléfono hacia Google** (no está enumerado en el documento de la rama: pedirlo). |
| Operación §2 | Variables `FCM_*`, pasos del proyecto de mensajería, comprobación en Ajustes. |
| Administrador §19 y §6 | «Despertador push (FCM)» en Ajustes; tarjeta «Alcance de órdenes» en la ficha; «Sondeo de respaldo» en la política. Capturas de ambas. |
| Problemas §4 | Estados de «Mensajes push» y exención de batería por fabricante. |
| Notas de versión §3 | Pasar «Por integrar: despertador» a la fila de MDM-07, con sus mediciones (la sección de mediciones de su documento estaba vacía). |

## Capturas que no se pudieron tomar (necesitan un agente real reportando)

El stack de demostración usa una flota sintética sin agentes. Estas pantallas solo muestran contenido cuando un
teléfono (o emulador) con el agente de esta versión reporta; se describen en texto y **no se inventó la imagen**:

- Pestaña **Remoto** con «Remoto listo» y una **sesión de soporte** en curso (pantalla, chat, llamada, archivos), y
  el menú **Sesiones de soporte** con sesiones. Una sesión viva exige un agente conectado por WebSocket.
- Pestaña **Permisos** del dispositivo con el estado real por permiso (sin reporte dice «Sin reporte»).
- Tarjeta **Wi‑Fi gestionado** y aviso de **refuerzo del quiosco** en la ficha.
- Diálogo **Modo perdido** (el botón está deshabilitado en un equipo sin agente reciente).
- **Zero‑Touch / KME**: la consola de desarrollo muestra el aviso «agente de pruebas»; tomarla con una consola
  compilada con el paquete de producción.

Para tomarlas: enrolar un emulador en el stack (`scripts/adb-enroll.sh`), agregar los pasos a
`scripts/manual-capturas.mjs` y poner las figuras en Administrador §9, §10, §11, §14 y §15.

## Verificaciones pendientes

- **Teléfono físico.** Nada de lo documentado se probó en teléfono físico. La lista por tema está en «Notas de
  versión» §4; el protocolo de 10 minutos por modelo, en el anexo de la «Guía de enrolamiento». Al tener resultados,
  actualizar la tabla de Enrolamiento §10 y las Notas.
- **QR en Android 11 a 17 y Play Protect.** Sin corrida válida en emulador; Play Protect sin probar en ninguna
  versión. Consola (pestaña «Por cable (USB)») y manuales dicen ahora lo mismo: en Android 15 y 16 el QR «puede
  requerir» el cable. Cambiar ambos textos cuando haya evidencia.
- **Soporte remoto.** En emulador no pasó «escribir texto a través de la sesión» (causa sin confirmar) y no se probó
  en Android 7 a 10 ni en quiosco. El manual lo dice en la leyenda del capítulo 15.
- **Depuración USB, dos controles.** La política tiene «Depuración USB y opciones de desarrollador» (Funciones del
  dispositivo) y «Depuración USB (ADB)» (Hardware y puertos). El manual recomienda usar uno; la precedencia exacta
  entre ambos no se confirmó en código.
- **Herramienta «Por cable (USB)».** `/files/DallyControl-Enrolar.zip` no se genera desde el repositorio: lo publica
  el operador. La guía lo dice.
- **Nombres de los criterios MDM-01…MDM-17.** Las notas usan un título corto por tema; reemplazarlos por el texto
  literal de la matriz del cliente si se quiere citar.
- **SLA.** Todas las marcas `[POR CONFIRMAR]` siguen abiertas (decisión de gerencia). Datos de guardia: Operación §9.
- **Dimensionamiento.** Disco, ancho de banda y consumo por equipo: «pendiente de medir» (Arquitectura §6).
- **Agente en teléfonos configurados en inglés.** Los rótulos nuevos tienen español en `values-es` e inglés por
  defecto, como el resto de la app: un teléfono en inglés los muestra en inglés. Los detalles técnicos que el agente
  devuelve en el resultado de una orden («invalid payload», etc.) siguen en inglés: el servidor compara esos textos.
- **Datos de demostración.** Eventos, alertas, recorridos y la falla de conexión del equipo de ejemplo se insertan por
  SQL (`scripts/manual-demo-data.sh`); las IP del stack local se tapan en las capturas.
