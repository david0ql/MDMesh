# Soporte remoto: ver y controlar la pantalla, llamada de voz, chat y archivos

Criterio MDM-08: *visualizar y controlar la pantalla del celular en tiempo real desde la consola para soporte
técnico*. Este documento dice qué hace, qué necesita, qué límites pone Android por versión y fabricante, y cómo
probarlo. La decisión de diseño está en [ADR 0012](adr/0012-remote-support-in-agent.md); el camino anterior con
droidVNC-NG ([ADR 0010](adr/0010-remote-control-droidvnc.md)) sigue disponible como respaldo.

## Qué ofrece

Todo ocurre dentro de una **sesión de soporte** que el técnico abre desde la pestaña **Remoto** del dispositivo. El
agente del teléfono lo hace todo: no hay que instalar otra app.

| Función | Detalle |
|---------|---------|
| Ver la pantalla | Imagen en vivo del teléfono; calidad Baja / Media / Alta, escala Ajustar o Tamaño real, pantalla completa. |
| Controlar | Clic = toque, clic sostenido = toque largo, arrastrar = deslizar, rueda = desplazar. Teclado físico: lo que se escribe (y lo que se pega) entra en el campo enfocado del teléfono; Retroceso, Enter y Esc (= Atrás). Barra: Atrás, Inicio, Recientes, Notificaciones, Encender pantalla, Bloquear, Reiniciar. |
| Llamada de voz | Botón **Llamar**: audio en los dos sentidos entre el navegador del técnico y el teléfono (altavoz). En el teléfono: *Soporte quiere hablar contigo* con **Aceptar / Rechazar**, y durante la llamada **Silenciar / Colgar** (también desde la notificación). En la consola: Silenciar y Colgar. |
| Chat | Mensajes del técnico aparecen en pantalla en el teléfono (abre *Soporte técnico*, también en quiosco y con el teléfono bloqueado); la persona responde ahí mismo. |
| Aviso | *Soporte está viendo tu pantalla*: una etiqueta roja sobre cualquier app y una notificación fija mientras dura la sesión. Se puede apagar por política. |
| Archivos | **Enviar archivo** al teléfono (queda en *Descargas/Soporte*) y **Explorar el teléfono** para descargar archivos a la consola. |
| Portapapeles | Enviar texto al portapapeles del teléfono y **traer el portapapeles del teléfono** (lo que la persona copió allí), con cualquier app en primer plano. |
| Captura y grabación | **Captura** descarga la imagen actual (PNG); **Grabar** guarda la sesión en video (WebM) en el equipo del técnico. |
| Registro | Cada sesión queda registrada: quién, qué dispositivo, cuándo, cuánto duró, si fue solo ver, y qué se usó (mensajes, archivos, segundos de voz, toques). **Historial de sesiones** en la pestaña Remoto y página **Sesiones de soporte** para toda la flota (con descarga CSV). Un administrador de carpeta solo ve las de sus dispositivos. |

## Requisitos

- **Agente** con soporte remoto integrado (la versión siguiente a la 0.9.8). Los agentes anteriores siguen
  funcionando con droidVNC-NG, sin voz ni chat.
- **Android 7 o superior** para ver y controlar. Android 6 no tiene control remoto.
- El teléfono debe ser **Device Owner** (cualquier forma de inscripción de DallyControl lo deja así).
- **Servidor**: nada nuevo. La sesión viaja por el mismo dominio y puerto HTTPS (443) que el resto de la consola,
  también por el túnel de Cloudflare. No se abre ningún puerto, no hace falta servidor STUN/TURN.
- **Navegador del técnico**: Chrome o Edge recientes (también Firefox y Safari 16.4+). Para la llamada el navegador
  pide permiso de micrófono la primera vez; la consola debe abrirse por HTTPS.
- **Ancho de banda**: la pantalla usa entre 0 (pantalla quieta) y ~1 Mbit/s; la voz, 128 kbit/s por sentido.

## La autorización, una sola vez

Android no deja que ninguna app (tampoco un Device Owner) active por sí sola un servicio de accesibilidad, que es lo
que permite ver y tocar la pantalla sin que nadie acepte cada sesión. Por eso:

| Cómo se inscribió el teléfono | Qué hay que hacer | Sesiones después |
|-------------------------------|-------------------|------------------|
| **Por cable o Wi-Fi con el inscriptor** (USB / adb) | Nada. El inscriptor le da al agente el permiso para activarse solo. | Desatendidas: nadie toca el teléfono. |
| **Por QR o Zero-Touch** | **Un toque, una vez**, al terminar la inscripción: el agente muestra la pantalla *Activa el soporte remoto* → **Abrir ajustes** → *Soporte remoto DallyControl* → activar el interruptor → **Permitir**. La pantalla se cierra sola. | Desatendidas: nadie toca el teléfono. |

La consola lo dice en la pestaña Remoto:

- **Remoto listo**: se puede ver y controlar sin nadie en el teléfono.
- **Falta autorización**: falta ese toque. El botón **Pedir autorización en el teléfono** vuelve a mostrar la
  pantalla guiada (útil para teléfonos que ya estaban en campo). Mientras tanto la sesión ya ofrece **chat, llamada
  de voz y archivos**, y la pantalla por droidVNC-NG si está instalado.
- **Remoto apagado por la política**: la política eligió «Solo droidVNC-NG».

La autorización se conserva al reiniciar el teléfono y al actualizar el agente. Se pierde si alguien la apaga en
Ajustes > Accesibilidad (la consola vuelve a decir *Falta autorización*) o al restablecer el teléfono de fábrica.

## Política

En la política, sección **Soporte remoto**:

| Ajuste | Valores | Por defecto |
|--------|---------|-------------|
| Motor | Automático (el agente si está autorizado; si no, droidVNC-NG) · Solo el agente · Solo droidVNC-NG | Automático |
| Aviso en el teléfono «Soporte está viendo tu pantalla» | Sí / No | Sí |
| Llamada de voz | El usuario debe aceptar · Se contesta sola (desatendida) | El usuario debe aceptar |
| Pedir la autorización al inscribir | Sí / No | Sí |

## Limitaciones por versión de Android

| Android | Ver y controlar | Sin nadie en el teléfono | Cómo captura | Dentro del quiosco |
|---------|-----------------|--------------------------|--------------|--------------------|
| 6 (API 23) | No | — | — | — |
| 7 – 10 (API 24–29) | Sí | Sí, tras la autorización | Captura de pantalla de Android; el agente acepta él mismo el aviso «Iniciar ahora» (2–4 s al empezar cada sesión). Fluida: 6 imágenes/s o más. | **La pantalla no se puede ver** (Android bloquea ese aviso en quiosco): la consola lo dice y ofrece seguir con chat, llamada y archivos; para ver la pantalla, sacar el teléfono del quiosco o usar droidVNC-NG. |
| 11 (API 30) | Sí | Sí, tras la autorización | Igual que 7–10. | Sí, con capturas de accesibilidad: una imagen por segundo (límite de Android 11). |
| 12 – 16 (API 31–36) | Sí | Sí, tras la autorización | Capturas de accesibilidad, sin ningún aviso; hasta 3 imágenes/s (límite de Android). | Sí, igual que fuera del quiosco. En Android 16 (quiosco sin lock task) el mismo servicio vigila el quiosco y atiende la sesión a la vez. |

Probado en emulador en Android 10, 11, 15 y 16, dentro y fuera del quiosco (ver [TESTING.md](TESTING.md) y «Estado» abajo). Android 7–9 usa el mismo camino que Android 10 y no se corrió. En Android 7–11 la notificación de la
sesión no se puede ocultar por política: Android la exige mientras se captura la pantalla.

Válido en todas las versiones:

- Las apps que protegen su pantalla (bancos, algunos campos de contraseña) se ven **en negro**: lo decide la app.
- Un **bloqueo con PIN/patrón** se ve tal cual; el técnico puede escribirlo si lo conoce (o usar *Restablecer
  contraseña* de la consola). No se puede saltar.
- **Portapapeles del teléfono hacia la consola**: desde Android 10 Android solo entrega el portapapeles a la app
  que tiene el foco. El agente lo resuelve con su servicio de pantalla: pone una ventana propia de un píxel,
  invisible, que toma el foco un instante, lee y se quita (0,1–0,4 s medido en emulador). La app que estaba delante sigue donde
  estaba; su teclado puede cerrarse en ese instante. Necesita la autorización del soporte remoto (el mismo toque
  único). Si el portapapeles está vacío, la consola ofrece el texto del campo enfocado y lo dice.
- **Explorar archivos**: completo si el teléfono tiene «acceso a todos los archivos» para el agente (lo da el
  inscriptor por cable); si no, se ven fotos, videos, audio y la carpeta *Soporte*.
- **Teléfono en ahorro de batería y bloqueado**: toma la sesión en su siguiente conexión (minutos). Para soporte
  inmediato, modo **Siempre conectado** (botón en la pestaña Remoto).

## Limitaciones por fabricante

- **Xiaomi / HyperOS / MIUI**: el motor del agente no depende de arrancar otra app en segundo plano (lo que
  bloqueaba a droidVNC-NG con «Not allowed to start service»): la sesión vive en el propio agente, que ya corre como
  servicio en primer plano. Pendiente de confirmar en teléfono físico que HyperOS no apaga el interruptor de
  accesibilidad al reiniciar (algunas versiones lo hacen con apps sin «Inicio automático»; el inscriptor ya activa
  ese permiso).
- **Samsung (One UI)**: la pantalla de Accesibilidad llama a la sección *Apps instaladas*; el resto es igual.
- **Huawei / Honor sin servicios de Google, Oppo/Realme/Vivo**: sin diferencias conocidas; pendiente de teléfono físico.
- **Google Play Protect**: el agente declara ahora un servicio de accesibilidad y el permiso de micrófono. En una
  inscripción por cable no interviene. En una instalación manual del APK puede mostrar una advertencia.

## Rendimiento medido

Emuladores en la máquina de pruebas (red local, servidor en Docker). Ver los números de cada corrida en
[TESTING.md](TESTING.md#remote-support-through-the-agent-adr-0012).

| Medida (10 repeticiones, p50 / p90) | Objetivo | Android 10 | Android 11 | Android 15 | Android 16 |
|---|---|---|---|---|---|
| Primera imagen desde «iniciar sesión» | < 10 s | 3,9 s / 4,1 s | 1,4 s / 1,5 s | 1,07 s / 1,08 s | 1,07 s / 1,08 s |
| Toque → imagen con el efecto | < 1 s | 0,19 s / 0,22 s | 0,17 s / 0,20 s | 0,18 s / 0,44 s | 0,19 s / 0,51 s |
| Carga de la máquina (`uptime`, 1 min) durante la medición | — | 18 | 5,5–9,5 | 4,2–6,3 | 3,4–4,9 |

Medido el 2026-11-28 en emulador a 1080×2400, enrolado estilo QR, con el agente final (10 sesiones nuevas y 10 toques
por versión; el peor toque de las 40 mediciones fue 0,53 s). Android 11, 15 y 16 se midieron con la máquina libre;
Android 10, con la máquina a carga 18 (aun así 0,22 s). La cota de Android 12+ es una captura cada 333 ms; el agente
guarda una captura «en mano» cuando la pantalla está quieta y la toma 120 ms después de un toque. Dentro del
quiosco: 0,25–0,70 s en Android 15 y 16; en Android 11 dentro del quiosco, 0,8–1,5 s (allí Android solo da una
captura por segundo). En teléfono físico falta confirmarlo.

La orden de inicio viaja por el canal en vivo del agente (el mismo socket por el que el servidor lo despierta): un
teléfono conectado se une sin esperar a su siguiente reporte.

## Estado (2026-11-28)

Corridas finales en emulador, enrolamiento estilo QR (sin ningún permiso dado por cable), agente y servidor de esta
rama; `scripts/remote-support-e2e.mjs --authorize --kiosk --latency 10`:

| | Android 10 (API 29) | Android 11 (API 30) | Android 15 (API 35) | Android 16 (API 36) |
|---|---|---|---|---|
| Resultado | 42 de 43 (sin `--kiosk`: no aplica); 45 de 45 en la primera corrida | 73 de 73 | 75 de 75 | 69 de 70 |
| Sesión dentro del quiosco: ver, tocar, chat y respuesta, llamada, sigue en quiosco | no aplica (Android 7–10 no puede mostrar la pantalla en quiosco) | pasa (3 quioscos) | pasa (3 quioscos) | pasa (3 quioscos) |
| El técnico responde en el teléfono a través de la sesión (toca el campo, escribe, toca Enviar) | pasa | pasa | pasa | pasa |
| Aviso apagado por la política | no aplica (Android exige la notificación al capturar) | no aplica (igual) | pasa (4 de 4 corridas) | pasa (4 de 4 corridas) |
| Portapapeles del teléfono con otra app delante | pasa | pasa | pasa (también texto copiado a mano en el teléfono) | pasa (igual) |

Las dos fallas, tal como salieron: en Android 10, un toque del técnico sobre una fila de Ajustes no abrió la
pantalla (una vez; `--only control` repetido pasó 3 de 3); en Android 16, la sesión de «solo ver» abierta justo
después de las 10 sesiones de latencia no recibió imagen en 20 s (una vez; pasó en las otras 7 corridas de esa misma
comprobación en Android 11, 15 y 16). No se encontró la causa de ninguna de las dos. Otra falla vista en Android 16
(`--only control`, 1 de 3) era de la prueba, que dejaba Ajustes en una subpantalla: la prueba ahora abre Ajustes
desde su inicio. En la consola (Chromium, `scripts/remote-support-console-e2e.mjs`, Android 16): 28 de
29; la que falla es el micrófono sintético de Chromium oído como silencio en el teléfono (el audio del técnico al
teléfono sí se verifica en la prueba de protocolo, con un tono).

Qué se corrigió en esta ronda:

- **Portapapeles desde el teléfono**: antes solo llegaba el texto del campo enfocado; ahora llega el portapapeles.
- **Aviso de la sesión**: desde Android 12 la notificación «Soporte está conectado» podía tardar hasta 10 s en
  aparecer; ahora sale al instante.
- **Soporte dentro del quiosco de Android 16**: el producto funcionaba; lo que fallaba era la prueba, que buscaba lock task
  (Android 16 no lo usa). Probado ahora también con Ajustes fuera del quiosco, que es cuando el servicio de pantalla
  vigila el quiosco y atiende la sesión a la vez.
- Dentro de un quiosco el técnico tampoco puede abrir Ajustes en el teléfono (el quiosco lo devuelve): para eso se
  saca el equipo del quiosco desde la consola. Es por diseño.

## Guía de prueba para el cliente

Con un teléfono inscrito (Android 11 o superior recomendado) y un usuario de consola con permiso de editar
dispositivos:

1. **Autorización** (solo inscripción por QR): al terminar la inscripción aparece *Activa el soporte remoto*. Tocar
   **Abrir ajustes**, elegir *Soporte remoto DallyControl*, activar, **Permitir**. En la consola, pestaña **Remoto**
   del dispositivo: debe decir **Remoto listo**.
2. **Ver y controlar sin nadie en el teléfono**: dejar el teléfono sobre la mesa. En la consola, **Ver y
   controlar**. En menos de 10 segundos aparece la pantalla del teléfono; en el teléfono, la etiqueta *Soporte está
   viendo tu pantalla*. Hacer clic en un ícono de la imagen: se abre en el teléfono. Probar **Atrás**, **Inicio**,
   **Recientes**; abrir un campo de texto y escribir con el teclado del computador.
3. **Chat**: escribir un mensaje en el panel de chat y **Enviar**. En el teléfono se abre *Soporte técnico* con el
   mensaje; responder desde el teléfono: la respuesta aparece en la consola.
4. **Llamada**: **Llamar** (el navegador pide el micrófono la primera vez). El teléfono suena y muestra *Soporte
   quiere hablar contigo*: **Aceptar**. Hablar en los dos sentidos. Probar **Silenciar** en cada lado y **Colgar**.
5. **Archivos**: **Archivos → Enviar archivo**; en el teléfono queda en *Descargas/Soporte*. **Explorar el
   teléfono** y descargar un archivo.
6. **Captura y grabación**: **Captura** descarga un PNG; **Grabar** / **Detener** descarga un video de la sesión.
7. **Bloquear / Reiniciar** desde la barra de la sesión.
8. **Solo ver**: terminar la sesión y abrir otra con **Solo ver**: se ve la pantalla pero los clics no hacen nada.
9. **Registro**: **Terminar sesión**. En **Historial de sesiones** (misma pestaña) y en el menú **Sesiones de
   soporte** aparece la sesión con usuario, hora, duración y lo que se usó; al desplegarla, los mensajes del chat.
10. **Política**: en la política del teléfono, *Soporte remoto → Llamada de voz → Se contesta sola*; guardar. La
    siguiente llamada se establece sin que nadie acepte en el teléfono.

## Qué solo se puede confirmar en un teléfono físico

- La **calidad real del audio** (micrófono, altavoz, cancelación de eco del fabricante). En emulador se comprueba
  que el audio viaja en los dos sentidos y que el micrófono se abre, con un tono sintético.
- Que el **fabricante no apague** el servicio de accesibilidad ni el agente en segundo plano (Xiaomi/HyperOS,
  Huawei, Oppo).
- La pantalla guiada dentro de una **inscripción real por QR / Zero-Touch** (en emulador se reproduce quitándole al
  agente el permiso que da el cable y haciendo el toque en las pantallas reales de Ajustes).
- El comportamiento con **datos móviles** y cambios de red a mitad de sesión (el agente reintenta 45 s).
- **Google Play Protect** frente al APK firmado de producción.
- La **latencia del toque** (en emulador 0,17–0,19 s de mediana, 0,51 s el percentil 90 más alto) con la red y el
  procesador de un teléfono real.
- **Traer el portapapeles** con el teclado y la capa de cada fabricante (la ventana de un píxel toma el foco un
  instante: comprobar que la app de trabajo no pierde lo que se estaba escribiendo).
- **Android 7, 8 y 9**: mismo camino que Android 10, no corrido en esta ronda.
