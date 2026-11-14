# Decisiones

Decisiones tomadas por el dueño del producto. **No re-discutir.** Si algo de acá parece incorrecto, anotarlo en *Provisorias* como propuesta, sin cambiar la decisión.

## Producto

**D-001 · Persona permanente, oportunidades con ciclo de vida** (2026-11-13)
Modelo similar a Salesforce. La persona es permanente y tiene una sola línea de tiempo con todos los mensajes. La oportunidad (episodio) se abre cuando la persona entra con una intención, tiene responsable, estado, fuente y resultado, y se cierra por resultado o por inactividad. Cada mensaje queda asociado a la persona y a la oportunidad abierta en ese momento. Métricas por oportunidad.

**D-002 · Duración de oportunidades configurable** (2026-11-13)
Por organización (o campaña), con dos valores:
- `inactivityDaysToClose`: N días sin mensajes → la oportunidad se cierra sola.
- `reentryWindow`: si la persona vuelve dentro de la ventana, se reabre la misma oportunidad; si vuelve después, se abre una nueva.
Valores de referencia: venta transaccional (plan de ahorro, seguros) reingreso ~3 días, cierre 7–15 días; consultiva (autos, inmuebles) 30–90 días; servicio recurrente: cada pedido es una oportunidad.

**D-003 · Resolución de identidades** (2026-11-13)
Se busca por cualquier dato de contacto conocido (teléfono normalizado E.164, email en minúsculas, id del canal). Si coincide → misma persona. Si no coincide → persona nueva. La revisión de duplicados (sugerida por el sistema, confirmada por una persona) queda para más adelante.

**D-004 · Vista** (2026-11-13)
Vista web propia primero. Después celular (PWA, luego app de tienda), escritorio (la misma web envuelta para mantener el estilo: Electron o Tauri, se decide después) y terminal (TUI).

**D-005 · Restricciones por canal** (2026-11-13)
Patrón de estados de ventana: `OPEN`, `AWAITING_REPLY`, `REQUIRES_TEMPLATE`, `BLOCKED`. Cada adaptador devuelve el estado para esa persona y la UI muestra qué puede hacer el operador (texto libre, solo plantilla o nada).

## Reparto

**D-006 · Sin cola global; reparto por organización** (2026-11-13)
No existe una cola global. Cada organización configura su reparto (estrategia intercambiable) y, si quiere, sus propias colas por equipo o campaña. Por defecto se asigna automáticamente a una persona.

**D-007 · Orden de asignación** (2026-11-13)
1. Si la persona ya tuvo responsable y sigue activo → vuelve a ese responsable.
2. Reglas por campaña, fuente, canal o producto → definen el grupo de operadores.
3. Dentro del grupo → **round robin** entre los disponibles, con **tope de 30 oportunidades abiertas** por operador (si llegó al tope, se lo saltea). Reparto por carga, más adelante.
4. Si no hay nadie disponible → se asigna igual (al de menor carga o a un responsable de guardia) y se avisa al supervisor.
Los operadores tienen disponibilidad: `ONLINE`, `AWAY`, `ON_LEAVE`.

**D-008 · Temporizador de atención** (2026-11-13)
Mismo mecanismo para el tiempo límite de las colas y para la primera respuesta.
- Cuenta solo la respuesta de una persona; el bot no frena el reloj.
- Corre solo en el horario de atención de la organización.
- Por defecto: **chat 15 min, email 4 h**.
- Aplica solo a la primera respuesta de cada oportunidad.
- Al vencer: reasigna y avisa al operador y al supervisor. **Después de 2 reasignaciones, escala al supervisor.**
- Las colas tienen tiempo límite: si nadie toma la oportunidad, se asigna sola.

## Técnica

**D-009 · Arquitectura API primero** (2026-11-13)
Toda la lógica en el backend. Clientes (web, celular, escritorio, terminal, MCP) consumen la misma API: REST documentada con OpenAPI + WebSocket para tiempo real.

**D-010 · Backend en Java** (2026-11-13)
Java (última LTS) + Spring Boot. Monolito modular (Spring Modulith), hexagonal. PostgreSQL + Flyway, Spring Security con JWT, multi-tenant por organización. Temporizadores en un scheduler persistido en base. Tests con JUnit 5 + Testcontainers.

**D-011 · Web en React + TypeScript + Vite** (2026-11-13)

## Forma de trabajo

**D-012 · Tareas en GitHub Issues + Projects** (2026-11-13)
Las tareas y errores son issues del repo, en un Project de GitHub con tablero (Todo / In Progress / Done). Las tareas
con varias entregas se dividen en subissues; cada PR cierra su issue con `Closes #N`. El backlog se migró el
2026-11-13 (#1 a #22, un milestone por fase); `docs/backlog.md` quedó como tabla de equivalencias. El ciclo de un
issue está en el skill `agarrar-issue`.

**D-013 · Agentes con revisor dedicado** (2026-11-13)
Se trabaja con el esquema de agentes traído de Delivery Run: un `coordinador` (hilo principal) que reparte entre
workers, cada uno con su revisor dedicado, con TDD y máximo 2 rondas worker↔revisor. Sin comentarios en el código.

## Provisorias

Decisiones tomadas por un agente (o propuestas) que el dueño todavía no revisó. Formato: fecha, tarea, contexto, opción elegida, alternativas.

- **P-001 · 2026-11-13 · setup** · Build con **Maven** (más común en Java empresarial). Alternativa: Gradle con Kotlin DSL.
- **P-002 · 2026-11-13 · setup** · Código e identificadores en **inglés** (portfolio y venta), documentación y commits en español. Alternativa: dominio en español.
- **P-003 · 2026-11-13 · setup de agentes** · Nombres de tests en inglés, camelCase, contando el escenario (`duplicatedWebhookDoesNotDuplicateMessage`), con marcadores `// Given / When / Then`, que son los únicos comentarios permitidos. Alternativas: `@DisplayName` en español; nombres en snake_case.
- **P-004 · 2026-11-13 · setup de agentes** · Migraciones Flyway con numeración correlativa (`V<n>__...`); si choca con `main`, se renumera la de la rama. Alternativa: versión por timestamp (evita choques entre ramas paralelas, pero exige `outOfOrder`).
- **P-005 · 2026-11-14 · #1 scaffold** · Paquete raíz y groupId `com.communicatorone`. Cada módulo es un subpaquete directo con un `package-info.java` anotado `@ApplicationModule`; la configuración transversal (por ejemplo `SecurityConfiguration`) va en el paquete raíz. Alternativa: un subpaquete `shared`/`config` declarado como módulo compartido.
- **P-006 · 2026-11-14 · #1 scaffold, ajustada en #25** · Seguridad base hasta #5 (JWT): sesión stateless, CSRF deshabilitado, sin usuario por defecto (se excluye `UserDetailsServiceAutoConfiguration`), públicos `/error` y `/actuator/health` y todas sus subrutas (entre ellas liveness y readiness, para las probes) vía el matcher de actuator `EndpointRequest.to(HealthEndpoint.class)`, con detalles y componentes ocultos porque `show-details` y `show-components` quedan en `never` (el default), que no se sube a `always` sin autenticación; el resto, incluidos Swagger, `/v3/api-docs` y los demás endpoints de actuator, responde 401. Alternativas: Swagger público en desarrollo; dejar el usuario generado por Boot.
- **P-007 · 2026-11-14 · #1 scaffold** · Defaults de desarrollo en `application.yml` para la URL de la base (armada con `DB_HOST`, `POSTGRES_PORT` y `POSTGRES_DB`, las mismas variables del compose), `DB_USERNAME` y `DB_PASSWORD`, que coinciden con `docker-compose.yml`. Revisar cuando exista un entorno de deploy. Alternativas: sin default para `DB_PASSWORD` (falla si falta); un perfil `local`.
- **P-008 · 2026-11-14 · #1 scaffold** · Redis está en `docker-compose.yml` porque lo pide el issue, pero la app todavía no tiene cliente ni lo usa (su health indicator rompería el test de salud sin Redis levantado); se agrega cuando una tarea lo necesite. Alternativa: sacarlo del compose hasta entonces.
- **P-009 · 2026-11-14 · #1 scaffold** · Sin `spring-modulith-starter-jpa` (registro de publicación de eventos) hasta que haya eventos persistentes; entonces se suma con su migración de `event_publication`. Alternativa: incluirlo ya, con la migración.
- **P-010 · 2026-11-14 · #1 scaffold** · Sin `name:` en `docker-compose.yml`: cada worktree tiene su proyecto y su volumen propios (comparten puertos, así que se levanta uno a la vez). Alternativa: `name: communicator-one` compartido entre worktrees.
