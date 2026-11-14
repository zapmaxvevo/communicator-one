---
name: heuristicas-diseno
description: Heurísticas de diseño de software del equipo (TDD, cambios mínimos, alta cohesión, bajo acoplamiento, encapsulamiento, Tell Don't Ask, Ley de Demeter, nombres significativos, YAGNI, etc.). Cargar SIEMPRE antes de analizar, diseñar, implementar, refactorizar o revisar código en este repo — backend o web — no solo para tareas explícitamente de "diseño".
---

Estas heurísticas guían por defecto el análisis, diseño, implementación y modificación de código en este repositorio.

El objetivo no es aplicar patrones por obligación, sino producir código simple, mantenible, testeable y con
responsabilidades bien definidas. **No aplicar estas heurísticas mecánicamente. Son herramientas de diseño, no reglas
dogmáticas.** Conviven con `.claude/rules/java.md`, `.claude/rules/tests.md` y `.claude/rules/frontend.md`, que mandan
sobre convenciones concretas del repo (idioma, nombres, estilo) cuando haya conflicto de detalle.

## Cómo usar esta skill

1. Antes de tocar código: repasar "Antes de implementar" más abajo.
2. Si vas a crear una clase o una abstracción: repasar la sección correspondiente más abajo.
3. Para el detalle de una heurística puntual, abrir solo el archivo de referencia que la contiene (tabla abajo).
4. Al terminar un cambio: usar "Criterio general" como checklist final.

## Heurísticas de referencia

| Heurística | Archivo |
|---|---|
| TDD como estrategia de desarrollo | [reference/principios-generales.md](reference/principios-generales.md) |
| Cambios mínimos | [reference/principios-generales.md](reference/principios-generales.md) |
| DRY, pero sin abstraer prematuramente | [reference/principios-generales.md](reference/principios-generales.md) |
| No abstraer por anticipación (YAGNI) | [reference/principios-generales.md](reference/principios-generales.md) |
| Simplicidad antes que sofisticación | [reference/principios-generales.md](reference/principios-generales.md) |
| No hacer refactors oportunistas | [reference/principios-generales.md](reference/principios-generales.md) |
| Alta cohesión | [reference/diseno-de-objetos.md](reference/diseno-de-objetos.md) |
| Bajo acoplamiento | [reference/diseno-de-objetos.md](reference/diseno-de-objetos.md) |
| Encapsulamiento | [reference/diseno-de-objetos.md](reference/diseno-de-objetos.md) |
| Objetos válidos | [reference/diseno-de-objetos.md](reference/diseno-de-objetos.md) |
| Polimorfismo antes que condicionales complejos | [reference/diseno-de-objetos.md](reference/diseno-de-objetos.md) |
| Composición sobre herencia | [reference/diseno-de-objetos.md](reference/diseno-de-objetos.md) |
| Reglas de negocio en el lugar correcto | [reference/diseno-de-objetos.md](reference/diseno-de-objetos.md) |
| Evitar "God Objects" | [reference/diseno-de-objetos.md](reference/diseno-de-objetos.md) |
| Tell, Don't Ask | [reference/diseno-de-objetos.md](reference/diseno-de-objetos.md) |
| Ley de Demeter | [reference/diseno-de-objetos.md](reference/diseno-de-objetos.md) |
| Abstracciones al nivel correcto | [reference/diseno-de-objetos.md](reference/diseno-de-objetos.md) |
| Nombres significativos | [reference/codigo-limpio.md](reference/codigo-limpio.md) |
| Funciones pequeñas | [reference/codigo-limpio.md](reference/codigo-limpio.md) |
| Separar dominio de infraestructura | [reference/codigo-limpio.md](reference/codigo-limpio.md) |
| Dependencias explícitas | [reference/codigo-limpio.md](reference/codigo-limpio.md) |
| Evitar efectos secundarios ocultos | [reference/codigo-limpio.md](reference/codigo-limpio.md) |
| Inmutabilidad cuando simplifique el diseño | [reference/codigo-limpio.md](reference/codigo-limpio.md) |
| Fail fast | [reference/codigo-limpio.md](reference/codigo-limpio.md) |
| Excepciones con significado | [reference/codigo-limpio.md](reference/codigo-limpio.md) |
| Evitar métodos que hagan demasiado | [reference/codigo-limpio.md](reference/codigo-limpio.md) |

---

## Antes de implementar

Antes de escribir código:

1. Entender el flujo actual.
2. Identificar dónde vive actualmente la responsabilidad.
3. Revisar tests existentes.
4. Buscar implementaciones similares.
5. Identificar dependencias.
6. Determinar el cambio mínimo necesario.
7. Recién después implementar.

No asumir cómo funciona el sistema basándose únicamente en nombres de archivos.

---

## Antes de crear una nueva clase

Preguntarse:

* ¿Existe ya un objeto que debería tener esta responsabilidad?
* ¿Estoy creando esta clase porque realmente representa un concepto?
* ¿Estoy evitando una clase existente por desconocer el código?
* ¿La nueva clase reduce complejidad o solamente la mueve?
* ¿Tiene una única responsabilidad clara?

Si la respuesta no es clara, probablemente todavía no sea necesaria.

---

## Antes de crear una abstracción

Preguntarse:

* ¿Existen realmente múltiples implementaciones?
* ¿Existe comportamiento que deba variar?
* ¿La abstracción representa un concepto del dominio?
* ¿Mejora la testabilidad?
* ¿Reduce acoplamiento?
* ¿Hace el código más fácil de entender?

Si solamente responde "quizás algún día necesitemos otra implementación", no crearla todavía.

Excepción en este proyecto: los puertos que fija la arquitectura (`ChannelAdapter`, `AssignmentStrategy`) existen
desde el principio porque el producto ya tiene varias implementaciones previstas (D-006, `docs/vision.md` §5).

---

## Criterio general

Ante cualquier decisión de diseño, priorizar en este orden:

1. Correctitud.
2. Claridad.
3. Simplicidad.
4. Cohesión.
5. Bajo acoplamiento.
6. Testabilidad.
7. Extensibilidad cuando exista una necesidad real.

No optimizar para "arquitectura perfecta".

Optimizar para que el próximo desarrollador pueda entender el código y modificarlo sin miedo.

---

# Regla final

Antes de hacer un cambio, preguntarse:

> ¿Cuál es la solución más simple que mantiene las responsabilidades correctas, permite probar el comportamiento y
> modifica la menor cantidad de código posible?

Si una solución requiere introducir muchas abstracciones, patrones o archivos nuevos, justificar primero por qué son
necesarias.

**No aplicar estas heurísticas mecánicamente. Son herramientas de diseño, no reglas dogmáticas.**
