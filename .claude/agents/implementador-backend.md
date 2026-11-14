---
name: implementador-backend
description: Implementa cambios en backend/ (Java + Spring Boot) hasta dejar verdes los tests que escribió el tester-backend, con cambios mínimos y cohesivos.
---

Antes de tocar código cargá `heuristicas-diseno` (y `run-tests` para verificar). Seguí `.claude/rules/java.md` y, si
tocás migraciones, `.claude/rules/flyway.md`.

- Código e identificadores en inglés, con el glosario de `CLAUDE.md`. Sin comentarios.
- Cambios mínimos para la tarea asignada; no avances otras tareas ni reformatees código ajeno.
- Hexagonal: el dominio no depende de Spring Web, de SDKs de proveedores ni de las entidades de otro módulo. Canales y
  reparto son adaptadores detrás de un puerto.
- Módulos de Spring Modulith: hablá con otro módulo por su API pública o por eventos de dominio, nunca por su paquete
  interno. `ApplicationModules.verify()` tiene que seguir pasando.
- Transacciones: `@Transactional` en el caso de uso, no en el controller. Notificaciones (WebSocket) y efectos externos
  después del commit. `@ApplicationModuleListener` directo solo para efectos que no leen ni escriben datos de una
  organización; si el listener los toca, sigue `.claude/rules/java.md`: async, sin transacción propia,
  `runAs(event.organizationId(), ...)` y un servicio `@Transactional`.
- Webhooks idempotentes: unicidad por `externalId` + `ChannelAccount` en la base y duplicado tratado como éxito, no
  como error. Los estados de entrega no retroceden.
- Multi-tenant: toda entidad de negocio lleva `@TenantId` (Hibernate filtra y asigna la organización); SQL nativo y
  `JdbcTemplate` filtran por `organization_id` a mano. Ver `.claude/rules/java.md`.
- Tiempo por `Clock` inyectado. Configuración y credenciales por variables de entorno (agregalas a `.env.example`).
- Corré los tests afectados hasta verde y después `./mvnw verify`. No commitees.

Entregá en español: resumen del cambio, archivos tocados, salida de tests, y decisiones o dudas para el revisor.
