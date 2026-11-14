# Principios generales

## Contenido

- TDD como estrategia de desarrollo
- Cambios mínimos
- DRY, pero sin abstraer prematuramente
- No abstraer por anticipación (YAGNI)
- Simplicidad antes que sofisticación
- No hacer refactors oportunistas

---

## TDD como estrategia de desarrollo

Siempre que sea razonable:

1. Escribir o modificar primero los tests.
2. Implementar la mínima solución necesaria para hacerlos pasar.
3. Refactorizar manteniendo los tests en verde.

Priorizar:

* Tests de comportamiento sobre tests de implementación.
* Casos de negocio sobre detalles internos.
* Tests pequeños y deterministas.
* Evitar mocks innecesarios.

No modificar tests simplemente para hacer pasar una implementación incorrecta.

---

## Cambios mínimos

Cuando se trabaja sobre código existente:

* Modificar solamente lo necesario para resolver el problema.
* No refactorizar código no relacionado con la tarea.
* No cambiar arquitecturas existentes sin una razón concreta.
* No "mejorar" código legacy que no sea parte del problema.
* Evitar cambios masivos que dificulten revisar el diff.

La solución debe ser proporcional al problema.

> Si para arreglar un bug de 10 líneas terminamos modificando 20 archivos, probablemente estamos resolviendo otro problema.

---

## DRY, pero sin abstraer prematuramente

No duplicar reglas de negocio.

Pero tampoco crear abstracciones solamente porque dos fragmentos de código se parecen.

Dos cosas similares sintácticamente no necesariamente representan el mismo concepto.

Primero entender el dominio.

Después abstraer cuando exista una razón real para compartir comportamiento.

> La duplicación accidental puede ser menos peligrosa que una abstracción incorrecta.

---

## No abstraer por anticipación (YAGNI)

No crear:

* interfaces sin necesidad,
* factories porque "quizás después",
* repositorios innecesarios,
* clases abstractas sin múltiples implementaciones,
* capas adicionales sin responsabilidad clara.

La arquitectura debe responder a necesidades reales.

> YAGNI: no diseñar para problemas hipotéticos mientras el problema actual sigue esperando.

---

## Simplicidad antes que sofisticación

Ante dos soluciones equivalentes, elegir la más simple.

Prioridad:

```text
Código claro
    >
Código inteligente
    >
Código ingenioso
```

Evitar patrones, metaprogramación, abstracciones o infraestructura adicional si una solución directa resuelve correctamente el problema.

---

## No hacer refactors oportunistas

Mientras se implementa una funcionalidad:

* No cambiar nombres de todo el módulo.
* No migrar tecnologías.
* No reestructurar todo el proyecto.
* No actualizar dependencias sin necesidad.
* No corregir problemas ajenos a la tarea.

Si se detecta un problema importante pero no relacionado, documentarlo y dejarlo separado.
