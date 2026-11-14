---
description: Cómo escribir tests del backend — tests de dominio y comportamiento antes que tests unitarios por método
paths:
  - "backend/src/test/**"
---

# Tests del backend

Estas reglas existen porque el instinto por defecto (un test por método, mockear cada colaborador, afirmar "se llamó a
X con Y") produce **tests frágiles que no prueban que el negocio funciona**. Acá se testea **comportamiento del
dominio**, no implementación.

**Seguí este estándar, no el archivo de al lado.** Si los tests vecinos chocan con estas reglas, escribí según las
reglas y avisá.

## Dos tipos de test: sabé cuál estás escribiendo

Casi todo lo que escribas es **(1)**. El **(2)** es solo para el objeto de borde.

**(1) Test de dominio / comportamiento**: el default, el grueso de la suite.
Corre el dominio real (servicios de aplicación, entidades, repositorios, eventos, la base) y afirma el **resultado
observable**. Fake solo del borde externo. **Nunca** `verify(...)` de Mockito ni `@MockitoBean` de objetos propios:
eso prueba el cableado, no el negocio.
- Con base: `@SpringBootTest` o `@ApplicationModuleTest` (Spring Modulith) + PostgreSQL con Testcontainers. Nada de H2.
- Si la regla es lógica pura (normalizar un teléfono a E.164, calcular el estado de ventana desde la fecha del último
  mensaje del cliente), test sin Spring ni base: más rápido e igual de válido. Sigue siendo un escenario, no un método.

**(2) Test unitario de borde**: solo para el objeto cuyo trabajo *es* hablar con un servicio externo (el cliente
HTTP de Meta o Telegram, el parser de webhooks). Su responsabilidad es armar y mandar el request correcto o interpretar
el payload correcto, así que acá, y **solo** acá, corresponde afirmar la forma exacta del request (con
`MockRestServiceServer`, MockWebServer o WireMock) o del parseo. Cubrí **ok + falla de conexión + respuesta
malformada**. Los payloads de ejemplo de los proveedores van en `src/test/resources`. Todo lo que *usa* ese objeto
recibe un test tipo (1) con un fake inyectado que afirma el resultado de dominio, no el payload.

## La regla que importa: testear un escenario de negocio, no un método

Antes de escribir un test, nombrá el **escenario** en una frase: un conjunto de condiciones y qué tiene que pasar
("un mensaje de una identidad desconocida crea una persona nueva con una oportunidad abierta"; "un webhook repetido no
duplica el mensaje"). Esa frase *es* el nombre del test y guía las aserciones. Si no podés decirla sin nombrar un
método o un argumento, estás por escribir un test unitario: subí un nivel.

### Un escenario por test, pero afirmando todo su resultado
- Un test fija **un** conjunto de condiciones (`// Given`) y afirma **todos** los efectos observables que produce. Si
  recibir un mensaje crea la persona, abre la oportunidad, guarda el mensaje y publica el evento, afirmá todo: es un
  escenario, no cuatro.
- Lo que se separa en tests distintos es la **variación de condiciones**: identidad conocida o desconocida, dentro o
  fuera de la ventana de reingreso, proveedor que falla, otra organización. Cada condición distinta es su propio test.
- Estructura `// Given` / `// When` / `// Then` (los únicos comentarios permitidos).

## Afirmar comportamiento observable, no llamadas internas

❌ Prueba el cableado: pasa aunque el efecto real esté roto y se rompe con refactors inofensivos.
```java
verify(assignmentStrategy).assign(opportunity);
```

✅ Afirma el resultado: estado real o una consulta de dominio del fake.
```java
@Test
void unknownIdentityCreatesNewPersonWithOpenOpportunity() {
    // Given
    var inbound = sandbox.inboundText(organization, "+5491155550101", "Hola, ¿tienen financiación?");

    // When
    inboundPipeline.process(inbound);

    // Then
    var person = people.findByIdentity(organization.id(), whatsapp("+5491155550101")).orElseThrow();
    var opportunity = opportunities.openFor(person.id()).orElseThrow();
    assertThat(opportunity.owner()).isNotNull();
    assertThat(messages.timelineOf(person.id())).singleElement()
            .satisfies(message -> assertThat(message.senderType()).isEqualTo(SenderType.CUSTOMER));
}
```

## Fakes: solo el borde externo, e inyectados

Real (nunca mockeado) en tests tipo (1): entidades, servicios, repositorios, eventos, la base. **Nunca mockees el
objeto bajo test ni sus colaboradores de dominio.** No agregues un fake sobre el que no afirmás nada.

Fake **solo** de lo que cruza el límite del proceso:
- **Proveedores externos** (Meta, Telegram, email): adaptadores fake o el canal `SANDBOX`, con métodos de consulta en
  lenguaje de dominio (`sandbox.hasSentTextTo(identity, "...")`) en vez de introspección de llamadas.
- **El reloj**: un `Clock` fijo como bean de test, con un helper para avanzarlo (`clock.advance(Duration.ofMinutes(16))`).
  Nada de `Thread.sleep`.

## Nombres

Nombre del método en inglés, en camelCase, que cuenta el caso de negocio: `duplicatedWebhookDoesNotDuplicateMessage`,
`personReturningAfterReentryWindowGetsNewOpportunity`, `operatorAtCapacityIsSkippedByRoundRobin`.
❌ `testProcess`, `shouldWork`, `returnsTrue`, `testAssign2`: no dicen nada del caso.

Clases: `<LoQueTesteo>Test`.

## Setup

- Un único contenedor de PostgreSQL compartido por la suite (`@ServiceConnection` / contenedor singleton) para que
  sea rápida.
- Datos con **builders en lenguaje de dominio**, agrupados en un objeto (`TestData.organization()...`,
  `people.withWhatsapp("+54...")`), no funciones estáticas sueltas repartidas por los archivos.
- Setup repetido de un escenario → un objeto fixture del escenario con métodos con nombre de dominio.

## No escribas estos

- Tests de getters/setters, `equals`/`toString` o "el campo se guardó tal cual".
- **Tests que solo prueban que JPA o Spring funcionan**, no nuestro código (guardar y releer una entidad sin lógica).
- Tests del código generado (cliente OpenAPI) o de configuración trivial.
- Tests acoplados a índices de listas o estructuras internas (fuera del test de borde que es dueño de esa estructura).

## Escenarios que no pueden faltar en este dominio

- **Otra organización:** toda consulta o endpoint nuevo tiene un test de que no ve datos de otra organización.
- **Webhooks:** repetido (no duplica) y desordenado (un `DELIVERED` que llega después de un `READ` no retrocede el
  estado).
- **Tiempo:** bordes de ventanas y temporizadores con reloj fijo (justo antes y justo después del límite).

## Antes de terminar: checklist de "esto huele a test unitario"

1. ¿Puedo decir el escenario sin nombrar un método o argumento? Si no, subí un nivel.
2. ¿Es tipo (1)? Entonces mis aserciones chequean un **resultado observable** (base, consulta, evento, excepción, una
   consulta de dominio del fake), no `verify`.
3. ¿El fake del borde está inyectado y el dominio corre real?
4. ¿Afirmé **todo** el resultado del escenario y separé solo las **condiciones**?
5. ¿Este test seguiría pasando después de un refactor interno que preserva el comportamiento?
6. ¿El nombre se lee como una frase que alguien de producto reconocería?

## Referencias

Cuando existan suites que cumplan bien este estándar, listarlas acá como ejemplo a seguir.
