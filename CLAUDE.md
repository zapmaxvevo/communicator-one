# CLAUDE.md

Guía para Claude Code (local o en la nube) al trabajar en este repo.

## Qué es

**Communicator One**: sistema para centralizar todas las comunicaciones con prospectos y clientes. Una persona puede escribir por WhatsApp, Instagram, Messenger, Telegram, email, SMS o webchat, pero para el negocio hay **una sola línea de tiempo** con esa persona, y el equipo la atiende desde **un único lugar**.

Leer antes de trabajar:
- `docs/vision.md`: la idea, el modelo de dominio y la arquitectura de canales.
- `docs/decisiones.md`: lo que ya está decidido. **No re-discutir.**
- `docs/backlog.md`: las tareas, en orden. Es la fuente de trabajo.

## Stack

- **Backend:** Java (última LTS) + Spring Boot, Maven, monolito modular con Spring Modulith, arquitectura hexagonal (canales y reparto como adaptadores). PostgreSQL + Flyway, Spring Security (JWT), WebSocket, springdoc-openapi. Tests: JUnit 5 + Testcontainers.
- **Web:** React + TypeScript + Vite.
- **Arquitectura API primero:** toda la lógica vive en el backend. Web, celular, escritorio y terminal son clientes de la misma API (REST + OpenAPI + WebSocket).

## Estructura (prevista)

```
backend/   Spring Boot (módulos: organizations, people, opportunities, messaging, channels, assignment, notifications)
web/       React + TS + Vite
docs/      visión, decisiones, backlog
```

Los comandos de build y test se completan en esta sección cuando se haga el scaffold (tarea T-001 / T-003).

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

## Reglas de trabajo autónomo

Este repo está pensado para que agentes trabajen sin supervisión (por ejemplo, de noche). Por eso:

1. **Tomar la primera tarea `[ ]` de `docs/backlog.md`** cuyas dependencias estén hechas. Una tarea por rama/PR.
2. **Si surge una duda no resuelta en `docs/decisiones.md`:** elegir la opción más simple y convencional, anotarla en la sección *Provisorias* de `docs/decisiones.md` (fecha, contexto, opción elegida, alternativas) y seguir. No frenarse a preguntar.
3. **Si una tarea es demasiado grande**, dividirla en el backlog y hacer solo la primera parte.
4. **Si algo bloquea de verdad** (falta una credencial, una cuenta externa, una decisión de producto que cambia el alcance): marcar la tarea `[!]` con el motivo y pasar a la siguiente.
5. **Tests obligatorios** para la lógica de dominio. No se abre PR con tests rotos.
6. **Al terminar:** marcar la tarea `[x]` en el backlog en el mismo PR, y actualizar este archivo si cambiaron comandos o estructura.
7. **Nunca** commitear credenciales ni tokens. Usar variables de entorno y `.env.example`.
8. Si el entorno no tiene Docker (Testcontainers), usar un perfil de tests con PostgreSQL embebido y dejarlo anotado como provisoria.

## Convenciones

- Código, identificadores y nombres de API en **inglés**. Documentación en `docs/` en **español**.
- Commits y títulos de PR en **español**, en imperativo y concisos.
- Ramas: `feature/T-XXX-descripcion-corta` o `fix/...`. **Nunca pushear a `main`**: siempre PR.
- Migraciones de base solo con Flyway, nunca `ddl-auto=update`.
- Todo procesamiento de webhooks es idempotente (los proveedores reenvían y desordenan eventos).
