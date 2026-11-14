---
description: Reglas para migraciones Flyway del backend
paths:
  - "backend/src/main/resources/db/migration/**"
  - "backend/src/test/resources/db/migration/**"
---

# Migraciones Flyway

- El esquema cambia **solo** con migraciones Flyway. Nunca `ddl-auto=update`.
- **Nunca edites ni borres una migración que ya está en `main`.** Para cambiar algo, migración nueva.
- Nombres `V<n>__<descripcion_en_ingles>.sql` con `n` correlativo. Si al actualizar la rama tu número choca con uno
  que entró a `main`, renumerá la tuya (nunca la mergeada).
- Una tarea, la menor cantidad de migraciones posible: antes de abrir el PR, juntá en una las que creaste en la rama.
- Toda tabla de negocio lleva `organization_id UUID NOT NULL REFERENCES organizations (id)` con su índice
  (`CREATE INDEX <tabla>_organization_id_idx ON <tabla> (organization_id)`). Es la columna del `@TenantId` de la
  entidad (P-018), y la FK es lo que rechaza una escritura sin organización activa.
- El índice por `organization_id` sobra si la tabla ya tiene un `UNIQUE` o una PK que empieza por `organization_id`
  (por ejemplo, `UNIQUE (organization_id)` en `opportunity_settings`): ese índice ya sirve al filtro del tenant.
- Toda tabla destino de una FK compuesta de tenant `(x_id, organization_id)` lleva `UNIQUE (id, organization_id)`,
  aunque sea redundante con la PK: PostgreSQL exige una restricción única exacta como destino de la FK.
- Ids `UUID` y tablas en plural snake_case (P-019), como `organizations` en `V1__create_organizations.sql`.
- Las migraciones de `src/test/resources/db/migration` son solo de test (no se empaquetan): hoy,
  `R__create_isolation_probe.sql` crea la tabla de la entidad de prueba del aislamiento (P-020).
- Índices desde el día 1 para lo que consultan los webhooks y la ventana de canal (emisor, receptor, fecha) y
  restricción única para la idempotencia (`external_id` + cuenta de canal).
- Cuidado con lo que bloquea o rompe al código que sigue corriendo durante el deploy: renombres, `NOT NULL` sin
  default, borrado de columnas que la versión anterior todavía usa. En esos casos, dividir en pasos (agregar → migrar
  datos → quitar) en tareas separadas.
