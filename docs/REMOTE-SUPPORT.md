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
| Portapapeles | Enviar texto al portapapeles del teléfono; traer el texto del campo enfocado. |
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

| Android | Ver | Controlar | Sin nadie en el teléfono | Notas |
|---------|-----|-----------|--------------------------|-------|
| 6 (API 23) | No | No | — | Android no tiene las funciones necesarias. |
| 7 – 10 (API 24–29) | Sí | Sí | Sí, tras la autorización | La imagen usa la captura de pantalla de Android; el agente acepta él mismo el aviso «Iniciar ahora». **No funciona dentro del quiosco** en estas versiones (Android no deja mostrar ese aviso en modo quiosco): ahí, usar droidVNC-NG o sacar el teléfono del quiosco durante el soporte. **Este camino está implementado pero aún no se probó en emulador ni en teléfono**: mientras tanto, en Android 7–10 usar droidVNC-NG (motor «Solo droidVNC-NG»); chat, voz y archivos sí funcionan con el agente. |
| 11 – 16 (API 30–36) | Sí | Sí | Sí, tras la autorización | Sin ningún aviso, también en quiosco y con el teléfono bloqueado. Android limita la captura a unas 3 imágenes por segundo: suficiente para guiar y operar, no para ver video. |

Válido en todas las versiones:

- Las apps que protegen su pantalla (bancos, algunos campos de contraseña) se ven **en negro**: lo decide la app.
- Un **bloqueo con PIN/patrón** se ve tal cual; el técnico puede escribirlo si lo conoce (o usar *Restablecer
  contraseña* de la consola). No se puede saltar.
- **Portapapeles del teléfono hacia la consola**: desde Android 10 una app en segundo plano no puede leer el
  portapapeles; la consola trae el texto del campo enfocado (o seleccionado), no el portapapeles en sí.
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

| Medida | Objetivo | Resultado |
|--------|----------|-----------|
| Tiempo hasta la primera imagen (desde «Ver y controlar») | < 10 s | ver TESTING.md |
| Latencia de un toque (clic → imagen con el efecto) | < 1 s | ver TESTING.md |
| El teléfono se une a la sesión | — | ver TESTING.md |

La orden de inicio viaja por el canal en vivo del agente (el mismo socket por el que el servidor lo despierta): un
teléfono conectado se une sin esperar a su siguiente reporte.

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
