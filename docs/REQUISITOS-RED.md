# Requisitos de red para los equipos administrados (DallyControl)

Este documento es para el área de redes / seguridad del cliente. Describe qué necesita un equipo Android
administrado para mantenerse conectado al servidor de DallyControl, cómo comprobarlo desde cualquier red y cómo leer
en la consola la causa de un corte.

## 1. Qué tráfico genera el agente

Todo el tráfico **sale del equipo** hacia el servidor. No se abre ningún puerto de entrada hacia la red del cliente.

| Uso | Protocolo | Destino | Puerto |
|-----|-----------|---------|--------|
| Reporte del equipo y recepción de órdenes | HTTPS (TLS 1.2 o superior) | `https://<dominio-del-servidor>/rest/public/agent/v1/…` | 443/TCP |
| Canal en vivo (aviso inmediato de órdenes) | WebSocket seguro (WSS) | `wss://<dominio-del-servidor>/agent/ws/…` | 443/TCP |
| Descarga de aplicaciones y actualizaciones | HTTPS | el mismo dominio | 443/TCP |
| Resolución de nombres | DNS | el DNS de la red | 53 |

`<dominio-del-servidor>` es el dominio que aparece en la consola (el mismo con el que se inscriben los equipos). Si
el servicio tiene una **dirección de respaldo** (ver sección 5), aplica lo mismo a ese segundo dominio.

## 2. Requisitos

1. **Salida al puerto 443/TCP** hacia el dominio del servidor, desde la red Wi‑Fi corporativa y desde datos móviles.
   Se recomienda permitir por **nombre de dominio**, no por dirección IP: el servicio se publica detrás de una red de
   distribución (Cloudflare) y sus direcciones IP cambian.
2. **Sin inspección TLS/SSL** (también llamada *SSL inspection*, *HTTPS decryption* o *deep packet inspection*) para
   ese dominio. El agente solo confía en autoridades certificadoras públicas; si el firewall o proxy reemplaza el
   certificado por uno propio, el agente rechaza la conexión y el equipo aparece sin conexión mientras esté en esa red.
   **Acción:** agregar el dominio a la lista de exclusión de inspección TLS.
3. **WebSocket permitido** (cabeceras `Upgrade: websocket` / `Connection: Upgrade`, respuesta `101`) hacia el
   dominio. Sin WebSocket el equipo sigue reportando por HTTPS, pero las órdenes llegan con minutos de demora en
   lugar de segundos.
4. **Conexiones largas sin corte**: el canal en vivo permanece abierto y envía un latido cada 50 segundos. Los
   tiempos de inactividad del firewall/proxy para ese dominio deben ser de **al menos 120 segundos**.
5. **DNS**: el dominio debe resolver desde la red (no bloquearlo ni redirigirlo en filtros de contenido).
6. **Sin proxy con autenticación** para ese dominio: el agente no usa credenciales de proxy. Si la red exige un
   proxy, el dominio debe salir de forma directa o por un proxy transparente sin autenticación.
7. **Portal cautivo**: en redes de invitados, el equipo no se conecta hasta que alguien acepta el portal. Para
   equipos dedicados use una red sin portal o registre los equipos por dirección MAC.

## 3. Cómo comprobarlo desde una red

En el repositorio se incluye `scripts/prueba-red.sh` (requiere `bash`, `curl` y `openssl`; funciona en macOS, Linux y
en Windows con Git Bash o WSL). Se ejecuta desde un computador conectado **a la red que se quiere validar**:

```bash
scripts/prueba-red.sh https://<dominio-del-servidor>
```

Verifica, en orden: DNS, puerto 443, certificado TLS (quién lo emite), respuesta HTTPS del servidor de DallyControl y
*upgrade* a WebSocket. Termina con `RESULTADO: la red cumple los requisitos.` (código de salida 0) o con la lista de
verificaciones que fallaron y qué cambiar.

### Detectar inspección TLS

1. Ejecute el script desde una red **sin filtros** (por ejemplo, compartiendo datos móviles) y anote la línea
   `Emitido por:` y la `Huella SHA-256`.
2. Ejecútelo desde la **red corporativa** indicando la referencia:

   ```bash
   scripts/prueba-red.sh https://<dominio-del-servidor> --emisor "Google Trust Services"
   # o, más estricto, con la huella del certificado tomada en el paso 1:
   scripts/prueba-red.sh https://<dominio-del-servidor> --huella "AB:CD:…"
   ```

Si el emisor es un producto de seguridad (Fortinet/FortiGate, Zscaler, Palo Alto, Sophos, Check Point, etc.) o no
coincide con la referencia, la red está inspeccionando TLS y hay que excluir el dominio. (La huella puede cambiar
cuando el certificado se renueva, cada pocas semanas; el emisor es la comparación estable.)

### Comprobación manual equivalente

```bash
# Certificado que presenta la red (ver "issuer"):
openssl s_client -connect <dominio>:443 -servername <dominio> </dev/null | openssl x509 -noout -issuer -subject
# HTTPS: debe responder 200 con un JSON {"status":"ERROR",...} (el servidor respondió; no una página de bloqueo):
curl -i -X POST -H 'Content-Type: application/json' -d '{}' https://<dominio>/rest/public/agent/v1/checkin
# WebSocket: debe responder "HTTP/1.1 101":
curl -i --http1.1 -H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 13' \
     -H 'Sec-WebSocket-Key: ZGFsbHljb250cm9sLXJlZA==' https://<dominio>/agent/ws/prueba-red
```

## 4. Cómo se ve en la consola

### Estado del equipo

| Estado | Significado |
|--------|-------------|
| **En línea** | Tiene el canal en vivo abierto o reportó en los últimos 20 minutos. |
| **En reposo** | Lleva más de 20 minutos sin reportar, pero hay una causa conocida y normal: *Equipo apagado* (se apagó de forma ordenada; hasta 72 h), *Batería agotada* (su último reporte fue con 5 % o menos, sin cargar) o *Pantalla apagada* (estaba guardado; hasta 16 h). |
| **Sin conexión** | Lleva más de 20 minutos sin reportar y nada lo explica (estaba en uso), o superó los plazos anteriores. |

Las **alertas de desconexión** y los reportes no cambian: siguen contando el tiempo sin reportar.

### Última falla de conexión (detalle del dispositivo)

En las versiones del agente posteriores a la 0.9.8, cada equipo guarda la última falla de red y la envía en su siguiente reporte. En
el detalle del dispositivo, la tarjeta **Última falla de conexión** muestra:

- **Falla**: DNS, certificado TLS rechazado, tiempo de espera agotado, conexión rechazada/cortada, respuesta HTTP
  con su código, o WebSocket rechazado con su código.
- **Red del equipo** en ese momento: Wi‑Fi (con el nombre de la red) o datos móviles.
- **Emisor del certificado** que presentó la red, cuando la falla fue de TLS. Si el emisor es el firewall del
  cliente y no una autoridad pública, queda demostrada la inspección TLS.
- **Último corte** y su causa: apagado o reinicio del equipo, sin energía, sin red, agente cerrado o suspendido por
  el sistema, o "había red pero no se pudo llegar al servidor".
- Número de **reconexiones** del canal en vivo y si el equipo está usando la dirección de respaldo.

Los mismos datos quedan en la pestaña de eventos del equipo ("Falla de conexión", "Corte de conexión") con fecha y
hora, para armar la evidencia de un incidente.

| Lo que muestra la consola | Causa habitual en la red | Qué revisar |
|---------------------------|--------------------------|-------------|
| Certificado TLS rechazado, emisor = firewall | Inspección TLS | Excluir el dominio de la inspección |
| WebSocket rechazado (HTTP 403/400/502) | Proxy que no permite *upgrade* | Permitir WebSocket hacia el dominio |
| No se pudo resolver el dominio (DNS) | Filtro DNS o DNS interno | Permitir/resolver el dominio |
| Tiempo de espera agotado / conexión rechazada, solo en una red | Puerto 443 filtrado, Wi‑Fi sin salida a internet | Regla de salida 443; cobertura Wi‑Fi |
| Muchas reconexiones, sin fallas | Cortes por inactividad del firewall, itinerancia entre puntos de acceso | Tiempo de inactividad ≥ 120 s |
| HTTP 407 | Proxy con autenticación | Salida directa para el dominio |

## 5. Dirección de respaldo (opcional)

El servidor puede entregar a los agentes una o más direcciones de respaldo (por ejemplo, un dominio directo al
servidor, sin la red de distribución). El agente las prueba cuando la dirección principal falla de forma repetida
(4 fallas seguidas durante al menos dos minutos, con red disponible), recuerda la elección aunque se reinicie y vuelve
a intentar la principal cada 15 minutos. Solo se aceptan direcciones `https`.

Si su red filtra por dominio, permita también el dominio de respaldo con los mismos requisitos de la sección 2.

*Para el operador del servidor:* se configura con la variable de entorno `AGENT_BACKUP_URLS` (lista separada por
comas) en el `.env` del despliegue, o el parámetro `agent.backup.urls` en instalaciones sin Docker. El dominio de
respaldo debe servir la misma aplicación con un certificado público válido. Los agentes reciben la lista en su
siguiente reporte; los de la versión 0.9.8 o anterior la ignoran.

## 6. Comportamiento del agente (para dimensionar)

- Mantiene un WebSocket abierto de forma permanente (latido cada 50 s) y reporta por HTTPS al menos cada 5 minutos.
- Ante un cambio de red (Wi‑Fi ↔ datos, cambio de punto de acceso, salida de una zona sin cobertura) reconecta de
  inmediato; si el servidor no responde, reintenta a los 1, 2, 4, 8 y luego cada 15 segundos.
- Si pasan 8 minutos sin un reporte exitoso habiendo red, descarta todas las conexiones abiertas y empieza de nuevo.
- Consumo en reposo: del orden de unos pocos kilobytes por minuto.
