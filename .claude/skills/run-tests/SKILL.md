---
name: run-tests
description: Corre los tests del backend (Maven + JUnit 5 + Testcontainers) o de la web (Vitest) con los comandos correctos. Usar cuando se pida correr tests, verificar un cambio o chequear un módulo o test puntual. Opcionalmente recibe una clase, un paquete o un archivo (ej. "PersonTest", "people", "web/src/features/inbox").
---

Correr los tests del proyecto. Los comandos definitivos están en `CLAUDE.md` (sección «Comandos»); si difieren de lo
que dice acá, manda `CLAUDE.md`.

## Backend (`backend/`)

Necesita Docker corriendo (Testcontainers levanta su propio PostgreSQL; no hace falta el `docker compose`). Siempre con
el wrapper de Maven desde `backend/`:

```bash
cd backend && ./mvnw test                      # tests unitarios y de integración que corre surefire
cd backend && ./mvnw verify                    # build completo con la suite entera (lo mismo que corre el CI)
```

Si `$ARGUMENTS` trae una clase, un método o un módulo, acotá la corrida:

```bash
cd backend && ./mvnw test -Dtest=PersonTest
cd backend && ./mvnw test -Dtest='PersonTest#unknownIdentityCreatesNewPerson'
cd backend && ./mvnw test -Dtest=ModularStructureTest    # estructura de módulos, no necesita Docker
cd backend && ./mvnw test -Dtest='com/communicatorone/people/**/*' -Dsurefire.failIfNoSpecifiedTests=false
```

Si Docker no está disponible, no cambies la configuración para esquivarlo: avisá (ver regla 8 de `CLAUDE.md`).

## Web (`web/`)

```bash
cd web && npx vitest run <archivos o carpeta>
cd web && npm test                             # suite completa
cd web && npm run lint && npm run build        # lint y build además de los tests
```

## Después de correr

Informá cantidad de tests que pasan y fallan, y mostrá cada falla con su stack trace relevante. No modifiques la
configuración de tests ni de la base para que pasen.
