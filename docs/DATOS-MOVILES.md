# Datos móviles por aplicación

Restringe qué aplicaciones pueden gastar el plan de datos del teléfono, pone límites de consumo por app y por
equipo, avisa antes de que se agoten y bloquea solo cuando se superan. Todo se define en la política (sección
**Datos móviles**), se puede ajustar por equipo y lo aplica el agente en el propio teléfono, aunque no tenga conexión
con la consola.

Requiere el agente como **dueño del dispositivo** (Device Owner). El agente DallyControl y su conexión con la consola
nunca se restringen, tampoco la app de soporte remoto.

## Qué se puede configurar

En **Políticas → (política) → Datos móviles**:

| Campo | Qué hace |
|-------|----------|
| Datos móviles por aplicación | *Sin restricción por app*, *Lista negra: solo Wi‑Fi* (las apps de la lista no usan datos móviles) o *Lista blanca: solo estas apps* (solo las de la lista los usan). |
| Lista de apps | Se eligen de las apps de la política (un toque) o se escribe el nombre del paquete. «Solo Wi‑Fi por app» es la lista negra. |
| Límite de datos del equipo | Megabytes por periodo para todo el teléfono. Al superarlo, todas las apps menos las esenciales quedan sin datos móviles hasta el siguiente periodo. |
| Límite de datos por app | Megabytes por periodo para una app. Al superarlo, esa app queda solo con Wi‑Fi hasta el siguiente periodo. |
| Periodo | Mensual con día de corte (1 a 28) o diario. Se calcula con la hora del teléfono. |
| Umbral de aviso | Porcentaje del límite que genera la alerta *Datos móviles: cerca del límite* (80 % si se deja vacío; con 100 solo se avisa al superar). |
| Apps esenciales | Conservan los datos móviles aunque el equipo supere su límite, y en lista blanca cuentan como permitidas. |
| Mecanismo de bloqueo | *Automático* (recomendado), *Restricción del sistema* o *VPN local del agente*. Ver abajo. |

En lista blanca y al superar el límite del equipo se restringen las apps «de cara al usuario»: las instaladas por el
usuario o por la consola y las de sistema que tienen ícono (Chrome, YouTube, Play Store…). Los servicios internos de
Android sin ícono y los servicios de Google Play (`com.google.android.gms`, `com.google.android.gsf`) solo se
restringen si la política los nombra en una lista negra: cortarlos rompe el inicio de sesión, las notificaciones y la
ubicación de todas las apps.

### Ajuste por equipo

En **Dispositivos → (equipo) → Uso → Datos móviles → Ajustar en este equipo**. Gana sobre la política, solo en ese
teléfono:

- *Sin restricción de datos móviles en este equipo*: ignora listas y límites.
- *Apps que siempre tienen datos móviles aquí* / *Apps que nunca tienen datos móviles aquí*.
- *No aplicar los límites de datos en este equipo* (por ejemplo, si se le compró más plan): las listas siguen.

Al guardar, la consola envía la configuración al teléfono en el momento (si está en línea la aplica en segundos).
*Volver a la política* quita el ajuste.

### Dónde se ve

- **Equipo → Uso**: mecanismo que usa el teléfono, apps sin datos móviles ahora y por qué (lista de la política, superó
  su límite, el equipo superó su límite), consumo del periodo contra cada límite con su porcentaje, y las apps que más
  datos móviles usaron en el periodo.
- **Alertas**: tipos *Datos móviles: cerca del límite* y *Datos móviles: límite superado*, con el detalle
  (`YouTube (com.google.android.youtube): 85 MB de 100 MB (85 %)` o `Equipo: 410 MB de 500 MB (82 %)`). Una alerta
  por límite y periodo. Llegan por correo a los destinatarios de **Geocercas → Notificaciones** (se pueden filtrar
  por estos dos tipos) si el servidor tiene SMTP.
- **Reportes → Datos móviles: uso vs. límite**: una fila por equipo y una por cada app con límite (consumo, límite,
  porcentaje, estado, apps sin datos móviles, mecanismo). Se exporta a Excel o CSV y se puede programar por correo.
  El reporte **Datos móviles** (mayores consumidores) sigue igual.
- **Equipo → Eventos**: los mismos avisos en la línea de tiempo del equipo.

## Cómo se bloquea (mecanismos)

Android no tiene un interruptor de MDM «bloquear datos móviles a la app X». Se usan dos mecanismos:

### 1. Restricción de datos del sistema — Android 9 o superior (predeterminado)

`DevicePolicyManager.setMeteredDataDisabledPackages`: el propio Android niega las **redes medidas** a las apps de la
lista, también cuando la app está en primer plano. No hay VPN, ni ícono, ni nada que el usuario pueda quitar. Se ve
en `dumpsys netpolicy` (*Admin restricted uids for metered data*).

Límites de este mecanismo:

- Bloquea redes **medidas**, no «la red celular» en sí. Los datos móviles siempre son medidos. Un Wi‑Fi marcado como
  medido (por ejemplo la zona portátil de otro celular, que Android detecta sola, o una red que el usuario marcó como
  «de uso medido») también queda bloqueado para esas apps. Es coherente con el objetivo (ese Wi‑Fi gasta un plan de
  datos), pero conviene saberlo.
- Algunas capas de fabricante podrían no respetarlo. Si en un modelo una app bloqueada sigue navegando con datos
  móviles, pon *Mecanismo de bloqueo → VPN local del agente* en la política de esos equipos.

### 2. VPN local del agente — Android 7 y 8, o cuando se fuerza

Un `VpnService` del propio agente que **no se conecta a ningún servidor**: solo captura las apps que no deben usar
datos móviles y deja morir sus paquetes. Las apps permitidas nunca entran al túnel, así que su tráfico (y el del
agente) no se toca ni se ralentiza.

- La fija el Device Owner con `setAlwaysOnVpnPackage` (eso también la autoriza: **no aparece el diálogo de
  consentimiento**) y bloquea los ajustes de VPN con `DISALLOW_CONFIG_VPN`, para que el usuario no la desconecte.
- **Sin «lockdown»**: el modo «bloquear conexiones sin VPN» de Android cortaría a todas las apps, también en Wi‑Fi,
  que es lo contrario de lo que se quiere. No se usa en ningún modo.
- En red móvil (o sin red) el túnel captura las apps bloqueadas. En Wi‑Fi solo retiene un paquete del sistema que no
  tiene permiso de Internet, así las apps bloqueadas vuelven a funcionar y la VPN sigue «conectada» (una VPN siempre
  activa que se desconecta hace que Android muestre un aviso permanente). El cambio Wi‑Fi ↔ móvil se detecta con un
  `NetworkCallback` y el túnel se reemplaza de forma atómica: las apps permitidas no se cortan.
- Si el equipo **ya tiene otra VPN siempre activa**, el agente no la reemplaza: la sección falla, la política aparece
  como no aplicada en ese equipo (reporte *Incumplimiento*) y la tarjeta del equipo muestra el problema
  («hay otra VPN siempre activa en el equipo (paquete)»). Hay que quitar esa VPN o usar la restricción del sistema.
- Android muestra el ícono de llave de VPN mientras el túnel está arriba. Solo puede haber una VPN de usuario a la vez:
  una app VPN de la empresa no puede convivir con este mecanismo.
- Los paquetes de una app bloqueada se descartan en silencio: la app ve un tiempo de espera agotado, no un error
  inmediato. Las consultas DNS las hace el sistema y pueden salir; la conexión de la app no.
- Android 6 (API 23): no existe forma de fijar la VPN sin que el usuario la acepte; la sección se reporta como fallida.

### Qué elige «Automático»

| Android | Mecanismo |
|---------|-----------|
| 9 o superior (API 28+) | Restricción de datos del sistema |
| 7 a 8.1 (API 24–27) | VPN local del agente |
| 6 (API 23) | No disponible (la política lo reporta como fallo) |

Al cambiar de mecanismo, quitar la sección de la política o poner el equipo «sin restricción», el agente deshace lo
que él mismo puso (lista del sistema vacía, VPN siempre activa fuera, ajustes de VPN desbloqueados).

## Límites, medición y alertas

- **De dónde sale el consumo.** El agente lee los contadores del propio Android (`NetworkStatsManager`, los mismos de
  *Ajustes → Uso de datos*) para todo el periodo en una sola consulta. No acumula nada por su cuenta, así que nada se
  puede contar dos veces, ni por reinicios ni por reenvíos. El total del equipo incluye el tráfico de apps ya
  desinstaladas y de la zona portátil.
- **Precisión.** Android guarda el consumo en bloques de 2 horas; el bloque que cruza el inicio del periodo se
  reparte en proporción. Apps que comparten identidad de sistema (mismo UID) comparten consumo. La operadora puede
  medir distinto (redondeos, tráfico gratuito): el límite es contra lo que mide Android.
- **Cuándo se evalúa.** Cada minuto mientras haya una política activa, en cada reporte a la consola y en cada cambio
  de red. Una app puede pasarse del límite por lo que alcance a consumir en ese minuto.
- **Alertas.** Al cruzar el umbral de aviso y al superar el límite el agente registra un evento y reporta de
  inmediato; la consola lo convierte en alerta (una por límite y periodo). El bloqueo no depende de la consola: el
  teléfono bloquea aunque esté sin conexión con el servidor.
- **Siguiente periodo.** Al empezar el periodo el consumo vuelve a contarse desde cero y las apps bloqueadas por
  límite recuperan los datos móviles solas.
- **En la consola**, el consumo del periodo es la lectura del teléfono cuando la reportó para el periodo vigente; si
  no (agente anterior a esta función), se suma de los reportes diarios por app que ya existían, con el periodo
  calculado en hora de Colombia.

## Compatibilidad

- Agentes anteriores ignoran la sección: siguen funcionando, sin bloqueo ni alertas de límite (la consola lo indica en
  la tarjeta del equipo: «el teléfono aún no lo ha reportado»).
- Un agente nuevo con políticas sin la sección no hace nada (y la revisión de esas políticas no cambia).
- Folder admins: ven y ajustan solo los equipos de sus carpetas; el reporte se limita a sus equipos.

## Cómo probarlo

### Servidor (sin teléfono)

`scripts/mobile-data-e2e.py` hace de equipo por la API del agente: comprueba que la sección llega al documento del
equipo (megabytes → bytes), que el ajuste por equipo cambia el documento y su revisión, la tarjeta del equipo, las
alertas a partir de los eventos (sin duplicar), el reporte y su exportación, y con `--mailpit` el correo.

```bash
scripts/mobile-data-e2e.py --base http://localhost:8080
# con correo:
docker run -d --name mailpit --network dallycontrol-dev_default -p 127.0.0.1:8025:8025 axllent/mailpit
SMTP_HOST=mailpit SMTP_PORT=1025 docker compose --env-file docker/dev.env up -d server
scripts/mobile-data-e2e.py --base http://localhost:8080 --mailpit http://localhost:8025
```

### Emulador (agente real)

`adb shell svc wifi disable` deja al emulador en su red celular emulada (medida): eso es «datos móviles» para la
prueba. El script instala tres apps de prueba que hacen una petición HTTP por lanzamiento (una bloqueada, una
permitida, una esencial) y sirve él mismo lo que descargan.

```bash
scripts/build-netprobe-testapps.sh                         # una vez: las apps de prueba
(cd agent-android && ./gradlew :app:assembleDebug)
emulator -avd mdm_api30 -no-window -no-audio -no-snapshot &
scripts/adb-enroll.sh --server http://10.0.2.2:8088 --api-url http://localhost:8088 --debug-build \
    --apk agent-android/app/build/outputs/apk/debug/app-debug.apk --admin-user admin --serial emulator-5554
scripts/mobile-data-e2e.py --base http://localhost:8088 --serial emulator-5554 --device <número del equipo>
```

Recorre: sin restricción → lista negra con la restricción del sistema (la app bloqueada falla con datos móviles y
funciona con Wi‑Fi; la permitida y el agente siguen funcionando; cambio Wi‑Fi ↔ móvil) → lo mismo forzando la VPN
local (VPN siempre activa del agente, sin diálogo, ajustes de VPN bloqueados) → lista blanca → ajuste por equipo →
límite por app (alerta de aviso, luego alerta de límite y bloqueo automático; con Wi‑Fi sigue funcionando) → límite
del equipo (todas menos la esencial) → «sin límites» en el equipo → se quita la sección y todo queda como antes. En
el camino revisa `dumpsys netpolicy`, `dumpsys device_policy` y `dumpsys connectivity`.

Comprobaciones a mano:

```bash
adb shell dumpsys netpolicy | grep -A3 "Admin restricted uids"      # restricción del sistema: UID de las apps bloqueadas
adb shell dumpsys device_policy | grep -i -A3 metered               # la lista que puso el agente
adb shell settings get secure always_on_vpn_app                     # VPN local: el paquete del agente
adb shell dumpsys connectivity | grep -i "Transports: .*VPN"        # el túnel
adb logcat -s MobileData MobileDataVpn NetProbe                     # qué decide el agente y cómo le va a la app
```

### Consola

`scripts/mobile-data-console-e2e.mjs` abre la consola en Chromium sin ventana: edita la sección en una política, la
guarda y la vuelve a abrir, y revisa la tarjeta del equipo, el ajuste por equipo, los tipos de alerta y el reporte.

```bash
(cd scripts/shots && npm ci)       # una vez: Playwright
DALLYCONTROL_ADMIN_PASSWORD=admin node scripts/mobile-data-console-e2e.mjs --api http://localhost:8088 [--device <número>]
```

### Solo en un teléfono físico

- Que la capa del fabricante respete la restricción del sistema (Samsung, Xiaomi, Motorola…): con una SIM con datos,
  Wi‑Fi apagado, la app bloqueada no debe cargar y la permitida sí.
- Consumo real contra el de la operadora, el corte de periodo en la fecha real y el comportamiento con doble SIM.
- Zona portátil de otro teléfono como Wi‑Fi medido (queda bloqueada con la restricción del sistema).
- Con la VPN local: el ícono de llave, el aviso de red supervisada de cada marca y el arranque tras reiniciar.
