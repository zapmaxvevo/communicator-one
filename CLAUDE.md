# CLAUDE.md

Guía para Claude Code (local o en la nube) al trabajar en este repo.

## Qué es

**Communicator One**: sistema para centralizar todas las comunicaciones con prospectos y clientes. Una persona puede escribir por WhatsApp, Instagram, Messenger, Telegram, email, SMS o webchat, pero para el negocio hay **una sola línea de tiempo** con esa persona, y el equipo la atiende desde **un único lugar**.

Leer antes de trabajar:
- `docs/vision.md`: la idea, el modelo de dominio y la arquitectura de canales.
- `docs/decisiones.md`: lo que ya está decidido. **No re-discutir.**
- Las tareas: issues de GitHub en el Project «Communicator One» (ver «Dónde están las tareas»).

## Stack

- **Backend:** Java (última LTS) + Spring Boot, Maven, monolito modular con Spring Modulith, arquitectura hexagonal (canales y reparto como adaptadores). PostgreSQL + Flyway, Spring Security (JWT), WebSocket, springdoc-openapi. Tests: JUnit 5 + Testcontainers.
- **Web:** React + TypeScript + Vite.
- **Arquitectura API primero:** toda la lógica vive en el backend. Web, celular, escritorio y terminal son clientes de la misma API (REST + OpenAPI + WebSocket).

## Estructura (prevista)

```
backend/   Spring Boot (módulos: organizations, people, opportunities, messaging, channels, assignment, notifications)
web/       React + TS + Vite
docs/      visión, decisiones, equivalencias del backlog
scripts/   issues.py (issues, subissues y estados del Project con gh)
.github/   plantillas de issue (tarea, error) y de PR
.claude/   agentes, skills, rules y hooks de Claude Code
```

Los comandos de build y test se completan en esta sección cuando se haga el scaffold (issues #1 y #3).

## Glosario (dominio en español → código en inglés)

| Concepto | Código |
|---|---|
| Organización (tenant, empresa cliente) | `Organization` |
| Persona (permanente) | `Person` |
| Identidad de contacto (wa_id, @ig, email…) | `ContactIdentity` |
| Oportunidad (episodio con ciclo de vida) | `Opportunity` |
| Mensaje | `Message` |
| Cuenta de canal (nuestro número, página, bot) | `ChannelAccount` |
| Adaptador de canal | `ChannelAdapter` |
| Operador | `Operator` |
| Estrategia de reparto | `AssignmentStrategy` |
| Temporizador de atención | `AttentionTimer` |

## Dónde están las tareas

Las tareas son issues del repo `zapmaxvevo/communicator-one`, en el Project «Communicator One» (tablero Todo / In
Progress / Done). Se escriben con las plantillas de `.github/ISSUE_TEMPLATE/` (`tarea`, `error`); por comando:
`python3 scripts/issues.py plantilla tarea`. Una tarea con varias entregas se divide en subissues
(`scripts/issues.py dividir`).

El Project está en https://github.com/users/zapmaxvevo/projects/1, con un milestone por fase. `docs/backlog.md` quedó
como tabla de equivalencias con los `T-XXX` anteriores.

## El ciclo de un issue

El detalle paso a paso está en el skill `agarrar-issue`. En resumen:

1. Leer el issue, dividirlo si tiene varias tareas y verificar que su premisa siga siendo cierta.
2. `python3 scripts/issues.py tomar N` (asignado + In Progress).
3. Worktree propio (`EnterWorktree`) y rama `feature/N-tema` o `fix/N-tema`.
4. Test primero, cambios mínimos, suite completa en verde. El commit lleva `Closes #N`.
5. `simplify`, push y PR con la plantilla. Revisión con `code-review <PR> --comment`.
6. El dueño mergea. Al mergear, el issue pasa a Done; los hooks cierran padres y limpian worktrees.

## Agentes

Para trabajar una tarea con revisión por pares: `claude --agent coordinador`. El coordinador no implementa: reparte
entre workers (`explorador`, `tester-backend`, `implementador-backend`, `tester-frontend`, `implementador-frontend`,
`infra`, `documentador`), cada uno con su revisor dedicado, y abre el PR. Definiciones en `.claude/agents/`.
Skills del repo: `agarrar-issue`, `heuristicas-diseno`, `run-tests`, `documentation`. Rules por tipo de archivo en
`.claude/rules/` (`java.md`, `tests.md`, `flyway.md`, `frontend.md`).

Hooks (`.claude/settings.json`): baseline de tests antes de editar y corrida final al terminar
(`.claude/hooks/run_relevant_tests.py`), `eslint --fix` en `web/`, PRs abiertos y limpieza de worktrees al arrancar,
y `cerrar-padres` después de `gh pr merge`.

## Reglas de trabajo autónomo

Este repo está pensado para que agentes trabajen sin supervisión (por ejemplo, de noche). Por eso:

1. **Tomar la primera tarea abierta** (primer issue en Todo del Project, en orden de milestone) cuyas dependencias
   («Depende de») estén cerradas. Una tarea por rama/PR.
2. **Si surge una duda no resuelta en `docs/decisiones.md`:** elegir la opción más simple y convencional, anotarla en la sección *Provisorias* de `docs/decisiones.md` (fecha, contexto, opción elegida, alternativas) y seguir. No frenarse a preguntar.
3. **Si una tarea es demasiado grande**, dividirla en subissues y hacer solo la primera parte.
4. **Si algo bloquea de verdad** (falta una credencial, una cuenta externa, una decisión de producto que cambia el alcance): comentar el motivo en el issue con la etiqueta `blocked` y pasar a la siguiente.
5. **Tests obligatorios** para la lógica de dominio. No se abre PR con tests rotos.
6. **Al terminar:** el PR lleva `Closes #N`, y se actualiza este archivo si cambiaron comandos o estructura.
7. **Nunca** commitear credenciales ni tokens. Usar variables de entorno y `.env.example`.
8. Si el entorno no tiene Docker (Testcontainers), usar un perfil de tests con PostgreSQL embebido y dejarlo anotado como provisoria.

## Convenciones

- Código, identificadores y nombres de API en **inglés**. Documentación en `docs/` en **español**.
- Commits y títulos de PR en **español**, en imperativo y concisos.
- Ramas: `feature/N-descripcion-corta` o `fix/N-...` (N = número de issue). **Nunca pushear a `main`**: siempre PR.
- Migraciones de base solo con Flyway, nunca `ddl-auto=update`.
- Todo procesamiento de webhooks es idempotente (los proveedores reenvían y desordenan eventos).
