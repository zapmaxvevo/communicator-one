---
description: Estándares de código Java / Spring Boot para backend/
paths:
  - "backend/**/*.java"
---

# Java / Spring Boot

Java (la LTS que fija `backend/pom.xml`) + Spring Boot, monolito modular con Spring Modulith, arquitectura hexagonal.
Para tests seguí `tests.md`; para migraciones, `flyway.md`.

## Idioma
- Código, identificadores, nombres de API y mensajes de log en **inglés**, usando el glosario de `CLAUDE.md`
  (`Person`, `ContactIdentity`, `Opportunity`, `Message`, `ChannelAccount`, `ChannelAdapter`, `Operator`,
  `AssignmentStrategy`, `AttentionTimer`). La documentación de `docs/` va en español.

## Módulos y capas
- Un módulo de Spring Modulith por concepto (`organizations`, `operators`, `people`, `opportunities`, `messaging`,
  `channels`, `assignment`, `notifications`). El paquete raíz del módulo es su API pública; los subpaquetes son internos y nadie
  de afuera los importa. `ApplicationModules.verify()` tiene que pasar siempre.
- Entre módulos: llamadas a la API pública o eventos de dominio. Efectos que no tienen que bloquear al que publica
  (notificar por WebSocket, arrancar un temporizador) van por evento, después del commit.
- Hexagonal: el dominio no depende de Spring Web, de SDKs de proveedores ni de detalles HTTP. Canales y reparto son
  adaptadores detrás de un puerto (`ChannelAdapter`, `AssignmentStrategy`).

## Diseño de objetos
- **Inyección por constructor**, campos `final`. Nada de `@Autowired` en campos.
- Objetos de dominio válidos desde su creación: **métodos de fábrica estáticos con nombre de intención**
  (`Opportunity.openFor(person, source, clock)`) en vez de constructores vacíos más setters. Sin setters públicos para
  reglas de negocio: `opportunity.close(result)`, no `opportunity.setStatus(CLOSED)`.
- `record` para value objects, comandos y DTOs.
- Visibilidad mínima: package-private por defecto y `public` solo lo que es API del módulo.
- El tiempo siempre por `java.time.Clock` inyectado. Nada de `Instant.now()` ni `LocalDateTime.now()` sin clock.

### Despacho polimórfico (en vez de `switch` por tipo)
Cuando el comportamiento depende del canal (o de otro tipo intercambiable), **no escribas `if`/`switch` sobre
`ChannelType` en el core**. Cada implementación es un bean que declara qué tipo atiende, y un registro las resuelve:

```java
@Component
class ChannelAdapterRegistry {

    private final Map<ChannelType, ChannelAdapter> adaptersByType;

    ChannelAdapterRegistry(List<ChannelAdapter> adapters) {
        this.adaptersByType = adapters.stream()
                .collect(Collectors.toUnmodifiableMap(ChannelAdapter::type, Function.identity()));
    }

    ChannelAdapter adapterFor(ChannelType type) {
        var adapter = adaptersByType.get(type);
        if (adapter == null) {
            throw new UnsupportedChannelException(type);
        }
        return adapter;
    }
}
```

`toUnmodifiableMap` falla al arrancar si dos adaptadores declaran el mismo tipo: así tiene que ser. Un `switch` simple
y local sobre un enum propio del módulo está bien; lo que se evita es repartir conocimiento de cada canal por el core.

## Errores
- Excepciones de dominio **unchecked**, con nombre que explica qué pasó (`ConversationWindowClosedException`,
  `ChannelAccountNotFoundException`), con una base por módulo si hace falta distinguirlas.
- Se traducen a HTTP en un único `@RestControllerAdvice` con `ProblemDetail`. Los controllers no atrapan excepciones de
  dominio uno por uno.
- Un adaptador atrapa los errores de su proveedor y los traduce (por ejemplo a un `SendResult` fallido o a una
  excepción del puerto); el error del SDK nunca llega al dominio. Nada de `catch (Exception e)` sin una razón concreta.

## Persistencia y transacciones
- `@Transactional` en el caso de uso (servicio de aplicación), no en controllers ni repositorios. Las lecturas, con
  `readOnly = true`.
- Multi-tenant (P-018): toda entidad de negocio lleva `@TenantId @Column(name = "organization_id") private UUID
  organizationId;`, sin setter. Hibernate lo asigna al persistir con la organización activa y filtra por él las
  consultas JPQL, Criteria, derivadas de Spring Data y `findById`. No se agrega `organizationId` a mano a esas
  consultas. Los `UPDATE`/`DELETE` masivos por JPQL no están verificados: no confiar en el filtro ahí sin un test de
  otra organización. `TenantIsolationTest` falla si una entidad de `com.communicatorone` (salvo `Organization`) no lo
  tiene.
- La organización activa la da `CurrentOrganization` (módulo `organizations`): el ámbito explícito de `runAs`/`callAs`
  o, si no hay, el principal autenticado que implementa `OrganizationMember`. Sin ninguno de los dos, las lecturas
  vuelven vacías y las escrituras fallan.
- SQL nativo y `JdbcTemplate` **no** pasan por `@TenantId`: filtran por `organization_id` a mano, siempre. Única
  excepción: `OperatorEmailDirectory` (módulo `operators`, P-022) busca por email sin filtrar organización porque el
  login todavía no tiene una; solo devuelve el `organization_id`, y el operador se carga después con `callAs` y la
  lectura filtrada (`docs/autenticacion.md`). Otra consulta que cruce organizaciones se decide en su tarea y se anota
  como provisoria.
- Procesos sin usuario (webhooks, jobs, listeners) llaman a `currentOrganization.runAs(organizationId, ...)` **fuera**
  de la transacción, con el `@Transactional` adentro: la organización se fija al abrir la sesión, y `runAs` lanza
  `IllegalStateException` si hay sincronización de transacción activa (una transacción o un ámbito `SUPPORTS`) o si ya
  hay una sesión de JPA ligada al hilo (`TransactionSynchronizationManager.hasResource(entityManagerFactory)`: OSIV,
  `SYNCHRONIZATION_NEVER` o un `EntityManager` ligado a mano).
- Los cambios de hilo (`@Async`, executors, `CompletableFuture`) no heredan el ámbito de `runAs` ni el
  `SecurityContext`: el código que corre en el otro hilo vuelve a abrir su `runAs`.
- Los eventos de dominio llevan `organizationId`, que se toma del accesor de la entidad (lo asignó Hibernate al
  persistir). `CurrentOrganization.resolve()` es package-private: fuera de `organizations` no se pregunta la
  organización activa. Los listeners de eventos que tocan datos de una organización no llevan transacción propia:
  hacen `runAs(event.organizationId(), ...)` y llaman a un servicio `@Transactional`; no usar
  `@ApplicationModuleListener` directo para eso. Tampoco un `@TransactionalEventListener(phase = AFTER_COMMIT)`
  síncrono: corre con la sincronización de la transacción todavía activa y `runAs` lanza `IllegalStateException`. El
  listener es `@Async` + `@TransactionalEventListener`, sin `@Transactional`; hace falta `@EnableAsync`, que la app
  todavía no tiene.
- La app corre con `spring.jpa.open-in-view: false` (`application.yml`). Con OSIV activo, el `EntityManager` se abre
  al empezar el request con la organización del usuario y queda ligado al hilo, así que un `runAs` dentro del request
  lanza `IllegalStateException`.
- Webhooks idempotentes: restricción única en la base (`externalId` + `ChannelAccount`) y el duplicado se trata como
  éxito.
- `spring.jpa.hibernate.ddl-auto=validate`. El esquema solo cambia con Flyway.

## Configuración y seguridad
- Propiedades con `@ConfigurationProperties`; credenciales y URLs de proveedores por variables de entorno, listadas en
  `.env.example`. Nunca valores reales en el repo.
- Webhooks: validar la firma del proveedor antes de procesar.
- Logs con SLF4J. Teléfonos, emails y contenido de mensajes no van a logs de nivel `INFO` o superior.

## Estilo
- **PROHIBIDO ESCRIBIR COMENTARIOS (incluido Javadoc) salvo que se pidan explícitamente.** Los nombres tienen que
  explicar el código. Única excepción: los marcadores `// Given` / `// When` / `// Then` en tests.
- `var` para variables locales cuando el tipo es obvio por el lado derecho.
- `Optional` solo como tipo de retorno, nunca como campo ni parámetro.
