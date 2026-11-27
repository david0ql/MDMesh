#!/usr/bin/env bash
# Prueba de red para DallyControl: verifica, DESDE LA RED DONDE SE EJECUTA, que un equipo puede llegar al servidor.
#
#   1. DNS            el dominio resuelve
#   2. Puerto 443     se abre la conexión TCP
#   3. TLS            el certificado es válido y lo emite una autoridad pública (si lo emite el firewall/proxy
#                     de la red, hay INSPECCIÓN TLS y el agente —que no confía en esa autoridad— no conecta)
#   4. HTTPS          el servidor de DallyControl responde al reporte del agente (no una página de bloqueo)
#   5. WebSocket      la red deja pasar el "upgrade" a WebSocket (canal en vivo de órdenes)
#
# Uso:
#   scripts/prueba-red.sh https://mdm.midominio.com
#   scripts/prueba-red.sh https://mdm.midominio.com --emisor "Google Trust Services"
#   scripts/prueba-red.sh https://mdm.midominio.com --huella AB:CD:...   (huella tomada desde una red sin filtros)
#   scripts/prueba-red.sh https://mdm.midominio.com https://directo.midominio.com   (varias direcciones)
#
# Ejecútelo dos veces: una desde una red sin filtros (datos móviles compartidos) para tomar la referencia, y otra
# desde la red corporativa. Si el emisor o la huella cambian entre las dos, la red corporativa inspecciona TLS.
# Requiere: bash, curl y openssl (macOS, Linux, o Windows con Git Bash / WSL). Sale con código 0 si todo pasa.

set -u

EXPECTED_ISSUER=""
EXPECTED_FP=""
TIMEOUT=10
URLS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --emisor) EXPECTED_ISSUER="${2:-}"; shift 2 ;;
    --huella) EXPECTED_FP="${2:-}"; shift 2 ;;
    --tiempo) TIMEOUT="${2:-10}"; shift 2 ;;
    -h|--help) sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) URLS+=("$1"); shift ;;
  esac
done
if [ ${#URLS[@]} -eq 0 ]; then
  echo "Falta la dirección del servidor. Ejemplo: $0 https://mdm.midominio.com" >&2
  exit 2
fi

# Autoridades de productos que inspeccionan TLS (firewalls, proxies, antivirus). Si el certificado lo emite una de
# estas, la red está abriendo el tráfico.
INSPECTORS='Fortinet|FortiGate|Zscaler|Palo Alto|Sophos|Check Point|Cisco Umbrella|OpenDNS|Netskope|Blue Coat|Symantec Web|Squid|Kaspersky|ESET|Avast|AVG |Bitdefender|Barracuda|WatchGuard|SonicWall|McAfee|Skyhigh|Forcepoint|Websense|Untangle|pfSense|Smoothwall|Lightspeed|iboss|Menlo|Trend Micro|Cloudflare Gateway|Cloudflare for Teams'

FAILS=0
ok()   { printf '  [ OK ]  %s\n' "$*"; }
bad()  { printf '  [FALLA] %s\n' "$*"; FAILS=$((FAILS + 1)); }
note() { printf '          %s\n' "$*"; }

check_url() {
  local url="${1%/}" scheme host port rest
  scheme="${url%%://*}"
  rest="${url#*://}"; rest="${rest%%/*}"
  host="${rest%%:*}"
  if [ "$rest" != "$host" ]; then port="${rest##*:}"; elif [ "$scheme" = "https" ]; then port=443; else port=80; fi

  echo
  echo "== $url =="
  if [ "$scheme" != "https" ]; then
    note "Aviso: la dirección no es https; el agente en producción solo usa https (puerto 443)."
  fi

  # 1. DNS
  local ips=""
  if command -v dig >/dev/null 2>&1; then ips="$(dig +short "$host" 2>/dev/null | grep -E '^[0-9a-fA-F:.]+$' | tr '\n' ' ')"; fi
  if [ -z "$ips" ] && command -v nslookup >/dev/null 2>&1; then
    ips="$(nslookup "$host" 2>/dev/null | awk '/^Address/ && NR>2 {print $NF}' | tr '\n' ' ')"
  fi
  if [ -z "$ips" ] && command -v getent >/dev/null 2>&1; then ips="$(getent hosts "$host" | awk '{print $1}' | tr '\n' ' ')"; fi
  if [ -z "$ips" ] && printf '%s' "$host" | grep -Eq '^[0-9.]+$|^localhost$'; then ips="$host"; fi
  if [ -n "$ips" ]; then ok "DNS: $host -> $ips"; else bad "DNS: no se pudo resolver $host (el DNS de esta red no conoce o bloquea el dominio)"; return; fi

  # 2. TCP
  # curl exit 7 = connection refused/unreachable, 28 = timed out; anything else means the TCP connection opened
  # (a TLS or HTTP problem is judged by the next steps).
  curl -sk -o /dev/null --connect-timeout "$TIMEOUT" --max-time "$TIMEOUT" "$scheme://$host:$port/" 2>/dev/null
  local rc=$?
  if [ "$rc" -ne 7 ] && [ "$rc" -ne 28 ]; then
    ok "Puerto $port: abierto"
  else
    bad "Puerto $port: no se pudo abrir la conexión (salida al puerto $port bloqueada hacia $host)"
    return
  fi

  # 3. TLS
  if [ "$scheme" = "https" ]; then
    local out cert issuer subject fp verify
    out="$(echo | openssl s_client -connect "$host:$port" -servername "$host" 2>&1)"
    cert="$(printf '%s\n' "$out" | sed -n '/-----BEGIN CERTIFICATE-----/,/-----END CERTIFICATE-----/p' | sed -n '1,/-----END CERTIFICATE-----/p')"
    if [ -z "$cert" ]; then
      bad "TLS: no se completó la negociación (la red corta o reemplaza la conexión cifrada)"
      note "$(printf '%s\n' "$out" | grep -iE 'error|alert|refused|reset' | head -2 | tr '\n' ' ')"
      return
    fi
    issuer="$(printf '%s\n' "$cert" | openssl x509 -noout -issuer 2>/dev/null | sed 's/^issuer= *//')"
    subject="$(printf '%s\n' "$cert" | openssl x509 -noout -subject 2>/dev/null | sed 's/^subject= *//')"
    fp="$(printf '%s\n' "$cert" | openssl x509 -noout -fingerprint -sha256 2>/dev/null | sed 's/^.*=//')"
    verify="$(printf '%s\n' "$out" | sed -n 's/^ *Verify return code: *//p' | head -1)"
    note "Certificado para: $subject"
    note "Emitido por:      $issuer"
    note "Huella SHA-256:   $fp"
    if printf '%s' "$issuer" | grep -Eiq "$INSPECTORS"; then
      bad "TLS: el certificado lo emite un equipo de seguridad de la red (INSPECCIÓN TLS). El agente lo rechaza."
      note "Solución: excluir $host de la inspección TLS/SSL en el firewall o proxy."
    elif [ -n "$EXPECTED_ISSUER" ] && ! printf '%s' "$issuer" | grep -Fiq "$EXPECTED_ISSUER"; then
      bad "TLS: el emisor no es el esperado (\"$EXPECTED_ISSUER\"): la red reemplaza el certificado (inspección TLS)"
    elif [ -n "$EXPECTED_FP" ] && [ "$(printf '%s' "$fp" | tr -d ':' | tr 'a-f' 'A-F')" != "$(printf '%s' "$EXPECTED_FP" | tr -d ': ' | tr 'a-f' 'A-F')" ]; then
      bad "TLS: la huella no coincide con la de referencia: la red presenta otro certificado (inspección TLS)"
    elif ! curl -s -o /dev/null --connect-timeout "$TIMEOUT" --max-time "$TIMEOUT" "$url/" 2>/dev/null; then
      bad "TLS: el certificado no es válido para este equipo (${verify:-sin detalle}); probable inspección TLS con una autoridad privada"
    else
      ok "TLS: certificado válido, emitido por una autoridad pública (sin inspección TLS)"
      if [ -z "$EXPECTED_ISSUER$EXPECTED_FP" ]; then
        note "Para comparar desde otra red:  $0 $url --emisor \"$(printf '%s' "$issuer" | sed -n 's/.*O *= *\([^,/]*\).*/\1/p')\""
      fi
    fi
  fi

  # 4. HTTPS: the agent's own endpoint. Without credentials the application answers 200 with an error envelope —
  #    which proves the request reached DallyControl and not a block page.
  local body code
  body="$(curl -sk --connect-timeout "$TIMEOUT" --max-time "$TIMEOUT" -w '\n%{http_code}' -X POST \
          -H 'Content-Type: application/json' -d '{}' "$url/rest/public/agent/v1/checkin" 2>/dev/null)"
  code="$(printf '%s' "$body" | tail -n 1)"
  if [ "$code" = "200" ] && printf '%s' "$body" | grep -q '"status"'; then
    ok "HTTPS: el servidor de DallyControl responde (reporte del agente, HTTP 200)"
  elif [ -z "$code" ] || [ "$code" = "000" ]; then
    bad "HTTPS: sin respuesta del servidor (conexión cortada o tiempo de espera agotado)"
  else
    bad "HTTPS: respondió HTTP $code y no el servidor de DallyControl (página de bloqueo o proxy de la red)"
  fi

  # 5. WebSocket upgrade. Without a device secret the server accepts the upgrade (101) and closes at once: the 101
  #    is what shows the network lets WebSocket through.
  local ws
  ws="$(curl -sk -o /dev/null --http1.1 --connect-timeout "$TIMEOUT" --max-time 5 -w '%{http_code}' \
        -H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 13' \
        -H 'Sec-WebSocket-Key: ZGFsbHljb250cm9sLXJlZA==' "$url/agent/ws/prueba-red" 2>/dev/null)"
  if [ "$ws" = "101" ]; then
    ok "WebSocket: la red permite el canal en vivo (HTTP 101)"
  else
    bad "WebSocket: la red no dejó pasar el upgrade (HTTP ${ws:-sin respuesta}; se esperaba 101)"
    note "Solución: permitir WebSocket (wss) hacia $host en el proxy/firewall. Sin él, las órdenes llegan con minutos de demora."
  fi
}

echo "Prueba de red de DallyControl — $(date '+%Y-%m-%d %H:%M:%S %Z')"
for u in "${URLS[@]}"; do check_url "$u"; done

echo
if [ "$FAILS" -eq 0 ]; then
  echo "RESULTADO: la red cumple los requisitos."
  exit 0
fi
echo "RESULTADO: $FAILS verificación(es) fallaron. Ver docs/REQUISITOS-RED.md."
exit 1
