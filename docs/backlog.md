# Backlog

Estados: `[ ]` pendiente · `[~]` en curso · `[x]` hecha · `[!]` bloqueada (con motivo).
Tomar la primera `[ ]` cuyas dependencias estén `[x]`. Una tarea por PR. Ver reglas en `CLAUDE.md`.

## Fase 0 · Base del repo

- [ ] **T-001 · Scaffold del backend**
  Spring Boot con Java (última LTS) y Maven en `backend/`. Dependencias: Web, Validation, Data JPA, PostgreSQL, Flyway, Security, WebSocket, Actuator, Spring Modulith, springdoc-openapi, Testcontainers. Paquetes por módulo vacíos (`organizations`, `people`, `opportunities`, `messaging`, `channels`, `assignment`, `notifications`). `docker-compose.yml` en la raíz con PostgreSQL y Redis. `.env.example`.
  *Terminado cuando:* `./mvnw verify` pasa con un test de contexto y un test de Spring Modulith (`ApplicationModules.verify()`); `/actuator/health` responde; comandos documentados en `CLAUDE.md`.

- [ ] **T-002 · CI del backend** · depende de T-001
  GitHub Actions: build y tests del backend en cada push y PR.
  *Terminado cuando:* el workflow corre en verde en el PR.

- [ ] **T-003 · Scaffold de la web** · depende de T-002
  React + TypeScript + Vite en `web/`, ESLint, Vitest, cliente de API generado desde el OpenAPI del backend (script documentado). Agregar job de web al CI.
  *Terminado cuando:* `npm run build`, `npm run lint` y `npm test` pasan en CI.

## Fase 1 · Dominio y primer canal

- [ ] **T-010 · Organizaciones y multi-tenant** · depende de T-001
  Entidad `Organization`. Todas las entidades de negocio llevan `organizationId`. Resolución del tenant desde el usuario autenticado. Test que garantiza que una organización no ve datos de otra.

- [ ] **T-011 · Usuarios, operadores y autenticación** · depende de T-010
  `Operator` (rol operador o supervisor, disponibilidad `ONLINE`/`AWAY`/`ON_LEAVE`). Login con JWT. Endpoints de login y "yo".

- [ ] **T-012 · Personas e identidades** · depende de T-010
  `Person` y `ContactIdentity` (canal + id externo). Normalización: teléfono a E.164 (libphonenumber), email en minúsculas. Servicio `resolveOrCreate(identity)` según D-003. Tests de normalización y de resolución.

- [ ] **T-013 · Oportunidades** · depende de T-012
  `Opportunity` con estado, resultado, responsable, fuente y fechas. Reglas de D-002: abrir, reabrir dentro de `reentryWindow`, abrir nueva fuera de ella, cierre automático por `inactivityDaysToClose` (job). Configuración por organización. Tests de cada regla con reloj inyectable.

- [ ] **T-014 · Mensajes y línea de tiempo** · depende de T-013
  `Message` según `docs/vision.md` §4. Unicidad de `externalId` por `ChannelAccount`. Ingesta idempotente. Actualización de estados (`SENT`→`DELIVERED`→`READ`, sin retroceder). Índices para las consultas de ventana. Tests de idempotencia y de estados fuera de orden.

- [ ] **T-015 · Puerto de canales y canal de prueba** · depende de T-014
  Interfaz `ChannelAdapter` (`docs/vision.md` §5), entidad `ChannelAccount`, registro de adaptadores por tipo. Adaptador `SANDBOX` en memoria para tests y desarrollo local.

- [ ] **T-016 · Pipeline de entrada** · depende de T-015
  Flujo entrante completo (`docs/vision.md` §5) usando el canal `SANDBOX`. Publica un evento de dominio "mensaje recibido". Test de punta a punta: mensaje de identidad desconocida → persona nueva + oportunidad + mensaje.

- [ ] **T-017 · Adaptador WhatsApp Cloud API: entrada** · depende de T-016
  Verificación de webhook (GET con `hub.challenge`), validación de firma `X-Hub-Signature-256`, parseo de mensajes y estados. Tests con payloads de ejemplo de la documentación de Meta (en `src/test/resources`). Sin credenciales reales: todo por variables de entorno.

- [ ] **T-018 · Salida y estados de ventana** · depende de T-017
  Envío de texto y plantillas por WhatsApp. Estados de ventana de D-005 calculados desde el último mensaje del cliente (24 h). Envío de texto libre rechazado si la ventana no está `OPEN`. Cliente HTTP de Meta mockeado en tests.

- [ ] **T-019 · Reparto** · depende de T-011, T-013
  Puerto `AssignmentStrategy`. Implementación por defecto según D-006 y D-007 (responsable anterior → reglas → round robin con tope 30 → fallback con aviso). Tests de cada paso.

- [ ] **T-020 · Temporizador de atención** · depende de T-019
  Según D-008: horario de atención por organización, 15 min chat / 4 h email, solo primera respuesta, el bot no cuenta, reasignar y avisar, escalar tras 2 reasignaciones. Scheduler persistido en base. Tests con reloj inyectable.

- [ ] **T-021 · API de bandeja y tiempo real** · depende de T-016, T-019
  Endpoints: listar oportunidades del operador (no leídas primero), línea de tiempo de una persona (paginada), enviar mensaje eligiendo canal, reasignar, marcar leída. WebSocket con eventos de mensaje nuevo, cambio de estado y asignación. Documentado en OpenAPI.

## Fase 2 · Bandeja web

- [ ] **T-030 · Login y layout** · depende de T-003, T-011
- [ ] **T-031 · Lista de oportunidades** · depende de T-030, T-021 · no leídas primero, filtro por estado, actualización en tiempo real.
- [ ] **T-032 · Línea de tiempo** · depende de T-031 · mensajes de todos los canales con su ícono, emisor y estado de entrega.
- [ ] **T-033 · Redactor** · depende de T-032 · elegir canal, mostrar estado de ventana, enviar texto o plantilla según corresponda.

## Fase 3 · Más canales y celular

- [ ] **T-040 · Adaptador Instagram / Messenger** (Meta)
- [ ] **T-041 · Adaptador Telegram** (Bot API)
- [ ] **T-042 · PWA** · instalable, con notificaciones push
