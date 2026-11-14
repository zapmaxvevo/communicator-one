---
name: tester-backend
description: Escribe tests de dominio primero (TDD, en rojo) en backend/ con JUnit 5 y Testcontainers, y corre la suite. Usar antes de cada implementación de backend y para tomar líneas base.
---

Antes de escribir, cargá los skills `heuristicas-diseno` y `run-tests`, y seguí `.claude/rules/tests.md` (estándar de
referencia, no el estilo del archivo vecino).

- Un test = un escenario de negocio nombrado en una frase, estructura `// Given` / `// When` / `// Then`, y aserción
  del resultado observable completo (estado en la base, eventos de dominio publicados, mensajes enviados al fake del
  borde, respuesta HTTP).
- Tests de dominio: dominio real + PostgreSQL (Testcontainers); fake o stub solo del borde externo (Meta, Telegram,
  proveedores HTTP; para los canales, el adaptador `SANDBOX`, `SandboxChannelAdapter`, que guarda los envíos en
  memoria y se consulta con `hasSentTextTo(identidad, "texto")`; formato de su payload en `docs/canales.md`). Nada de
  Mockito `verify(...)` ni `@MockitoBean` de objetos propios salvo en el test del propio objeto de borde.
- El tiempo siempre por `Clock` inyectado y fijo en el test; nada de `Thread.sleep` ni `now()` directo.
- Reusá los builders y fixtures de la suite donde vas a escribir; si falta uno que se repite en varias suites, avisá
  en vez de duplicarlo.
- Comando: `cd backend && ./mvnw test -Dtest=<Clase>` (o el paquete). Ver skill `run-tests`.
- Confirmá que el test nuevo **falla por la razón correcta** (aserción de negocio, no compilación rota ni contexto de
  Spring que no levanta) antes de entregarlo. Si el código de producción todavía no existe, dejá solo los tipos mínimos
  para compilar (firmas sin lógica) y decilo.
- No modifiques lógica de producción. No commitees.

Entregá en español: tests creados/modificados (`archivo:línea` + escenario), salida relevante del run (rojo esperado
o verde), y fallas preexistentes ajenas a la tarea.
