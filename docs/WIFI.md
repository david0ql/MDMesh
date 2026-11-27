# Wi‑Fi corporativo

Redes Wi‑Fi que la política guarda en los equipos, con sus certificados, y los bloqueos que impiden al usuario
cambiarlas o conectarse a otras. Todo se configura en **Políticas → (la política) → Redes Wi‑Fi** y se aplica solo: el
equipo recibe el cambio en su siguiente reporte, sin tocarlo.

Requisito: el agente como **Device Owner** (la inscripción normal de DallyControl). Sin serlo solo puede *sugerir*
redes al sistema (ver [Qué API usa el agente](#qué-api-usa-el-agente-según-la-versión-de-android)).

## Redes

Cada red tiene nombre (SSID), seguridad y, según la seguridad, contraseña o datos de 802.1X.

| Seguridad | Qué es | Android mínimo |
|---|---|---|
| WPA2‑Personal | WPA/WPA2 con contraseña (PSK), 8–63 caracteres o la clave de 64 hexadecimales | 6 |
| WPA3‑Personal | SAE. Solo contraseña (no la clave hexadecimal) | 10, y un chip Wi‑Fi con WPA3 |
| WPA2/WPA3 (transición) | Para puntos de acceso en modo mixto. Android 12+ usa WPA3 cuando puede; los anteriores, WPA2 | 6 |
| WPA2‑Enterprise | 802.1X (EAP) contra un servidor RADIUS | 6 |
| WPA3‑Enterprise | 802.1X con WPA3 | 12 |
| Abierta cifrada (OWE) | Sin contraseña pero cifrada | 10, y un chip con OWE |
| Abierta | Sin contraseña ni cifrado | 6 |
| WEP | Obsoleta; solo si el equipo de red no admite otra cosa | 6 |

Un equipo que no puede guardar una red (por ejemplo WPA3 en Android 9) **no falla la política**: guarda las demás y
en su ficha la red aparece como «No soportada en esta versión de Android».

Las contraseñas **no se vuelven a mostrar** después de guardar: aparecen como «guardada» con un botón **Cambiar**. Si
no escribes una nueva, se conserva la que estaba. Tampoco salen en el historial de cambios de la política ni en la API.

### Opciones por red (botón «Opciones»)

| Opción | Qué hace | Disponible en |
|---|---|---|
| Red oculta | Para redes que no anuncian su nombre | todas |
| Conectarse automáticamente | Apagada: la red queda guardada pero el equipo no entra solo | todas |
| Prioridad (0–999) | Con varias redes gestionadas al alcance, el equipo se pasa a la de mayor prioridad | todas (ver nota) |
| Dirección MAC | Aleatoria o la del dispositivo (para filtros por MAC o reservas DHCP) | Android 13+ (en 10–12 Android puede rechazarla; antes de 10 siempre es la del dispositivo) |
| Uso de datos | Medida / no medida | Android 11+ (en 9–10 Android puede rechazarla) |
| Proxy | Manual (servidor, puerto, excepciones) o archivo PAC | Android 8+ |
| Dirección IP | Fija (IP, prefijo, puerta de enlace, DNS) en lugar de DHCP | todas |

Nota sobre la prioridad: Android dejó de respetar la prioridad de una red guardada en Android 8. El agente la aplica
él mismo: cada 5 minutos, si hay al alcance una red gestionada de mayor prioridad que la actual, se conecta a ella.

Si una versión de Android rechaza una de estas opciones, el equipo guarda la red **sin** esa opción y la ficha del
equipo lo dice («Sin aplicar en esta versión de Android: …»).

### Redes empresariales (802.1X)

1. En **Certificados Wi‑Fi** (dentro del mismo bloque) sube la **CA** que firma el certificado del servidor RADIUS, en
   PEM o DER. Para **EAP‑TLS** sube además el certificado del equipo con su clave en **PKCS#12** (.p12/.pfx) y su
   contraseña.
2. Agrega la red con seguridad WPA2‑Enterprise (o WPA3‑Enterprise) y elige:
   - **Método**: PEAP (con MSCHAPv2 o GTC), TTLS (con PAP, MSCHAPv2 o GTC) o TLS (certificado de cliente).
   - **Identidad** y **contraseña** (PEAP/TTLS), e **identidad anónima** si el servidor la usa.
   - **Dominio del servidor** (por ejemplo `radius.empresa.com`) y, si hace falta, el nombre alternativo
     (`DNS:radius.empresa.com`).
   - La **CA** del servidor y, con TLS, el **certificado de cliente**.

La consola (y el servidor) **no aceptan** una red 802.1X sin CA y sin dominio: sin ellos el equipo entregaría las
credenciales a cualquier punto de acceso que use el mismo nombre de red.

Los certificados se guardan **cifrados** (AES‑256‑GCM) y nunca se devuelven: la consola solo muestra a quién
pertenecen, quién los emitió, su huella y **cuándo vencen** (en ámbar a menos de 30 días, en rojo vencidos). La
clave de cifrado vive en un archivo del servidor (`wifi-certs.key`, junto a sus otras claves), no en la base de
datos: **inclúyelo en los respaldos**; sin él los certificados guardados no se pueden abrir y hay que subirlos de nuevo.
Un certificado que usa alguna política no se puede eliminar. Subir y eliminar certificados requiere el permiso «Crear
políticas nuevas»; verlos y usarlos en una red, el de «Ver políticas».

En el equipo, el agente instala la CA (`installCaCert`) y el par de claves del cliente (`installKeyPair`) como Device
Owner, y los quita cuando ninguna red de la política los usa. En Android 9 y anteriores el almacén de claves del
sistema solo acepta el par de claves si el equipo tiene bloqueo de pantalla; sin él, la red EAP‑TLS igual queda
configurada con su propia copia del certificado (es la que usa el Wi‑Fi).

## Bloqueos de Wi‑Fi

| Bloqueo | Qué hace | Disponible en |
|---|---|---|
| Bloquear las redes gestionadas | El usuario no puede editarlas ni olvidarlas | todas |
| Impedir agregar redes | El usuario no puede guardar redes nuevas | Android 13+ |
| Impedir cambiar la configuración de Wi‑Fi | Cierra los ajustes de Wi‑Fi | todas |
| Impedir encender o apagar el Wi‑Fi | El interruptor deja de responder al usuario | Android 13+ |
| Solo las redes gestionadas y las de una lista | «Solo redes corporativas»: el equipo no se conecta a ninguna otra | Android 13+ |
| Todas menos las de una lista | Redes a las que nunca se conecta | Android 13+ |
| Seguridad mínima | Personal / empresarial / empresarial de 192 bits: no entra a redes más débiles | Android 13+ |

El **radio** es otra cosa y está en **Red → Wi‑Fi** de la política: sin administrar, forzar encendido o forzar apagado.
Si alguien lo cambia, el agente lo vuelve a dejar como dice la política.

En un equipo con Android anterior al que pide un bloqueo, ese bloqueo no se aplica y la ficha del equipo lo indica
(«no disponible en esta versión de Android»); el resto de la política sí.

### Para no dejar un equipo sin conexión

Un bloqueo mal puesto puede dejar a un equipo sin red y, sin red, ya no recibe la corrección. Hay tres barreras:

1. **La consola avisa** mientras editas y **el servidor rechaza** al guardar: «solo redes permitidas» sin ninguna red,
   impedir agregar redes sin traer ninguna, una seguridad mínima que ninguna red gestionada alcanza, una red gestionada
   en la lista de bloqueadas, Wi‑Fi forzado apagado con redes configuradas.
2. **El agente nunca se excluye a sí mismo**: la lista de permitidas siempre incluye las redes gestionadas, la de
   bloqueadas nunca las incluye, y la seguridad mínima nunca queda por encima de la mejor red gestionada.
3. **Salvaguarda**: con un bloqueo restrictivo activo, si el equipo pasa **N minutos** (10 por defecto; se elige en la
   política, 1–120) sin ninguna red utilizable —ni Wi‑Fi ni datos móviles—, levanta por su cuenta los bloqueos que
   impiden conectarse (lista de redes, seguridad mínima, agregar/cambiar redes, interruptor, radio apagado), enciende
   el Wi‑Fi y lo reporta: evento «Bloqueo de Wi‑Fi relajado» y un aviso en la ficha del equipo. Las redes gestionadas
   siguen bloqueadas contra edición. Pasado un rato (6×N, mínimo 30 minutos) vuelve a aplicar el bloqueo y a vigilar.
   Una política nueva reinicia todo.

## Estado en cada equipo

En la ficha del equipo, **Wi‑Fi gestionado** muestra por cada red de la política:

| Estado | Significa |
|---|---|
| Conectada | El equipo está en esa red |
| Configurada | Guardada, al alcance o sin datos de alcance (red oculta), sin errores |
| Fuera de alcance | Guardada, pero el equipo no la ve |
| La red está cerca pero con otra seguridad | El SSID existe con una seguridad distinta a la configurada |
| Contraseña incorrecta | El punto de acceso rechazó la clave |
| Credenciales rechazadas | El RADIUS rechazó identidad/contraseña, o su certificado no coincide con la CA o el dominio |
| Certificado rechazado, vencido o ilegible | El certificado no se pudo instalar o EAP‑TLS fue rechazado |
| No soportada en esta versión de Android | Esa seguridad no existe en ese Android o en ese chip |
| Android no la guardó | El sistema rechazó la red; el agente lo reintenta cada 5 minutos |
| Guardada, sin conexión automática | Como se pidió |

Además lista los bloqueos en vigor, los que esa versión de Android no tiene y, si la salvaguarda actuó, hasta cuándo.

## Qué API usa el agente según la versión de Android

| Qué | Android 6–9 (API 23–28) | Android 10 (29) | Android 11–12 (30–32) | Android 13+ (33+) |
|---|---|---|---|---|
| Guardar redes (Device Owner) | `WifiConfiguration` + `WifiManager.addNetwork` | igual, con `KeyMgmt.SAE` / `KeyMgmt.OWE` | igual, con `setSecurityParams(SECURITY_TYPE_*)` | igual |
| 802.1X | `WifiEnterpriseConfig` | igual | igual; desde Android 12 la clave EAP‑TLS se usa por alias (`setClientKeyPairAlias` + `grantKeyPairToWifiAuth`) | igual |
| Certificados | `installCaCert`, `installKeyPair` | igual | igual | igual |
| Bloquear redes gestionadas | ajuste global `wifi_device_owner_configs_lockdown` | igual | `setConfiguredNetworksLockdownState` | igual |
| Cerrar ajustes de Wi‑Fi | `DISALLOW_CONFIG_WIFI` | igual | igual | igual |
| Impedir agregar / interruptor | — | — | — | `DISALLOW_ADD_WIFI_CONFIG`, `DISALLOW_CHANGE_WIFI_STATE` |
| Lista de SSID | — | — | — | `setWifiSsidPolicy` |
| Seguridad mínima | — | — | — | `setMinimumRequiredWifiSecurityLevel` |
| Proxy | — (API 26+: `setHttpProxy`) | `setHttpProxy` | igual | igual |
| IP fija | campos ocultos de `WifiConfiguration` | igual | igual | `setIpConfiguration` (API pública) |
| MAC | siempre la del dispositivo | campo oculto `macRandomizationSetting` | igual (rechazado en el emulador de Android 11) | `setMacRandomizationSetting` (API pública) |
| Medida | campo oculto `meteredOverride` (rechazado en el emulador de Android 9) | igual | igual (aplicado en Android 11) | igual (aplicado en Android 15) |

**Por qué `WifiConfiguration` y no `WifiNetworkSuggestion`.** `WifiConfiguration`/`addNetwork` está obsoleta para apps
normales desde Android 10, pero sigue siendo la API de un Device Owner y la única que: guarda la red sin preguntar al
usuario, deja quitarla después, permite proxy/IP fija, y queda protegida por el bloqueo de redes gestionadas. Las
sugerencias (`WifiNetworkSuggestion`) dependen de que el usuario las apruebe, no se pueden bloquear y el usuario puede
desactivarlas. El agente solo las usa como respaldo cuando **no** es Device Owner ni Profile Owner en Android 10+;
en ese caso la ficha del equipo marca la red «como sugerencia».

Las opciones marcadas como «campo oculto» no tienen API pública en esa versión: el agente lo intenta y, si Android lo
rechaza, guarda la red sin la opción y lo reporta (no hay fallo silencioso).

Dos detalles de cómo Android trata las redes de un Device Owner, que el agente resuelve solo:

- Una red recién guardada queda **deshabilitada para conexión automática** hasta que Android acepta `enableNetwork`, y
  lo rechaza mientras el radio está arrancando. El agente espera al radio antes de guardar y, cada minuto, habilita las
  redes gestionadas que sigan deshabilitadas.
- Para saber si una red está al alcance hace falta un escaneo reciente: el agente pide uno al aplicar la política y
  cada 5 minutos. Sin escaneo, la ficha dice «Configurada» en vez de inventar «Fuera de alcance».

## Compatibilidad con agentes anteriores

Los agentes anteriores a esta función (0.8.x–0.9.8) siguen recibiendo las redes WPA2, WEP, abiertas y de transición
como siempre. Las redes WPA3, OWE y 802.1X viajan en un campo aparte que esos agentes ignoran, así que no las guardan
(ni las guardan mal); tampoco aplican los bloqueos nuevos. Al actualizar el agente, las recibe en su siguiente reporte.

Cambio de comportamiento: **Red → Wi‑Fi** ahora controla de verdad el radio (encendido/apagado), como dice la consola.
Antes, «apagado» ponía la restricción `DISALLOW_CONFIG_WIFI`; esa restricción es ahora el bloqueo «Impedir cambiar la
configuración de Wi‑Fi».

## Probar

### En emulador (sin punto de acceso)

El emulador de Android trae una red abierta virtual, `AndroidWifi`. Con un equipo inscrito como Device Owner en el
stack local:

```bash
scripts/wifi-policy-e2e.py --api http://localhost:8088 --serial emulator-5554 --device-id <número del equipo>
```

Crea una CA y un PKCS#12 de prueba con `openssl`, los sube, guarda en la política del equipo una red de cada tipo
(PSK oculta, SAE, transición, OWE, PEAP, TTLS, EAP‑TLS y una con todas las opciones) y comprueba en el teléfono, con
`cmd wifi list-networks`, `dumpsys wifi` y `dumpsys device_policy`, que quedaron con los parámetros correctos, que los
certificados se instalaron, que el bloqueo impide olvidarlas (`cmd wifi forget-network`), que en Android 13+ la lista
de SSID y la seguridad mínima sacan al equipo de una red no permitida, que el radio forzado se repone, que la
salvaguarda actúa al quedarse sin red (unos 3 minutos; `--skip-safeguard` la omite) y que al quitar las redes de la política desaparecen del teléfono con
sus certificados. Usa una política sin redes Wi‑Fi propias: la deja como estaba al terminar.

La consola se prueba aparte, en un navegador sin ventana:

```bash
(cd scripts/shots && npm ci)
DALLYCONTROL_ADMIN_PASSWORD=admin node scripts/wifi-console-e2e.mjs --api http://localhost:8088 [--device <número>]
```

### Con un punto de acceso real

El emulador no puede autenticarse contra WPA2/WPA3/802.1X; eso se confirma con un teléfono y un punto de acceso.

**WPA2 / WPA3.** Un router con WPA2, WPA3 o modo mixto. Agrega la red a la política y en la ficha del equipo debe
pasar a «Conectada». Cambia la contraseña en la política por una errada: en unos minutos debe decir «Contraseña
incorrecta». Aleja el equipo: «Fuera de alcance».

**802.1X con FreeRADIUS** (un computador en la misma red que el punto de acceso):

```bash
# 1. Certificados de prueba (CA, servidor y cliente). En producción usa los de tu PKI.
openssl req -x509 -newkey rsa:2048 -nodes -keyout ca.key -out ca.pem -days 365 -subj "/CN=Prueba CA" \
    -addext "basicConstraints=critical,CA:TRUE"
openssl req -newkey rsa:2048 -nodes -keyout server.key -out server.csr -subj "/CN=radius.prueba.local"
printf 'subjectAltName=DNS:radius.prueba.local\nextendedKeyUsage=serverAuth\n' > server.ext
openssl x509 -req -in server.csr -CA ca.pem -CAkey ca.key -CAcreateserial -out server.pem -days 365 -extfile server.ext
openssl req -newkey rsa:2048 -nodes -keyout client.key -out client.csr -subj "/CN=equipo-01"
printf 'extendedKeyUsage=clientAuth\n' > client.ext
openssl x509 -req -in client.csr -CA ca.pem -CAkey ca.key -CAcreateserial -out client.pem -days 365 -extfile client.ext
openssl pkcs12 -export -inkey client.key -in client.pem -certfile ca.pem -out client.p12 -passout pass:cambiar

# 2. FreeRADIUS en un contenedor: un usuario de prueba y el punto de acceso como cliente.
mkdir -p raddb/certs && cp ca.pem server.pem server.key raddb/certs/
cat > raddb/clients.conf <<'EOF'
client ap { ipaddr = 192.168.1.0/24  secret = secreto-del-ap }
EOF
cat > raddb/authorize <<'EOF'
equipo@prueba.local  Cleartext-Password := "clave-de-prueba"
EOF
docker run --rm -p 1812:1812/udp \
    -v "$PWD/raddb/clients.conf:/etc/raddb/clients.conf" \
    -v "$PWD/raddb/authorize:/etc/raddb/mods-config/files/authorize" \
    -v "$PWD/raddb/certs/ca.pem:/etc/raddb/certs/ca.pem" \
    -v "$PWD/raddb/certs/server.pem:/etc/raddb/certs/server.pem" \
    -v "$PWD/raddb/certs/server.key:/etc/raddb/certs/server.key" \
    freeradius/freeradius-server:latest -X
```

En el punto de acceso: seguridad WPA2‑Enterprise, servidor RADIUS = la IP del computador, puerto 1812, secreto
`secreto-del-ap`. En la consola: sube `ca.pem` (CA) y `client.p12` (cliente, contraseña `cambiar`) y prueba, una por
una, estas redes sobre el mismo SSID:

| Caso | Configuración | Resultado esperado en la ficha |
|---|---|---|
| PEAP/MSCHAPv2 | identidad `equipo@prueba.local`, contraseña `clave-de-prueba`, dominio `radius.prueba.local`, CA | Conectada |
| TTLS/PAP | igual, método TTLS, fase 2 PAP | Conectada |
| EAP‑TLS | método TLS, identidad `equipo-01`, certificado de cliente, dominio y CA | Conectada |
| Contraseña errada | PEAP con otra contraseña | Credenciales rechazadas |
| Dominio errado | PEAP con dominio `otro.local` | Credenciales rechazadas (el equipo rechaza al servidor) |
| CA distinta | PEAP con otra CA subida | Credenciales rechazadas (el equipo rechaza al servidor) |

La salida `-X` de FreeRADIUS muestra cada intento (`Access-Accept` / `Access-Reject`); en los dos últimos casos no llega
a haber `Access-Accept` porque es el teléfono quien corta al no poder validar el certificado del servidor.

**Bloqueos en un teléfono.** Con «Bloquear las redes gestionadas», en Ajustes → Wi‑Fi la red gestionada no ofrece
«Olvidar» ni editar. Con «Solo las redes gestionadas…» (Android 13+), al tocar otra red Android muestra que el
administrador no lo permite. Con «Impedir agregar redes», «Agregar red» está desactivado.
