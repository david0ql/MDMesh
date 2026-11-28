# Manuales: qué falta completar

Los manuales (`docs/manuales/src/*.html` → PDF con `docs/manuales/build.sh`) documentan lo que está en la rama
`integration/mobicontrol`. Las funciones que viven en ramas aún no fusionadas se describen con la marca
**«Llega con…»** (clase `pend`), en texto y **sin capturas**: no se inventaron pantallas. Esta lista dice qué
completar al fusionar cada rama y qué quedó por verificar.

## Cómo regenerar

```bash
docker compose --env-file docker/dev.env up -d --build && scripts/dev-seed.sh
scripts/manual-demo-data.sh                                   # flota de demostración (datos ficticios)
(cd scripts/shots && npm ci)                                  # una vez
node scripts/manual-capturas.mjs --web http://localhost:8088 [--apk <un .apk pequeño de prueba>]
docs/manuales/build.sh                                        # PDF + copia e índice para la página Ayuda
```

- La marca (producto, empresa, edición) está **solo** en `docs/manuales/marca.env`; el logo, en `_plantilla/logo.jpeg`.
- `build.sh` copia los PDF y `indice.json` a `web/public/manuales/` (lo que sirve el menú **Ayuda**). Hay que
  confirmar ambos lugares en el mismo commit.
- Si se renombra el `id` de una sección, revisar `web/src/data/help.ts` (mapa pantalla → sección del «?»).

## Por rama sin fusionar

| Rama | Tema (criterio) | Secciones a completar | Capturas a tomar |
|---|---|---|---|
| `worktree-agent-a1ba39633b6d354be` | Quiosco reforzado (MDM-10) | Admin §9 «Quiosco»: contraseña de salida obligatoria («Contraseña de salida del quiosco», botones «Ver»/«Generar»), espera tras intentos fallidos, «Solo remoto», estado del refuerzo en la ficha y «Pedir autorización en el teléfono». Problemas §6: eventos `kioskExitFailed`, `kioskEscapeBlocked`, `kioskLockdownGap`. Admin §10: «Depuración USB y opciones de desarrollador». | Editor de política (grupo Seguridad), diálogo «Entrar en quiosco», ficha con el aviso de refuerzo. |
| `worktree-agent-ac94f56bcc9a248d1` | Permisos por app (MDM-06) | Admin §10 «Permisos de las aplicaciones»: «Permisos de apps» (5 opciones), sección «Permisos por app» (tabla permiso/regla/estado, accesos especiales), pestaña «Permisos» de la ficha y CSV. Problemas: aviso de sensores en Android 12+. | Sección «Permisos por app» de la política; pestaña «Permisos» del dispositivo. |
| `worktree-agent-ac51c699aee29f387` | Hardware y puertos (MDM-12) | Admin §10: tabla de las 23 funciones con su Android mínimo, «Otras restricciones de Android», pestaña «Restricciones» de la ficha. Problemas: columna «Resultado en el equipo» (Pendiente, No soportado, Sigue bloqueado…). | Sección «Hardware y puertos»; pestaña «Restricciones». |
| `worktree-agent-ab3f5e6e882397d47` | Wi-Fi corporativo (MDM-13) | Admin §11 «Redes Wi-Fi»: seguridades (WPA3, 802.1X, OWE), «Opciones» por red, «Certificados Wi‑Fi», «Bloqueos de Wi‑Fi» y salvaguarda, tarjeta «Wi‑Fi gestionado». Arquitectura §4: RADIUS de la red del cliente. Operación §5: incluir `wifi-certs.key` en los respaldos. Notas: Red → Wi‑Fi pasa a controlar el radio. | Redes Wi‑Fi de la política (red 802.1X y certificados); tarjeta «Wi‑Fi gestionado». |
| `worktree-agent-a3092d2a937cb8f23` | Soporte remoto con voz (MDM-08) | Admin §15: chips «Remoto listo»/«Falta autorización», barra de sesión (Calidad, Captura, Grabar, Portapapeles, Archivos, Llamar), chat, menú «Sesiones de soporte», política «Soporte remoto». Problemas §8: motivos de fin de sesión y de llamada. Arquitectura §2 y §5: WebSocket de soporte en el mismo dominio, sin STUN/TURN; micrófono permitido en el borde. Menú del §1 (entrada nueva). | Pestaña «Remoto» con sesión en curso; «Sesiones de soporte»; política «Soporte remoto». |
| `worktree-agent-a0c1e278585a25040` | FRP y borrado remoto (MDM-03, MDM-09) | Enrolamiento §9: cuentas por empresa/carpeta/política, diálogo «Cuentas que pueden reactivar un equipo», estados «FRP activa/pendiente/inactiva». Admin §14: «Modo perdido», diálogo «Borrado remoto» con sus opciones. Problemas: motivos de «FRP inactiva». | Política «Restablecimiento de fábrica»; «Protección contra restablecimiento y robo» en Control; diálogos «Modo perdido» y «Borrado remoto». |
| `worktree-agent-aa96983bce1627e55` | Despertador FCM (MDM-07) | Arquitectura §2 «Despertador» y §4 (salida del servidor a los servicios de Google; qué necesita el teléfono: **no está enumerado en el documento de la rama**). Operación §2: variables `FCM_*`. Admin §19: «Despertador push (FCM)» y, en la ficha, «Alcance de órdenes»; política «Sondeo de respaldo». Problemas §4: estados de «Mensajes push». | Ajustes «Despertador push (FCM)»; tarjeta «Alcance de órdenes». |
| `worktree-agent-a2698adb390221324` | Conectividad, último ajuste (MDM-02) | Problemas §3 y Admin §6: chip «En reposo N» en la lista. Arquitectura §4: la conmutación al respaldo pasa a «4 fallas en 2 minutos». Texto «versiones posteriores a la 0.9.8». | Lista de dispositivos con el chip «En reposo». |

Al fusionar: quitar el párrafo `pend` correspondiente, escribir los pasos con los rótulos finales (la depuración USB
aparece en tres ramas con controles distintos: revisar cuál queda), tomar las capturas agregándolas a
`scripts/manual-capturas.mjs`, y pasar el criterio de «por integrar» a «integrado» en las Notas de versión.

## Verificaciones pendientes (no dependen de una fusión)

- **Teléfono físico.** Nada de lo documentado se probó en teléfono físico. La lista por tema está en
  «Notas de versión» §4 y en «Guía de enrolamiento» §10. Al tener resultados por modelo, actualizar esas dos tablas.
- **QR en Android 11 a 17.** La matriz en emulador no se obtuvo (máquina sobrecargada). La consola, además, afirma en
  la pestaña «Por cable (USB)» que el QR no funciona en Android 15 y 16; la guía lo presenta como «puede bloquear».
  Confirmar en físico y unificar el texto de la consola y de la guía.
- **Herramienta «Por cable (USB)».** `/files/DallyControl-Enrolar.zip` no se genera desde el repositorio: lo publica
  el operador. La guía lo dice; si se automatiza, actualizar el capítulo 5 de la guía de enrolamiento.
- **Nombres de los criterios MDM-01…MDM-17.** Las notas usan un título corto por tema; reemplazarlos por el texto
  literal de la matriz del cliente si se quiere citar.
- **SLA.** Todas las marcas `[POR CONFIRMAR]` siguen abiertas (decisión de gerencia). Datos de guardia: Operación §9.
- **Dimensionamiento.** Disco, ancho de banda y consumo por equipo están como «pendiente de medir» (Arquitectura §6).
- **Pantalla del agente.** Los rótulos fijos de la app están en inglés («MANAGEMENT», «DEVICE ID»); el diálogo de
  salida del quiosco también. Los manuales lo dicen tal cual. Si se traducen, actualizar Enrolamiento §8.
- **Textos de la consola que el manual no pudo citar.** Algunos avisos muestran el código del servidor sin traducir
  (`error.agent.*`, `error.group.*`, `error.announcement.*`); la guía de problemas trae una tabla de equivalencias.
- **Capturas con la dirección del stack local.** La de «Inscribir» muestra `localhost` en el pie del QR y un QR de
  demostración; no corresponden a ningún servidor real.
