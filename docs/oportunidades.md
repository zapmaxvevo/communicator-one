# Oportunidades

Issue #36 (subissue 1 de #7). Decisiones: D-001 (persona permanente, oportunidades con ciclo de vida), D-002
(duración configurable) y P-032 (módulo y API), P-033 (modelo), P-034 (reglas), P-035 (unicidad y concurrencia),
P-036 (configuración) en `docs/decisiones.md`. Se apoya en `docs/multi-tenant.md` y en las personas de
`docs/personas.md`.

## Resumen

Una `Opportunity` es un episodio de una persona con la organización: se abre cuando la persona escribe y no tiene una
oportunidad vigente, sigue abierta mientras haya actividad y se cierra sola después de N días sin actividad
(`NO_RESPONSE`). Si la persona vuelve poco después del cierre (dentro de la ventana de reingreso), se reabre la misma;
si vuelve más tarde, se abre una nueva. Una persona tiene a lo sumo una oportunidad abierta.

El módulo `opportunities` expone una sola operación: `OpportunityTracker.recordActivity(personId, source)`. Registra
la actividad de la persona y devuelve el id de su oportunidad vigente: la que ya estaba abierta, la cerrada que se
reabre o una nueva. El cierre por inactividad se evalúa en esa misma llamada («al vuelo») y se fecha en
`última actividad + N`, así que el resultado no depende de que haya corrido un job.

Se va a activar con cada mensaje entrante: el flujo entrante (#10) resuelve la persona, registra la actividad y guarda
el mensaje con el id de la oportunidad (#8). En #36, «actividad» es un mensaje entrante; si los salientes cuentan se
decide con su tarea. Hoy nadie la llama fuera de los tests.

Fuera de #36: el job que persiste los cierres de las oportunidades que nadie toca (#37), el responsable y el reparto
(#13; la columna `owner_id` existe pero queda nula), el cierre manual con `WON`/`LOST` (#15), eventos y endpoints REST.

## Piezas

Módulo `opportunities` (`backend/src/main/java/com/communicatorone/opportunities/`). No depende de ningún otro módulo
en código: la persona llega como `UUID` (P-019) y la base controla que sea de la misma organización; la organización la
asigna y filtra Hibernate con `@TenantId` (P-018).

| Clase | Visibilidad | Qué hace |
|---|---|---|
| `OpportunityTracker` | pública (API) | `@Service` con `recordActivity(UUID, String): UUID` (`OpportunityTracker.java:29-40`), que valida, abre su transacción con el bean `TransactionTemplate` y reintenta una vez ante `DataIntegrityViolationException`. El trabajo lo hace `currentOpportunityOf` (`OpportunityTracker.java:42-51`). Recibe repositorios, `TransactionTemplate` y `Clock` en el constructor (`OpportunityTracker.java:21-27`). |
| `Opportunity` | package-private | Entidad de la tabla `opportunities`: `id`, `@TenantId organizationId`, `personId`, `ownerId`, `source`, `status` y `result` (guardados por nombre), `openedAt`, `lastActivityAt`, `closedAt` (`Opportunity.java:17-45`). Fábrica `openFor(personId, source, now)` (`Opportunity.java:58-62`) y regla `recordActivityAt(now, settings)` (`Opportunity.java:64-78`); privados `closeAsUnanswered` y `reopen` (`Opportunity.java:112-123`). Sin setters. |
| `OpportunityStatus` | package-private | `OPEN`, `CLOSED` (`OpportunityStatus.java:3-6`). |
| `OpportunityResult` | package-private | `WON`, `LOST`, `NO_RESPONSE` (`OpportunityResult.java:3-7`). En #36 solo se usa `NO_RESPONSE`. |
| `OpportunitySettings` | package-private | Entidad de la tabla `opportunity_settings`: `id`, `@TenantId organizationId`, `inactivityDaysToClose`, `reentryWindowDays` (`OpportunitySettings.java:22-32`). `of(int, int)` valida (`OpportunitySettings.java:42-46`), `defaults()` da 7 y 3 sin persistir (`OpportunitySettings.java:19-20`, `:48-50`), `inactivityEndsAt` y `allowsReentryAt` hacen las cuentas (`OpportunitySettings.java:52-58`). |
| `OpportunityRepository` | package-private | `findFirstByPersonIdOrderByOpenedAtDesc(UUID)` con `@Lock(PESSIMISTIC_WRITE)`: la última oportunidad de la persona, bloqueada para escritura (`OpportunityRepository.java:11-12`). |
| `OpportunitySettingsRepository` | package-private | `findCurrent()`, un JPQL sin filtro (`select s from OpportunitySettings s`) que `@TenantId` limita a la fila de la organización activa (`OpportunitySettingsRepository.java:10-11`). |

El `package-info.java` con `@ApplicationModule` ya existía desde el scaffold y `ModularStructureTest` ya declaraba
`opportunities`. Sin dependencias nuevas ni configuración nueva.

## API y contrato

```java
UUID opportunityId = opportunityTracker.recordActivity(personId, "whatsapp");
```

| Entrada | Salida |
|---|---|
| Persona sin oportunidades | id de una oportunidad nueva, abierta, con esa fuente |
| Persona con una abierta y activa | el id de esa misma, con `lastActivityAt` = ahora (la fuente no cambia) |
| Persona cuya última se cerró (o quedó inactiva) hace menos de W días | el id de esa misma, reabierta |
| Persona cuya última se cerró hace W días o más | id de una nueva; la vieja queda `CLOSED`/`NO_RESPONSE` |
| `personId` nulo | `NullPointerException` |
| `source` nula, vacía o en blanco | `IllegalArgumentException`, sin tocar la base |
| Llamada con sincronización de transacción activa | `IllegalStateException`, sin tocar la base |
| Sin organización activa, o persona de otra organización | `DataIntegrityViolationException`, nada guardado |

Quien llama a `recordActivity`:

1. **No tiene una transacción abierta.** La operación abre la suya; si hay sincronización de transacción activa (una
   transacción real o un ámbito `SUPPORTS`), lanza `IllegalStateException` («Activity is recorded in its own
   transaction, not inside an open one», `OpportunityTracker.java:32-33`). Es la misma comprobación que
   `PersonDirectory.resolveOrCreate` (P-029): en PostgreSQL una violación aborta la transacción en curso y el reintento
   necesita una transacción limpia.
2. **Tiene organización activa:** `currentOrganization.callAs(organizationId, ...)` o un request con operador
   autenticado. Sin organización, la inserción falla por la FK de `organization_id` contra el centinela.
3. **Pasa una persona de la organización activa**, resuelta antes con `PersonDirectory.resolveOrCreate`.
4. **Pasa la fuente** como etiqueta opaca (por ejemplo, el canal o la campaña). Se valida que tenga texto y se guarda
   tal cual, sin recortar; solo se usa al abrir una oportunidad nueva. Una actividad que continúa o reabre no la cambia.
5. **Descarta antes los duplicados**: #10 descarta el mensaje repetido por `externalId` antes de llamar, porque cada
   llamada cuenta como actividad nueva.

Flujo entrante (#10), en tres transacciones (persona, oportunidad, mensaje):

```java
currentOrganization.callAs(organizationId, () -> {
    var personId = personDirectory.resolveOrCreate(identity);               // tx 1
    // #10 descarta acá el duplicado por externalId
    var opportunityId = opportunityTracker.recordActivity(personId, source); // tx 2
    return messages.record(personId, opportunityId, message);                // tx 3 (#8)
});
```

Si la tercera falla, la actividad ya quedó registrada (la oportunidad abierta o con `lastActivityAt` adelantado) sin
mensaje. El reintento del webhook vuelve a llamar a `recordActivity`, que continúa la misma oportunidad.

## Reglas

Con L = última actividad, N = `inactivityDaysToClose`, W = `reentryWindowDays`, C = cierre:

- **Inactiva** cuando `ahora >= L + N` (límite inclusive). El cierre se fecha en `C = L + N`, no a la hora en que se
  detecta.
- **Inactividad desde la última actividad**, no desde la apertura: cada actividad corre el límite.
- **Reingreso medido desde el cierre**: si `ahora < C + W`, se reabre la misma oportunidad; desde `C + W`, se abre una
  nueva. Con `W = 0`, nunca se reabre.
- **Cierre al vuelo**: `recordActivity` cierra la oportunidad vencida antes de decidir, así que el resultado es el
  mismo haya corrido el job (#37) o no.
- **Reapertura**: vuelve a `OPEN`, borra resultado y `closedAt`, pone `lastActivityAt` = ahora y conserva fuente,
  responsable y `openedAt` (`Opportunity.java:118-123`). Aplica sea cual sea el resultado del cierre (D-002 literal);
  ver «Riesgos».
- **Días de 24 h en UTC** (`Duration.ofDays`, `OpportunitySettings.java:53`, `:57`): no se ajustan por horario de
  verano ni por la zona de la organización.
- **Hora de procesamiento**: la actividad se fecha con `clock.instant()`, no con la hora que informa el proveedor.

### Línea de tiempo

Defaults (N = 7, W = 3), L = 2026-03-02 09:00 UTC (el `MutableClock.INITIAL_INSTANT` de los tests):

```
L = 2026-03-02 09:00          L + 7d = C = 2026-03-09 09:00     C + 3d = L + 10d = 2026-03-12 09:00
|---- abierta: la continúa ----|---- reingreso: cierra y reabre ----|---- cierra y abre una nueva ---->
```

| La persona escribe en | Resultado | Test |
|---|---|---|
| L + 7d − 1s | Sigue la misma; `lastActivityAt` = L + 7d − 1s | `activityWithinTheInactivityPeriodContinuesTheOpenOpportunity` |
| L + 5d y después L + 11d | Sigue la misma: el segundo mensaje está a 6 días del primero | `inactivityIsCountedFromTheLastActivityNotFromTheOpening` |
| L + 10d − 1s | Se cierra al vuelo en L + 7d y se reabre: misma id, `openedAt` y fuente | `personReturningJustBeforeTheReentryWindowEndsGetsTheSameOpportunityBack` |
| L + 10d | La vieja queda `CLOSED`/`NO_RESPONSE` con `closedAt` = L + 7d y se abre una nueva | `personReturningWhenTheReentryWindowEndsNeedsANewOpportunity` |
| L + 7d, con W = 0 | La vieja cerrada en L + 7d, y una nueva | `withoutReentryWindowAPersonReturningAfterTheInactivityPeriodNeedsANewOpportunity` |

Filas después de que una persona de Acme escribe por WhatsApp en L y vuelve por Instagram en L + 10d
(`OpportunityTrackerTest.personReturningAfterTheReentryWindowGetsANewOpportunity`):

```
id      | source    | status | result      | opened_at        | last_activity_at | closed_at        | owner_id
<vieja> | whatsapp  | CLOSED | NO_RESPONSE | 2026-03-02 09:00 | 2026-03-02 09:00 | 2026-03-09 09:00 | null
<nueva> | instagram | OPEN   | null        | 2026-03-12 09:00 | 2026-03-12 09:00 | null             | null
```

Si hubiera vuelto en L + 10d − 1s, quedaría una sola fila: `<vieja>`, `whatsapp`, `OPEN`, sin resultado ni
`closed_at`, `opened_at` = L y `last_activity_at` = L + 10d − 1s.

## Diagramas

`recordActivity`:

```mermaid
flowchart TD
    R[recordActivity personId, source] --> V{personId nulo o<br/>source sin texto?}
    V -->|sí| X1[NullPointerException /<br/>IllegalArgumentException]
    V -->|no| G{sincronización de<br/>transacción activa?}
    G -->|sí| X2[IllegalStateException]
    G -->|no| T[transacción con TransactionTemplate:<br/>currentOpportunityOf]
    T --> S[settings.findCurrent<br/>o defaults 7 / 3]
    S --> F[última oportunidad de la persona<br/>findFirstByPersonIdOrderByOpenedAtDesc<br/>con lock de escritura]
    F --> NOW[now = clock.instant]
    NOW --> E{hay una?}
    E -->|no| NEW
    E -->|sí| A{recordActivityAt now, settings}
    A -->|true: continuada o reabierta| ID[id de esa oportunidad]
    A -->|false: queda cerrada| NEW[flush de la vieja<br/>saveAndFlush Opportunity.openFor]
    NEW -->|ok| NID[id de la nueva]
    NEW -->|DataIntegrityViolationException| RB[rollback]
    RB --> RT[un reintento:<br/>currentOpportunityOf en otra transacción]
    RT -->|ok| ID2[id de la oportunidad<br/>que confirmó la otra transacción]
    RT -->|vuelve a fallar| X3[se propaga<br/>p. ej. sin organización activa]
```

`Opportunity.recordActivityAt` (`Opportunity.java:64-78`):

```mermaid
flowchart TD
    O{abierta y<br/>now >= L + N?} -->|sí| C[closeAsUnanswered L + N<br/>CLOSED / NO_RESPONSE]
    O -->|no| O2
    C --> O2{abierta?}
    O2 -->|sí| CONT[lastActivityAt = now<br/>true]
    O2 -->|no| W{now < closedAt + W?}
    W -->|sí| RE[reopen now<br/>true]
    W -->|no| NO[false: hace falta una nueva]
```

## Flujo en detalle

### Validación y transacción (`OpportunityTracker.recordActivity`)

`recordActivity` (`OpportunityTracker.java:29-40`):

1. Rechaza `personId` nulo (`NullPointerException`) y una fuente sin texto (`Assert.hasText`,
   `IllegalArgumentException` «Activity needs the source it came from»), antes de todo (`OpportunityTracker.java:30-31`).
   Así, una fuente vacía se rechaza igual aunque la persona tenga una oportunidad abierta, en la que la fuente no se usa.
2. Rechaza la sincronización de transacción activa (`OpportunityTracker.java:32-33`).
3. Corre `currentOpportunityOf` en una transacción del `TransactionTemplate` (`OpportunityTracker.java:34-36`). Si sale
   `DataIntegrityViolationException`, la transacción ya se deshizo entera y se corre una vez más, en una transacción
   nueva (`OpportunityTracker.java:37-38`). Si el reintento también falla, la excepción se propaga.

### La oportunidad vigente (`currentOpportunityOf`)

`currentOpportunityOf` (`OpportunityTracker.java:42-51`):

1. Lee la configuración de la organización activa o, si no tiene fila, los defaults (`OpportunityTracker.java:43`).
2. Trae la última oportunidad de la persona (por `openedAt` descendente, la primera) con lock pesimista de escritura
   (`OpportunityTracker.java:44`). El filtro de `@TenantId` limita la búsqueda a la organización activa; el índice
   `opportunities_person_id_opened_at_idx` sirve a esta consulta. El SQL que genera Hibernate (con
   `spring.jpa.show-sql` en una corrida de `OpportunityTrackerTest`) es:

   ```sql
   select o1_0.id, ..., o1_0.status from opportunities o1_0
   where o1_0.organization_id = ? and o1_0.person_id = ?
   order by o1_0.opened_at desc fetch first ? rows only for no key update of o1_0
   ```

   `FOR NO KEY UPDATE` choca consigo mismo y con `FOR UPDATE`, así que dos `recordActivity` de la misma persona se
   serializan igual; no bloquea los `FOR KEY SHARE` que toman las FK de otras tablas que apunten a la oportunidad (por
   ejemplo, al insertar mensajes de #8).
3. Recién entonces lee el reloj (`OpportunityTracker.java:45`). Una transacción que esperó el lock lee una hora
   posterior a la de la que lo tenía, así que `lastActivityAt` no retrocede.
4. Si hay oportunidad y `recordActivityAt` devuelve `true` (continuada o reabierta), devuelve su id
   (`OpportunityTracker.java:46-48`). Los cambios los guarda el dirty checking al confirmar.
5. Si no hay oportunidad, o la última quedó cerrada y fuera del reingreso, hace `flush` (la vieja se escribe ya
   cerrada) y después `saveAndFlush(Opportunity.openFor(personId, source, now))`, y devuelve el id nuevo
   (`OpportunityTracker.java:49-50`).

### La regla (`Opportunity.recordActivityAt`)

`recordActivityAt(now, settings)` (`Opportunity.java:64-78`) devuelve `true` si la oportunidad sigue vigente y `false`
si hace falta una nueva (y entonces queda cerrada):

1. Si está abierta y `now` no es anterior a `settings.inactivityEndsAt(lastActivityAt)` (L + N), la cierra como
   `NO_RESPONSE` con `closedAt` = L + N (`Opportunity.java:65-68`, `:112-116`).
2. Si sigue abierta, `lastActivityAt = now` y `true` (`Opportunity.java:69-72`).
3. Si está cerrada (de antes o recién) y `settings.allowsReentryAt(closedAt, now)` (`now < closedAt + W`,
   `OpportunitySettings.java:56-58`), la reabre y `true` (`Opportunity.java:73-76`, `:118-123`).
4. Si no, `false` (`Opportunity.java:77`).

Recibe un `Instant` y no un `Clock`: el servicio lee el reloj una sola vez por llamada. `openFor` exige `personId`
no nulo y fuente con texto (`Opportunity.java:59-60`) y arranca con `openedAt` = `lastActivityAt` = `now`
(`Opportunity.java:50-56`).

## Concurrencia

A lo sumo una oportunidad abierta por persona: lo garantiza el índice único parcial `opportunities_person_id_open_key`
(`V4__create_opportunities.sql:26`). Dentro de eso:

- **El lock serializa** las actividades de una persona que ya tiene oportunidades: la segunda transacción espera a que
  la primera confirme y trabaja sobre la fila ya actualizada. Seguir, reabrir y cerrar no se pisan (tampoco con el job
  de #37, que va a bloquear las mismas filas).
- **Dos carreras terminan en el índice**, y las resuelve el único reintento:
  - **(a) Primera actividad de una persona.** No hay fila que bloquear: las dos transacciones insertan y la segunda
    espera al índice; cuando la primera confirma, falla con 23505.
  - **(b) Última oportunidad cerrada y fuera del reingreso.** Las dos bloquean la misma fila vieja. La primera la
    cierra, inserta la nueva y confirma. La segunda obtiene el lock y ve la fila vieja ya cerrada, pero no la nueva (en
    READ COMMITTED la instantánea de su consulta es de antes de esa confirmación); decide abrir otra y choca con el
    índice.

  En los dos casos el reintento, en una transacción nueva, encuentra la oportunidad del ganador como la última y la
  continúa: nunca vuelve a insertar sobre ella. Por eso alcanza con un reintento. Si falla otra vez, no es una carrera
  (sin organización, persona ajena) y se propaga.

Carrera (a), como la reproduce `concurrentFirstActivitiesOfAPersonOpenASingleOpportunity`:

```mermaid
sequenceDiagram
    participant A as Transacción A (rival)
    participant DB as PostgreSQL
    participant B as recordActivity
    A->>DB: INSERT opportunities (persona, OPEN)
    Note over A,DB: sin confirmar
    B->>DB: SELECT última oportunidad ... FOR NO KEY UPDATE (no ve la fila de A)
    B->>DB: INSERT opportunities (saveAndFlush)
    Note over B,DB: el índice parcial espera a A
    A->>DB: COMMIT
    DB-->>B: 23505 opportunities_person_id_open_key
    Note over B: DataIntegrityViolationException, rollback
    B->>DB: reintento: SELECT ... FOR NO KEY UPDATE
    DB-->>B: la oportunidad de A
    Note over B: recordActivityAt la continúa, devuelve su id
```

Carrera (b):

```mermaid
sequenceDiagram
    participant A as recordActivity A
    participant DB as PostgreSQL
    participant B as recordActivity B
    A->>DB: SELECT última (vieja) FOR NO KEY UPDATE
    B->>DB: SELECT última FOR NO KEY UPDATE
    Note over B,DB: espera el lock de A
    A->>DB: UPDATE vieja CLOSED, INSERT nueva OPEN, COMMIT
    DB-->>B: la vieja, ya CLOSED (la nueva no entra en su instantánea)
    B->>DB: INSERT otra OPEN
    DB-->>B: 23505 opportunities_person_id_open_key
    Note over B: rollback y reintento
    B->>DB: SELECT última FOR NO KEY UPDATE
    DB-->>B: la nueva de A
    Note over B: la continúa
```

**La trampa del orden de flush.** Al hacer flush, Hibernate ejecuta los `INSERT` antes que los `UPDATE`. Sin el
`opportunities.flush()` explícito (`OpportunityTracker.java:49`), la nueva se insertaría con la vieja todavía `OPEN` en
la base, y el índice parcial rechazaría a la propia transacción. Lo cubre
`personReturningAfterTheReentryWindowGetsANewOpportunity`, que pasa por ese camino sin concurrencia.

Descartadas (P-035): `@Version` con N reintentos; unirse a la transacción del llamador y bloquear la fila de `people`
(tabla de otro módulo); advisory lock; `SERIALIZABLE`.

## Configuración

Tabla `opportunity_settings`, una fila por organización (P-036):

| Columna | Dominio | Default (sin fila) | Restricción |
|---|---|---|---|
| `inactivity_days_to_close` | `inactivityDaysToClose`: N | 7 | ≥ 1 (`OpportunitySettings.of` y `CHECK`) |
| `reentry_window_days` | `reentryWindowDays`: W | 3 | ≥ 0; 0 = nunca reabre |

Sin API. En desarrollo se carga por SQL:

```sql
INSERT INTO opportunity_settings (id, organization_id, inactivity_days_to_close, reentry_window_days)
VALUES (gen_random_uuid(), '<organization_id>', 15, 3);
```

La configuración se lee en cada llamada, así que un cambio vale desde la siguiente actividad y se aplica también a las
oportunidades ya abiertas (el cierre se recalcula con la N nueva). La configuración por campaña de D-002 se suma cuando
existan campañas.

## Esquema

`backend/src/main/resources/db/migration/V4__create_opportunities.sql`:

```sql
ALTER TABLE operators ADD CONSTRAINT operators_id_organization_id_key UNIQUE (id, organization_id);

CREATE TABLE opportunities (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations (id),
    person_id UUID NOT NULL,
    owner_id UUID,
    source TEXT NOT NULL,
    status TEXT NOT NULL,
    result TEXT,
    opened_at TIMESTAMPTZ NOT NULL,
    last_activity_at TIMESTAMPTZ NOT NULL,
    closed_at TIMESTAMPTZ,
    CONSTRAINT opportunities_person_id_organization_id_fkey
        FOREIGN KEY (person_id, organization_id) REFERENCES people (id, organization_id),
    CONSTRAINT opportunities_owner_id_organization_id_fkey
        FOREIGN KEY (owner_id, organization_id) REFERENCES operators (id, organization_id),
    CONSTRAINT opportunities_closure_check CHECK (
        (status = 'OPEN' AND result IS NULL AND closed_at IS NULL)
        OR (status = 'CLOSED' AND result IS NOT NULL AND closed_at IS NOT NULL))
);

CREATE INDEX opportunities_organization_id_idx ON opportunities (organization_id);
CREATE INDEX opportunities_person_id_opened_at_idx ON opportunities (person_id, opened_at DESC);
CREATE INDEX opportunities_owner_id_organization_id_idx ON opportunities (owner_id, organization_id);
CREATE UNIQUE INDEX opportunities_person_id_open_key ON opportunities (person_id) WHERE status = 'OPEN';

CREATE TABLE opportunity_settings (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations (id),
    inactivity_days_to_close INTEGER NOT NULL,
    reentry_window_days INTEGER NOT NULL,
    CONSTRAINT opportunity_settings_organization_id_key UNIQUE (organization_id),
    CONSTRAINT opportunity_settings_inactivity_days_to_close_check CHECK (inactivity_days_to_close > 0),
    CONSTRAINT opportunity_settings_reentry_window_days_check CHECK (reentry_window_days >= 0)
);
```

- `operators_id_organization_id_key` (`V4__create_opportunities.sql:1`) es redundante con la clave primaria de
  `operators`, pero hace falta como destino de la FK compuesta del responsable. Es un cambio sobre una tabla de otro
  módulo (ver «Riesgos»).
- Las FK compuestas `opportunities_person_id_organization_id_fkey` y `opportunities_owner_id_organization_id_fkey`
  (`V4__create_opportunities.sql:14-17`) garantizan que la persona y el responsable sean de la misma organización que la
  oportunidad, aunque alguien escriba por SQL. Con `MATCH SIMPLE` (el default), un `owner_id` nulo no se controla, que
  es lo esperado mientras no haya responsable.
- `opportunities_closure_check` (`V4__create_opportunities.sql:18-20`) es el mismo invariante que mantiene la entidad:
  abierta sin resultado ni cierre; cerrada con los dos.
- `opportunities_person_id_open_key` (`V4__create_opportunities.sql:26`) es el índice único parcial de P-035. Va solo
  por `person_id`, sin `organization_id`: una persona es de una sola organización.
- `opportunities_person_id_opened_at_idx` (`V4__create_opportunities.sql:24`) sirve a la búsqueda de la última
  oportunidad; `opportunities_owner_id_organization_id_idx` (`:25`) indexa la FK del responsable, para el borrado o
  la actualización de operadores y las consultas por responsable de #13.
- `status` y `result` son `TEXT` sin `CHECK` de valores, como `operators.role` y `person_identities.kind`.

## Tests

`backend/src/test/java/com/communicatorone/opportunities/OpportunityTest.java` (sin Spring; N = 7, W = 3 salvo donde
se indica, apertura en `MutableClock.INITIAL_INSTANT`). Verifican el estado completo: estado, resultado, `closedAt`,
fuente, `openedAt` y `lastActivityAt`.

| Test | Qué cubre |
|---|---|
| `activityWithinTheInactivityPeriodContinuesTheOpenOpportunity` | Actividad en L + 7d − 1s: `true`, sigue abierta con `lastActivityAt` = ahora. |
| `inactivityIsCountedFromTheLastActivityNotFromTheOpening` | Actividad en T + 5d y en T + 11d: sigue abierta (la inactividad se cuenta desde la última actividad). |
| `personReturningJustBeforeTheReentryWindowEndsGetsTheSameOpportunityBack` | Actividad en L + 10d − 1s: cierre al vuelo y reapertura; `true`, abierta, sin resultado ni `closedAt`, misma `openedAt` y fuente. |
| `personReturningWhenTheReentryWindowEndsNeedsANewOpportunity` | Actividad en L + 10d (límite de la ventana): `false`; `CLOSED`, `NO_RESPONSE`, `closedAt` = L + 7d, `lastActivityAt` sin cambiar. |
| `withoutReentryWindowAPersonReturningAfterTheInactivityPeriodNeedsANewOpportunity` | Con W = 0, actividad en L + 7d (límite de la inactividad): `false`; cerrada en L + 7d. |
| `opportunityCannotOpenWithoutSource` | Fuente nula, vacía o en blanco: `IllegalArgumentException`. |

`backend/src/test/java/com/communicatorone/opportunities/OpportunitySettingsTest.java` (sin Spring):

| Test | Qué cubre |
|---|---|
| `settingsWithoutInactivityPeriodOrWithNegativeReentryWindowAreRejected` | (0, 3), (−1, 3) y (7, −1): `IllegalArgumentException`. |
| `oneDayOfInactivityWithoutReentryWindowIsTheShortestAcceptedSetting` | (1, 0) se acepta: la inactividad termina a las 24 h y en ese mismo instante ya no hay reingreso. |

`backend/src/test/java/com/communicatorone/opportunities/OpportunityTrackerTest.java` (`@SpringBootTest` con
`RANDOM_PORT` + Testcontainers, sin `@Transactional`; reloj fijo en `MutableClock.INITIAL_INSTANT` y movido con
`MutableClock`). Cada test crea sus organizaciones y personas (estas con `PersonDirectory.resolveOrCreate`) y registra
con `callAs` (`recordIn`). Las filas se leen con `OpportunitiesFixture` (`JdbcTemplate`, filtrando por
`organization_id` salvo `opportunitiesInAnyOrganizationOf`), comparando la fila entera.

| Test | Qué cubre |
|---|---|
| `firstActivityOfAPersonOpensAnOpportunity` | Una fila `OPEN`, sin resultado ni `closed_at` ni responsable, con la fuente y `opened_at` = `last_activity_at` = ahora, en la organización del ámbito. |
| `activityOfAPersonWithAnOpenOpportunityContinuesIt` | A los 2 días, por otra fuente: mismo id, una sola fila, `last_activity_at` adelantado, fuente original. |
| `personReturningWithinTheReentryWindowGetsTheSameOpportunityBack` | Con defaults, vuelta en L + 10d − 1s por otra fuente: mismo id, una fila `OPEN` con la fuente original. |
| `personReturningAfterTheReentryWindowGetsANewOpportunity` | Vuelta en L + 10d: la vieja `CLOSED`/`NO_RESPONSE` con `closed_at` = L + 7d y una nueva `OPEN` con la fuente nueva. Cubre la trampa del orden de flush. |
| `eachOrganizationAppliesItsOwnOpportunitySettings` | Acme con (1, 0) y Globex sin fila (defaults): a los 2 días, Acme abre una nueva (la vieja cerrada en L + 1d) y Globex continúa la suya. |
| `personOfAnotherOrganizationCannotRecordActivity` | Con ámbito Globex y una persona de Acme sin oportunidades: `DataIntegrityViolationException` por la FK compuesta (la salida capturada nombra `opportunities_person_id_organization_id_fkey`); ninguna oportunidad de esa persona en ninguna organización. |
| `openOpportunityOfAPersonIsNotContinuedFromAnotherOrganization` | Con ámbito Globex y una persona de Acme que tiene una abierta en Acme: Globex no la ve (`@TenantId`), intenta abrir otra y la rechaza el índice único parcial, que no distingue organización (`DataIntegrityViolationException`); Globex sigue sin oportunidades y la de Acme no cambia. |
| `activityCannotBeRecordedWithoutOrganization` | Sin ámbito ni usuario: `DataIntegrityViolationException` y ninguna oportunidad de esa persona en ninguna organización. |
| `activityCannotBeRecordedInsideAnOpenTransactionalScope` | Parametrizado con `Propagation.REQUIRED` y `Propagation.SUPPORTS`: `IllegalStateException` y nada guardado. |
| `activityWithoutSourceIsRejected` | Fuente nula, vacía o en blanco: `IllegalArgumentException` y nada guardado. |
| `activityWithoutSourceIsRejectedEvenWhileThePersonHasAnOpenOpportunity` | Lo mismo con una oportunidad abierta: se rechaza y la oportunidad no cambia. |
| `concurrentFirstActivitiesOfAPersonOpenASingleOpportunity` | La carrera (a), determinista: un hilo rival inserta una oportunidad `OPEN` sin confirmar (`OpportunitiesFixture.rivalStartsOpeningOpportunity`); `recordActivity` corre en otro hilo; Awaitility espera en `pg_stat_activity` otra sesión bloqueada por un lock con un `insert into opportunities` (`anotherSessionIsWaitingToWriteOpportunities`) y recién ahí confirma el rival. Afirma que devuelve el id del rival, que queda una sola fila `OPEN` (la del rival) y que la salida capturada nombra `opportunities_person_id_open_key`. |
| `opportunityOwnerMustBeAnOperatorOfTheSameOrganization` | Poner por SQL (`OpportunitiesFixture.assignOwner`) un operador de Globex como responsable de una oportunidad de Acme lanza `DataIntegrityViolationException` (FK compuesta) y la oportunidad no cambia. |

La carrera (b) no tiene un test propio.

`TenantIsolationTest.everyBusinessEntityBelongsToAnOrganization` cubre que `Opportunity` y `OpportunitySettings` lleven
`@TenantId`, y `ModularStructureTest` que `opportunities` no dependa de internos de otros módulos.

## Riesgos y pendientes

- **Reabrir una `WON` o `LOST` pierde el resultado.** El reingreso aplica sea cual sea el resultado (D-002 literal) y
  `reopen` lo borra. Hoy no pasa porque solo existe `NO_RESPONSE`; con el cierre manual (#15) importa. Propuesta para el
  dueño en P-034: que `WON`/`LOST` no se reabran.
- **V4 agrega un `UNIQUE (id, organization_id)` a `operators`**, tabla del módulo `operators`, para la FK del
  responsable. Alternativa si molesta: agregar esa FK con #13.
- **Tres transacciones en #10**: si falla el guardado del mensaje, la actividad queda registrada sin mensaje. El
  reintento del webhook continúa la misma oportunidad, pero adelanta otra vez `last_activity_at`.
- **Hora de procesamiento, no del proveedor**: un webhook que llega tarde (reintentos del proveedor) cuenta como
  actividad de cuando se procesa. Con varias instancias, un desfase de relojes entre ellas podría hacer retroceder
  `last_activity_at`.
- **Cambios de configuración retroactivos**: bajar N cierra al vuelo, en la siguiente actividad, oportunidades que con
  la N anterior seguían abiertas, con un `closed_at` en el pasado.
- **Cierres sin persistir hasta #37**: una oportunidad vencida que nadie toca sigue `OPEN` en la base hasta que el job
  la cierre; quien lea la tabla directamente la ve abierta.
- **El responsable** (`owner_id`) queda siempre nulo hasta el reparto (#13), y no hay eventos hasta entonces.
- El test de la carrera depende de `pg_stat_activity` (PostgreSQL).

## Pendiente: #37 y #13

- **#37 (job de cierre)**: va a cerrar en la base las oportunidades abiertas cuya inactividad venció, con el mismo
  `closedAt` = L + N que el cierre al vuelo (reusando `closeAsUnanswered`), bloqueando las filas para no pisarse con
  `recordActivity`.
- **#13 (reparto)**: asigna el responsable de las oportunidades nuevas (la columna y su FK ya están) y publica los
  eventos que necesite.
