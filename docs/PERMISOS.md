# Permisos de apps sin intervención del usuario (MDM-06)

Cómo se conceden, deniegan o dejan al usuario los permisos de las apps desde la consola, qué hace el agente en el
teléfono, qué reporta, y qué límites pone Android (con la referencia de la API).

Requiere que el agente sea **propietario del dispositivo** (Device Owner) y agente 0.9.9 o superior. Un agente
anterior ignora la sección nueva y sigue como antes (concede todo lo que las apps piden); la consola lo dice en la
pestaña **Permisos** del equipo.

## 1. Tres niveles

| Nivel | Dónde | Qué fija |
|---|---|---|
| **Política por defecto** | Políticas → editar → Seguridad → **Permisos de apps** | Qué responde Android cuando *cualquier* app pide un permiso. |
| **Por app, en la política** | Políticas → editar → **Permisos por app** | Conceder / Denegar / Preguntar por permiso, el preajuste «todos los que la app declara» y los accesos especiales, para todos los equipos de la política. |
| **Por app, en un equipo** | Dispositivo → pestaña **Permisos** | Lo mismo, solo para ese equipo, encima de su política. |

Gana lo más específico: la regla del equipo sobre la de la política, y la de un permiso sobre el preajuste de la app.

### Política por defecto

| Opción | En el teléfono (`DevicePolicyManager.setPermissionPolicy`) |
|---|---|
| Conceder todo automáticamente | `PERMISSION_POLICY_AUTO_GRANT`: la app pide el permiso y Android lo concede sin mostrar diálogo. |
| Preguntar al usuario | `PERMISSION_POLICY_PROMPT`: el usuario decide, salvo lo que fijen las reglas por app. |
| Denegar todo | `PERMISSION_POLICY_AUTO_DENY`: se deniega sin diálogo, salvo lo que concedan las reglas por app. |
| Conceder todo, pero preguntar por la ubicación | Preguntar + «conceder todos» a las apps de la política, con la ubicación en «preguntar». |
| Conceder todo, pero denegar la ubicación | Conceder + ubicación denegada a las apps de la política. |

El agente la aplica cada vez que aplica la política y en cada arranque (es idempotente: solo escribe si cambió), y
reporta la que está realmente en vigor. Los campos «Modo permisivo» y «Bloquear permiso de ubicación» se retiraron de
la consola: no hacían nada (el segundo es ahora la opción «…denegar la ubicación»).

`setPermissionPolicy` solo afecta a lo que las apps pidan **a partir de ese momento**. Para cambiar un permiso ya
contestado, o para no depender de que la app lo pida, están las reglas por app.

### Reglas por app

Por cada app (las de la política, o cualquier paquete que se escriba: preinstalado o instalado por el usuario):

- **Conceder** / **Denegar**: `setPermissionGrantState(GRANTED | DENIED)`. Queda **fijado**: el usuario no puede
  cambiarlo en Ajustes y la app no muestra diálogo.
- **Preguntar**: `PERMISSION_GRANT_STATE_DEFAULT`, queda en manos del usuario. Si el permiso lo había concedido el MDM,
  primero se retira para que la app tenga que pedirlo. Con la política por defecto «Conceder todo», Android lo concede
  al pedirlo sin mostrar diálogo: para que de verdad pregunte, la política por defecto debe ser «Preguntar».
- **Todos los que la app declara**: el preajuste se aplica a cada permiso de tiempo de ejecución del manifiesto de la
  app, también a los que no tienen fila propia.
- Al **quitar** una regla, el agente libera solo lo que él fijó (el permiso queda como esté, pero ya no bloqueado).

Permisos con fila propia: ubicación precisa, aproximada y en segundo plano; cámara; micrófono; almacenamiento y
fotos/videos/audio; contactos; teléfono; SMS; notificaciones (`POST_NOTIFICATIONS`, Android 13+); Bluetooth y
dispositivos cercanos (Android 12+); actividad física y sensores corporales; calendario.

Cuándo se aplican: al aplicar la política, **en el instante en que la app se instala** (antes de abrirla, también si
la instala el usuario o la tienda), en cada arranque del agente, y a las apps **ya instaladas** en cuanto llega la
regla. La ubicación en segundo plano se concede siempre después de la precisa/aproximada.

## 2. Límites de Android

### Permisos de sensores en Android 12 o superior

Desde Android 12 un administrador solo puede **conceder** ubicación, cámara, micrófono, sensores corporales y
actividad física si `DevicePolicyManager.canAdminGrantSensorsPermissions()` devuelve `true`. Devuelve `false` cuando el
equipo se aprovisionó con `EXTRA_PROVISIONING_SENSORS_PERMISSION_GRANT_OPT_OUT`. Denegarlos siempre se puede.

El agente lo consulta y lo reporta. Si el equipo no lo permite, la pestaña **Permisos** muestra un aviso rojo para ese
equipo, cada fila afectada dice «este equipo no deja al administrador concederlo», y el resultado de la política
queda como «aplicado (este equipo no deja…)» en vez de fallar una y otra vez. La solución es que el usuario acepte el
permiso en el teléfono, o reinscribir el equipo sin esa opción (la inscripción por QR y por USB de DallyControl no la
activa).

### Accesos especiales

No son permisos de tiempo de ejecución: `setPermissionGrantState` no los cubre y Android no da al propietario del
dispositivo una API pública para activarlos. El agente hace lo que sí existe y, para lo demás, la consola muestra
**«Requiere un toque del usuario»** con el estado real y el botón **Abrir en el teléfono**, que abre la pantalla exacta
(en quiosco se deja pasar esa pantalla durante 5 minutos).

| Acceso | Lo que hace el MDM solo | Lo que requiere al usuario | Referencia |
|---|---|---|---|
| Acceso a todos los archivos | Nada (en Android ≤10 lo cubre el permiso Almacenamiento) | Activarlo (Android 11+) | `MANAGE_EXTERNAL_STORAGE` es un app‑op; `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` |
| Mostrar sobre otras apps | Nada | Activarlo | `SYSTEM_ALERT_WINDOW` es un app‑op; `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` |
| Modificar ajustes del sistema | Nada | Activarlo | `WRITE_SETTINGS` es un app‑op; `Settings.ACTION_MANAGE_WRITE_SETTINGS` |
| Instalar apps desconocidas | Android ≤7: lo activa (`setSecureSetting(INSTALL_NON_MARKET_APPS)`) | Android 8+: activarlo para esa app | app‑op `REQUEST_INSTALL_PACKAGES`; `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` |
| Sin restricciones de batería | Android 11+: protege la app (`setUserControlDisabledPackages`): no se puede forzar su detención y queda exenta del reposo de apps | Añadirla a «sin optimizar» (lista de Doze) | `setApplicationExemptions` es `@SystemApi` (no disponible para un Device Owner); `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` |
| Uso de datos sin restricción | Nada | Activarlo. Android no deja leer este estado de otra app: la consola lo dice | `Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS` |
| Acceso a notificaciones | Nada | Activar el servicio de la app | `NotificationManager.setNotificationListenerAccessGranted` es `@SystemApi`; `Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS` |
| Accesibilidad | Si hay una lista de servicios permitidos, añade la app (`setPermittedAccessibilityServices` solo restringe) | Activar el servicio | `Settings.ACTION_ACCESSIBILITY_SETTINGS` |

El estado de cada acceso se lee del teléfono (app‑ops, lista de Doze, servicios habilitados) y se actualiza en cuanto
el usuario lo activa.

### Otros

- Apps que apuntan a Android 5.1 o anterior (targetSdk < 23) no tienen permisos de tiempo de ejecución: Android puede
  rechazar el cambio; el resultado lo dice («Android no dejó fijar: …») sin dar la política por fallida.
- Los permisos «normales» (Internet, vibración…) y los de firma no se piden ni se conceden: los da la instalación.

## 3. Inventario

El agente reporta, en su conexión periódica y solo cuando algo cambió (o cada 6 horas): la política por defecto en
vigor, si el equipo deja conceder sensores, el resultado de la última aplicación y, por cada app gestionada (las que
tienen regla y todas las instaladas encima del sistema), cada permiso que declara — concedido o no, fijado por
política o no, la regla en vigor — y cada acceso especial.

En **Dispositivo → Permisos** se ve por app, junto a su regla, y **Descargar CSV** lo exporta (una fila por app y
permiso; UTF‑8 con BOM, separado por punto y coma). **Actualizar estado** hace que el teléfono vuelva a aplicar las
reglas y reporte en el momento.

## 4. Pruebas

```bash
scripts/build-permtestapp.sh                         # app de prueba: pide ubicación, cámara, almacenamiento…
scripts/permissions-e2e.py --api http://localhost:8088 --serial emulator-5600 --device-id <número del equipo>
DALLYCONTROL_ADMIN_PASSWORD=admin node scripts/permissions-console-e2e.mjs --api http://localhost:8088
```

`permissions-e2e.py` usa un agente real (emulador o teléfono, Device Owner): instala la app de prueba desde la consola
y comprueba en el propio teléfono, con `dumpsys package` (granted=true, `POLICY_FIXED`), `dumpsys device_policy`
(política de permisos), la ventana en primer plano (ningún diálogo) y el registro de la app, cada caso de este
documento. `permissions-console-e2e.mjs` prueba las pantallas en Chromium con un teléfono simulado, incluido el aviso
de sensores de Android 12+. Resultados en [TESTING.md](TESTING.md).
