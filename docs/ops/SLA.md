# Acuerdo de nivel de servicio (SLA) de DallyControl

> **Estado: PROPUESTA.** Los valores marcados con **[POR CONFIRMAR]** son comerciales y los decide la gerencia
> antes de entregar este documento a un cliente: disponibilidad comprometida, horarios, tiempos, compensaciones
> y nombres de contacto. El resto describe cómo funciona hoy el servicio y cómo se mide.

## 1. Alcance

Este acuerdo cubre el servicio DallyControl en la nube que opera el proveedor para el cliente:

- la **consola web** de administración,
- la **API** con la que se comunican los equipos Android (registro, reporte periódico, políticas, comandos,
  descarga de aplicaciones),
- el **canal de aviso inmediato** a los equipos (WebSocket),
- el **control remoto** de pantalla, cuando está contratado.

No cubre los equipos Android, sus redes móviles o Wi-Fi, ni servicios de terceros de los que el servicio no
depende para funcionar (ver «Exclusiones»).

## 2. Disponibilidad comprometida

| Concepto | Valor |
|---|---|
| Disponibilidad mensual objetivo | **99,5 %** **[POR CONFIRMAR]** |
| Tiempo máximo sin servicio que eso permite | 3 h 36 min en un mes de 30 días |
| Periodo de medición | mes calendario, hora de Colombia (UTC−5) |
| Horario cubierto | 24 horas, todos los días |

Referencia para decidir el valor:

| Objetivo | Tiempo sin servicio permitido al mes | Qué exige |
|---|---|---|
| 99,0 % | 7 h 12 min | Infraestructura actual |
| **99,5 %** | 3 h 36 min | Infraestructura actual, con monitoreo, respaldos y despliegue sin corte activos |
| 99,9 % | 43 min | Servidor en espera con réplica de la base de datos y segundo conector del túnel en otro servidor |

El servicio corre hoy en **un solo servidor**. Mientras no exista el servidor en espera, una falla del equipo
físico o de su centro de datos puede tardar horas en resolverse; por eso no se recomienda comprometer 99,9 %
con la infraestructura actual.

### Cómo se mide

- Un monitor automático consulta **cada minuto**, desde fuera, la dirección pública del servicio
  (`/healthz/deep`), que solo responde «correcto» si la consola, la API **y la base de datos** funcionan.
- Cada consulta queda guardada. La disponibilidad del mes es:

  `consultas correctas ÷ consultas realizadas × 100`

- El cliente puede ver el resultado en cualquier momento en la **página de estado**: `https://<dominio>/estado`
  (estado actual, disponibilidad de los últimos 90 días y por mes, incidentes con hora de inicio y duración).
- Como segunda fuente independiente se usa un monitor externo (Uptime Kuma). Si las dos fuentes difieren,
  se toma la más desfavorable para el proveedor.

### Qué cuenta como «sin servicio»

Un minuto cuenta como sin servicio cuando la consulta de ese minuto falla. No cuentan:

- las ventanas de mantenimiento programado anunciadas (sección 3),
- las causas excluidas (sección 8).

## 3. Mantenimiento

| Concepto | Valor |
|---|---|
| Ventana de mantenimiento programado | domingos de 2:00 a 5:00 a. m. (hora de Colombia) **[POR CONFIRMAR]** |
| Aviso previo | 48 horas, por correo al contacto del cliente **[POR CONFIRMAR]** |
| Máximo de mantenimiento con corte | 2 horas al mes **[POR CONFIRMAR]** |
| Mantenimiento de emergencia (seguridad) | sin ventana; se avisa en cuanto se decide |

Las **actualizaciones normales del producto no necesitan ventana**: se aplican con un relevo del servidor y
no interrumpen el servicio (medido en pruebas con el servidor sin saturar: ninguna petición fallida; cada equipo pierde el canal de
aviso inmediato unos 3 segundos en total y se reconecta solo; los usuarios de la consola pueden tener que
volver a iniciar sesión). Detalle en `DESPLIEGUE.md`.

Durante un mantenimiento con corte, la consola muestra una página de mantenimiento y los equipos siguen
funcionando con la última política recibida; al terminar se ponen al día solos.

## 4. Severidad de los incidentes

| Nivel | Definición | Ejemplos |
|---|---|---|
| **P1 – Crítico** | El servicio no está disponible o los equipos no pueden comunicarse; afecta a toda la operación. | La consola no abre; ningún equipo reporta; pérdida de datos. |
| **P2 – Alto** | Una función principal no sirve o el servicio está muy degradado; no hay alternativa razonable. | No se pueden instalar aplicaciones; los comandos no llegan; el control remoto no conecta; una parte importante de los equipos no reporta. |
| **P3 – Medio** | Una función secundaria falla o hay una alternativa; afecta a pocos usuarios o equipos. | Un reporte no se genera; un equipo concreto no aplica su política; lentitud ocasional. |
| **P4 – Bajo** | Consulta, duda de uso, mejora o defecto menor sin impacto en la operación. | Texto incorrecto; solicitud de una función nueva. |

El proveedor asigna el nivel al recibir el caso y puede cambiarlo, explicando por qué, cuando se conoce el
impacto real.

## 5. Tiempos de respuesta y de solución

«Respuesta» es el primer contacto de una persona del proveedor que confirma que el caso se está atendiendo.
«Solución» es el restablecimiento del servicio, aunque sea con una medida provisional.

| Nivel | Cobertura | Primera respuesta | Informes de avance | Solución o medida provisional |
|---|---|---|---|---|
| **P1** | 24 h, todos los días **[POR CONFIRMAR]** | 30 minutos **[POR CONFIRMAR]** | cada 60 minutos | 4 horas **[POR CONFIRMAR]** |
| **P2** | 24 h, todos los días **[POR CONFIRMAR]** | 2 horas **[POR CONFIRMAR]** | cada 4 horas | 8 horas hábiles **[POR CONFIRMAR]** |
| **P3** | horario hábil | 1 día hábil **[POR CONFIRMAR]** | al cambiar de estado | 5 días hábiles **[POR CONFIRMAR]** |
| **P4** | horario hábil | 2 días hábiles **[POR CONFIRMAR]** | al cambiar de estado | según plan de versiones |

Horario hábil: lunes a viernes de 8:00 a. m. a 6:00 p. m., hora de Colombia, sin festivos **[POR CONFIRMAR]**.

Los incidentes P1 y P2 que detecta el monitoreo automático se atienden **sin esperar a que el cliente los
reporte**: la alerta llega al personal de guardia en un máximo de dos minutos desde la falla.

Todo incidente P1, y los P2 que superen su tiempo de solución, terminan con un **informe posterior al
incidente** entregado al cliente en un máximo de 5 días hábiles **[POR CONFIRMAR]**: qué pasó, por cuánto
tiempo, causa, y qué se cambió para que no se repita.

## 6. Canales de soporte y escalamiento

| Canal | Uso | Dato |
|---|---|---|
| Correo de soporte | Todos los niveles; deja registro | **[POR CONFIRMAR]** |
| Teléfono o WhatsApp de guardia | P1 y P2, a cualquier hora | **[POR CONFIRMAR]** |
| Página de estado | Consultar si hay un incidente en curso | `https://<dominio>/estado` |

Para acelerar la atención, el reporte debe incluir: qué no funciona, desde cuándo, a cuántos equipos o
usuarios afecta y, si aplica, el número de un equipo de ejemplo.

Escalamiento cuando no se cumple un tiempo de esta tabla:

| Paso | Cuándo | Responsable |
|---|---|---|
| 1 | Al abrir el caso | Ingeniero de guardia **[POR CONFIRMAR]** |
| 2 | P1 sin respuesta en 30 min, o sin solución en 2 h | Líder técnico **[POR CONFIRMAR]** |
| 3 | P1 sin solución en 4 h, o P2 sin solución en su plazo | Gerencia **[POR CONFIRMAR]** |

## 7. Respaldo y recuperación de datos

| Concepto | Compromiso | Cómo se cumple |
|---|---|---|
| **RPO** (máxima pérdida de datos ante un desastre) | 24 horas **[POR CONFIRMAR]** | Respaldo diario completo, verificado, con copia fuera del servidor. Con servidor en espera: segundos. |
| **RTO** (tiempo máximo para restablecer el servicio tras un desastre) | 4 horas **[POR CONFIRMAR]** | Restauración documentada y probada. Con servidor en espera: 15 minutos. |
| Retención de respaldos | 30 diarios, 8 semanales, 12 mensuales | Automática. |
| Prueba de restauración | Semanal, automática | Cada domingo el respaldo se restaura en una base temporal y se valida. |
| Antes de cada actualización | Respaldo verificado | Lo hace el procedimiento de despliegue; si falla, no se actualiza. |

Mediciones de referencia del procedimiento (base de datos de prueba; el tiempo real crece con el tamaño):
restauración completa de base de datos y archivos con el servicio de vuelta en 20 segundos; conmutación al
servidor en espera en 21 segundos de trabajo técnico (detalle en `RESPALDOS.md` y `ALTA-DISPONIBILIDAD.md`).

Los respaldos que salen del servidor viajan y se guardan **cifrados**.

## 8. Exclusiones

No cuentan como incumplimiento ni como tiempo sin servicio:

1. Mantenimiento programado y anunciado dentro de los límites de la sección 3.
2. Fallas de la red, la energía o los equipos del cliente, incluidos los equipos Android, sus tarjetas SIM y
   su cobertura.
3. Fallas generalizadas de terceros fuera del control del proveedor: red de Cloudflare, operadores móviles,
   servicios de Google en los que se apoyan los equipos Android, el proveedor del centro de datos.
4. Uso contrario a la documentación, cambios hechos por el cliente en la configuración, o cargas fuera de lo
   acordado (número de equipos contratados **[POR CONFIRMAR]**).
5. Suspensión del servicio por falta de pago o por orden de autoridad.
6. Fuerza mayor.
7. Funciones marcadas como «beta» o «experimental».

## 9. Compensación por incumplimiento **[POR CONFIRMAR]**

Propuesta, sobre la mensualidad del mes afectado:

| Disponibilidad medida en el mes | Crédito |
|---|---|
| Menor al objetivo y mayor o igual a 99,0 % | 5 % |
| Menor a 99,0 % y mayor o igual a 98,0 % | 10 % |
| Menor a 98,0 % | 20 % |

El crédito se solicita dentro de los 30 días siguientes al cierre del mes y se aplica a la factura siguiente.
Es la única compensación por incumplimiento de disponibilidad.

## 10. Revisión

Este acuerdo se revisa cada 6 meses **[POR CONFIRMAR]** o cuando cambie la infraestructura (por ejemplo, al
activar el servidor en espera, que permite subir el objetivo a 99,9 %).

---

### Para la gerencia: qué respalda cada cifra propuesta

| Cifra | De dónde sale |
|---|---|
| 99,5 % | Un solo servidor compartido. Los dos incidentes registrados (11 min y el corte de un despliegue) habrían consumido menos del 10 % del margen mensual. |
| Respuesta P1 en 30 min | El monitor avisa en 2 minutos o menos; el resto es disponibilidad real de la persona de guardia. **Requiere definir quién está de guardia y cómo se le contacta de noche.** |
| RPO 24 h | Respaldo diario. Bajarlo exige el servidor en espera (replicación continua). |
| RTO 4 h | Restaurar en un servidor nuevo: aprovisionarlo, instalar, restaurar. Medido en pruebas: la restauración en sí tarda segundos a minutos; el resto es conseguir el servidor. |
| Ventana dominical | Es el horario de menor uso habitual; confirmar con la operación del cliente. |
