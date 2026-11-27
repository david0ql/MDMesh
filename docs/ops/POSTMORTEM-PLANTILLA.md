# Informe posterior al incidente — plantilla

> Se escribe para todo incidente P1, y para los P2 que superen su tiempo de solución (ver `SLA.md`).
> Plazo: 5 días hábiles desde el cierre. Sin culpables: se describen hechos, decisiones y sistemas, no personas.
> Copie este archivo como `POSTMORTEM-AAAA-MM-DD-titulo-corto.md`.

## Resumen

| | |
|---|---|
| **Título** | Una frase que diga qué falló y para quién |
| **Fecha** | AAAA-MM-DD |
| **Severidad** | P1 / P2 |
| **Duración** | de HH:MM a HH:MM (hora de Colombia) — N minutos |
| **Impacto** | Quién se vio afectado y cómo (usuarios de la consola, equipos, funciones) |
| **Detectado por** | Alerta automática / cliente / personal propio — y cuánto tardó |
| **Estado** | Resuelto / mitigado con pendientes |
| **Autor y revisores** | |

**En dos o tres frases:** qué pasó, por qué, y qué se cambió.

## Impacto

- Tiempo sin servicio y funciones afectadas.
- Número de equipos y usuarios afectados (con la fuente del dato).
- Datos perdidos o inconsistentes: sí / no; cuáles.
- Minutos consumidos del margen mensual del SLA (`/estado`).

## Línea de tiempo

Horas en hora de Colombia. Incluya cuándo empezó, cuándo se detectó, cada decisión y cuándo terminó.

| Hora | Hecho |
|---|---|
| HH:MM | |

Tiempos clave: **detección** (inicio → alguien se entera), **respuesta** (→ alguien actúa),
**recuperación** (→ servicio restablecido).

## Causa

- **Causa directa:** el cambio o la falla que produjo el efecto.
- **Causas de fondo:** por qué fue posible (qué control faltaba o no funcionó). Pregunte «¿por qué?» hasta
  llegar a algo que se pueda cambiar en el sistema o en el proceso.
- **Por qué no se detectó antes:** en la revisión, en las pruebas, en el despliegue, en el monitoreo.

## Qué funcionó y qué no

- Funcionó: …
- No funcionó: …
- Tuvimos suerte en: …

## Acciones

Cada acción con responsable y fecha. Una acción sin responsable no existe.

| # | Acción | Tipo (prevenir / detectar / mitigar) | Responsable | Fecha | Estado |
|---|---|---|---|---|---|
| 1 | | | | | |

## Comunicación

- Qué se le dijo al cliente, cuándo y por qué canal.
- Texto del resumen enviado al cierre.

## Anexos

Registros, gráficos de la página de estado, comandos ejecutados.
