# Acceso: túnel de Cloudflare con réplica, y ruta directa

El servicio se publica por un **túnel de Cloudflare**: el contenedor `cloudflared` abre conexiones salientes
hacia Cloudflare y le entrega las peticiones al borde (`caddy:80`). No hay puertos abiertos en el servidor.

Con un solo conector, reiniciarlo corta el acceso entre 10 y 20 segundos, y si se cuelga no hay otro camino.
Dos formas de quitar ese punto único de falla, que se pueden combinar.

## 1. Segundo conector del mismo túnel (réplica)

Cloudflare admite varios conectores por túnel: cada uno mantiene cuatro conexiones con dos centros de datos
de Cloudflare, y Cloudflare envía cada petición a un conector que esté conectado. Si uno cae, usa los demás.
No hay nada que configurar en el panel: basta arrancar otro `cloudflared` **con el mismo token**.

### En el mismo servidor

Protege de un conector colgado y permite actualizar o reiniciar un conector sin corte.

```ini
# .env
COMPOSE_PROFILES=cloudflare,cloudflare-replica,remote,monitor,backup
MONITOR_TUNNEL_URLS=http://cloudflared:20241/ready,http://cloudflared-2:20241/ready
```

```bash
docker compose up -d cloudflared-2     # arranca la réplica; el conector original no se toca
```

En el panel de Cloudflare (Zero Trust → Networks → Tunnels → su túnel) deben verse **2 conectores** (8
conexiones). El monitor avisa si alguno pierde sus conexiones.

Reiniciar o actualizar los conectores sin corte, de uno en uno:

```bash
scripts/deploy.sh --tunnel             # o a mano:
docker compose up -d --no-deps cloudflared-2 && sleep 15 && docker compose up -d --no-deps cloudflared
```

> **La primera vez** que se despliega esta versión, la orden de `cloudflared` cambia (se le agrega
> `--metrics` para que el monitor pueda consultarlo), y Docker lo recreará la próxima vez que se le pida.
> Para que ese reinicio no corte el acceso: arranque primero `cloudflared-2` como arriba, compruebe en el
> panel que está conectado, y después recree `cloudflared`.

### En otro servidor (redundancia real)

Un segundo conector en el mismo servidor no protege de la caída del servidor. Para eso el conector de
reserva debe estar en **otra máquina que también pueda atender**: es el servidor en espera de
`ALTA-DISPONIBILIDAD.md`. Allí el conector se arranca solo en la conmutación (si arrancara antes, Cloudflare
le enviaría parte del tráfico a un servidor que no está sirviendo).

## 2. Ruta directa, sin túnel

Sirve como modo principal alternativo o como camino de emergencia si el problema es el túnel o Cloudflare.
El borde publica 80/443 y obtiene solo su certificado de Let's Encrypt.

Requisitos: una IP pública para el servidor, los puertos 80 y 443 abiertos hacia él, y un registro DNS que
apunte a esa IP.

```ini
# .env
COMPOSE_FILE=docker-compose.yml:docker-compose.domain.yml
COMPOSE_PROFILES=remote,monitor,backup          # sin `cloudflare`
SITE_ADDRESS=mdm.ejemplo.com
ACME_EMAIL=infra@ejemplo.com
CF_IP_HEADER_STRIP=CF-Connecting-IP
```

```bash
docker compose up -d
```

Diferencias con el túnel:

| | Túnel | Directo |
|---|---|---|
| Puertos abiertos en el servidor | ninguno | 80 y 443 |
| Certificado TLS | Cloudflare | Let's Encrypt, automático en Caddy |
| Protección DDoS / WAF | Cloudflare | la que tenga el proveedor de red |
| Depende de Cloudflare | sí | no |
| En un servidor compartido | no choca con nadie | **80/443 solo pueden ser de un producto**: si otro ya los usa, hace falta un proxy común delante o una IP propia |

`CF_IP_HEADER_STRIP=CF-Connecting-IP` es obligatorio en modo directo: sin Cloudflare delante, ese
encabezado lo puede falsificar cualquiera, y el borde debe descartarlo.

### Como camino de emergencia

Si el servidor comparte los puertos 80/443 con otros productos, la ruta directa no se puede encender en un
minuto. Dos opciones realistas, a preparar **antes** de necesitarlas:

- **Nombre alterno directo.** Un segundo nombre (por ejemplo `mdm-directo.ejemplo.com`) que apunte a la IP
  del servidor y se sirva por el proxy que ya ocupa el 443 hacia el borde de DallyControl. Los equipos
  siguen usando el nombre normal; en una emergencia se cambia el DNS del nombre normal (registro sin proxy
  de Cloudflare, «nube gris») hacia la misma IP. El cambio tarda lo que dure el TTL del registro: déjelo en
  5 minutos.
- **Solo consola.** Si lo urgente es que los administradores entren, un túnel SSH basta mientras se resuelve
  el túnel: `ssh -L 8443:localhost:<puerto-del-borde> servidor` (requiere publicar el borde en un puerto de
  loopback con un archivo de compose adicional).

Los equipos Android tienen grabada la dirección `BASE_URL` del enrolamiento: cualquier ruta alterna debe
responder por **ese mismo nombre**, o no la usarán.

## Diagnóstico rápido

| Síntoma | Comprobación | Causa probable |
|---|---|---|
| Página de error de Cloudflare 1033 / 530 | `docker compose ps cloudflared`; panel: 0 conectores | Conector detenido o token inválido |
| Error 502 de Cloudflare; el registro de cloudflared dice `connection refused` | `docker compose ps caddy` | El borde está reiniciando o caído |
| `/healthz/edge` responde y `/healthz/deep` no | `docker compose ps server postgres` | Servidor o base de datos |
| Todo responde desde el servidor y nada desde fuera | `docker compose logs --tail 50 cloudflared` | Túnel, DNS o Cloudflare |

Pasos de recuperación: `RUNBOOK.md`, «Caída del túnel».

## Qué se probó y qué no

Probado en el entorno local: los archivos de compose con los perfiles `cloudflare` y `cloudflare-replica` son
válidos; `cloudflared` espera a que el borde esté saludable; el monitor detecta un conector sin conexiones
(simulado) y avisa, y avisa la recuperación.

**No se pudo probar fuera de producción**, porque exige el token real del túnel: que Cloudflare reparta el
tráfico entre los dos conectores y que reiniciar uno no corte el acceso. Es el comportamiento documentado
por Cloudflare para conectores múltiples; verifíquelo al activarlo con `scripts/measure-downtime.sh
https://<dominio>/healthz/deep 120` mientras reinicia un conector.
