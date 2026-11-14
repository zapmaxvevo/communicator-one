# Diseño de objetos y responsabilidades

## Contenido

- Alta cohesión
- Bajo acoplamiento
- Encapsulamiento
- Objetos válidos
- Polimorfismo antes que condicionales complejos
- Composición sobre herencia
- Reglas de negocio en el lugar correcto
- Evitar "God Objects"
- Tell, Don't Ask
- Ley de Demeter
- Abstracciones al nivel correcto

---

## Alta cohesión

Cada clase, módulo o función debe tener una responsabilidad clara.

Evitar clases que:

* Consulten la base de datos.
* Apliquen reglas de negocio.
* Formateen respuestas.
* Envíen mensajes por un canal.
* Manejen HTTP.
* Y además hagan café.

Separar responsabilidades cuando exista una razón real para hacerlo.

Una clase debe agrupar comportamiento relacionado con un mismo concepto del dominio.

---

## Bajo acoplamiento

Reducir la cantidad de conocimiento que un componente necesita tener sobre otros.

Preferir:

```text
Servicio → puerto (interfaz) → adaptador
```

sobre:

```text
Servicio → implementación concreta → detalles internos → infraestructura
```

Evitar que una clase dependa de detalles innecesarios de otra.

Si cambiar una clase obliga a modificar muchas clases que no deberían conocer ese cambio, probablemente existe
demasiado acoplamiento. En este repo, si sumar un canal obliga a tocar el core, el acoplamiento está mal.

---

## Encapsulamiento

Los objetos deben controlar su propio estado y comportamiento.

Evitar:

```java
opportunity.setStatus(OpportunityStatus.CLOSED);
opportunity.setClosedAt(Instant.now());
person.getIdentities().add(identity);
```

cuando esas operaciones representan reglas de negocio.

Preferir:

```java
opportunity.close(OpportunityResult.WON, clock);
person.addIdentity(identity);
```

El objetivo es que las reglas importantes estén dentro del objeto que tiene la información necesaria para hacerlas
cumplir.

---

## Objetos válidos

Preferir objetos que sean válidos desde su creación.

Evitar objetos parcialmente inicializados:

```java
var opportunity = new Opportunity();
opportunity.setPerson(person);
opportunity.setSource(source);
opportunity.setStatus(OpportunityStatus.OPEN);
```

si el dominio requiere esos valores.

Preferir:

```java
var opportunity = Opportunity.openFor(person, source, clock);
```

La creación del objeto debe garantizar sus invariantes siempre que sea posible.

Evitar usar `null` como representación de múltiples estados posibles cuando el dominio puede expresarse mejor
mediante objetos o tipos explícitos (por ejemplo, el estado de ventana `OPEN` / `AWAITING_REPLY` /
`REQUIRES_TEMPLATE` / `BLOCKED` en vez de un `Boolean` que puede ser `null`).

---

## Polimorfismo antes que condicionales complejos

Cuando existen diferentes comportamientos para un mismo concepto, evaluar polimorfismo antes de construir grandes
bloques de:

```java
if (type == ChannelType.EMAIL) {
    ...
} else if (type == ChannelType.WHATSAPP) {
    ...
} else if (type == ChannelType.SMS) {
    ...
}
```

Por ejemplo:

```java
channelAdapters.adapterFor(type).send(to, from, content);
```

No aplicar polimorfismo artificialmente.

Un `if` simple y claro es preferible a crear cinco clases innecesarias.

---

## Composición sobre herencia

Preferir composición cuando permita representar mejor el comportamiento.

Evitar jerarquías de herencia profundas o creadas únicamente para reutilizar código.

Preferir:

```java
class InboundPipeline {
    private final ChannelAdapterRegistry adapters;
    private final IdentityResolver identities;

    InboundPipeline(ChannelAdapterRegistry adapters, IdentityResolver identities) {
        this.adapters = adapters;
        this.identities = identities;
    }
}
```

sobre crear una cadena de clases únicamente para compartir comportamiento.

La herencia debe representar una relación real de sustitución, no solamente "quiero reutilizar estos métodos".

---

## Reglas de negocio en el lugar correcto

Las reglas deben vivir cerca de los datos y conceptos que gobiernan.

Si una regla pertenece claramente a `Opportunity` (por ejemplo, reabrir dentro de la `reentryWindow`), no debería
estar dispersa entre:

```text
controllers
listeners
jobs programados
utils
servicios de otros módulos
```

sin una razón.

Buscar primero el objeto de dominio responsable de la decisión.

---

## Evitar "God Objects"

Una clase que conoce todo y hace todo es una señal de problemas.

Ejemplo:

```text
CommunicationService
    ├── personas
    ├── oportunidades
    ├── WhatsApp
    ├── Telegram
    ├── reparto
    ├── temporizadores
    ├── base de datos
    └── autenticación
```

Separar responsabilidades según conceptos reales del dominio.

No reemplazar un God Object por veinte clases inútiles. La división debe tener sentido.

---

## Tell, Don't Ask

Preferir decirle a un objeto qué debe hacer en lugar de extraer su estado para decidir externamente.

Evitar:

```java
if (opportunity.getStatus() == OpportunityStatus.CLOSED
        && Duration.between(opportunity.getClosedAt(), now).compareTo(reentryWindow) < 0) {
    opportunity.setStatus(OpportunityStatus.OPEN);
}
```

Preferir:

```java
opportunity.reopenIfWithin(reentryWindow, clock);
```

Esto mantiene las reglas dentro del objeto responsable.

---

## Ley de Demeter

Un componente debería conocer solamente las dependencias que realmente necesita.

Evitar cadenas como:

```java
message.getOpportunity().getOwner().getOrganization().getBusinessHours();
```

cuando esto implica conocer demasiada estructura interna.

Preferir métodos que expresen intención:

```java
opportunity.ownerBusinessHours();
```

o una colaboración más directa.

---

## Abstracciones al nivel correcto

Una abstracción debe representar un concepto útil.

Mala abstracción:

```java
BaseManager
GenericProcessor
AbstractHandler
CommonService
```

si no representan conceptos reales.

Mejor:

```java
IdentityResolver
InboundPipeline
RoundRobinAssignmentStrategy
AttentionTimer
```

Las abstracciones deben hablar el lenguaje del dominio.
