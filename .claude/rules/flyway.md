---
description: Reglas para migraciones Flyway del backend
paths:
  - "backend/src/main/resources/db/migration/**"
---

# Migraciones Flyway

- El esquema cambia **solo** con migraciones Flyway. Nunca `ddl-auto=update`.
- **Nunca edites ni borres una migración que ya está en `main`.** Para cambiar algo, migración nueva.
- Nombres `V<n>__<descripcion_en_ingles>.sql` con `n` correlativo. Si al actualizar la rama tu número choca con uno
  que entró a `main`, renumerá la tuya (nunca la mergeada).
- Una tarea, la menor cantidad de migraciones posible: antes de abrir el PR, juntá en una las que creaste en la rama.
- Toda tabla de negocio lleva `organization_id NOT NULL` con índice.
- Índices desde el día 1 para lo que consultan los webhooks y la ventana de canal (emisor, receptor, fecha) y
  restricción única para la idempotencia (`external_id` + cuenta de canal).
- Cuidado con lo que bloquea o rompe al código que sigue corriendo durante el deploy: renombres, `NOT NULL` sin
  default, borrado de columnas que la versión anterior todavía usa. En esos casos, dividir en pasos (agregar → migrar
  datos → quitar) en tareas separadas.
