# Mensajes y línea de tiempo

Issue #8. Decisiones: D-001 (cada mensaje queda asociado a la persona y a la oportunidad abierta en ese momento) y
P-038 (módulo y API), P-039 (cuenta de canal sin FK en V6), P-040 (modelo), P-041 (estados), P-042 (idempotencia y
concurrencia) en `docs/decisiones.md`, más P-045 (#42: FK de tres columnas a `channel_accounts`, en lugar de la de dos
que anunciaba P-039). Se apoya en `docs/multi-tenant.md`, en las personas de `docs/personas.md`, en las oportunidades
de `docs/oportunidades.md` y en las cuentas de canal de `docs/canales.md`.

## Resumen

Un `Message` es un mensaje de la línea de tiempo de una persona: lo que escribió ella (entrante) o lo que le mandó la
organización (saliente, de un operador, un bot o el sistema). Cada mensaje guarda la persona, la oportunidad en la que
cayó, la cuenta de canal por la que pasó (nuestro número, página o bot), el canal, el id que le dio el proveedor
(`externalId`), el texto y el estado de entrega.

El módulo `messaging` expone un único servicio, `Timeline`, con cuatro operaciones:

- `recordReceived(ReceivedMessage)`: registra un mensaje entrante. Nace `DELIVERED`, fechado con la hora que informa el
  proveedor.
- `recordSent(SentMessage)`: registra un saliente que el proveedor ya aceptó (ya devolvió su id). Nace `SENT`, fechado
  con el reloj.
- `applyStatus(channelAccountId, externalId, status)`: aplica un reporte de estado del proveedor (entregado, leído,
  fallido, borrado). El estado solo avanza: un reporte que llega tarde no lo hace retroceder.
- `isRecorded(channelAccountId, externalId)`: dice si ese mensaje ya está registrado, para que #10 descarte un webhook
  repetido antes de contar actividad en la oportunidad.

Registrar es **idempotente por cuenta de canal + `externalId`**: los proveedores reenvían webhooks, y el mismo mensaje
registrado dos veces (también en paralelo) da una sola fila. La respuesta, `RecordedMessage(id, duplicate)`, dice si el
mensaje ya estaba; eso es lo que #10 usa para publicar el evento «mensaje recibido» una sola vez.

Se va a activar desde el flujo entrante (#10), con cada webhook de mensaje; desde el flujo saliente (#12), al enviar; y
con cada webhook de estado. Hoy nadie la llama fuera de los tests.

Fuera de #8: la tabla `channel_accounts` y su FK desde `messages` (#42, `docs/canales.md`), el puerto de canal y el
adaptador `SANDBOX` (#43, `docs/canales.md`), el pipeline entrante y la llamada a `recordActivity` (#10), adjuntos,
plantillas y ubicación (#11, #12), la ventana de conversación y el envío (#12), la consulta de la línea de tiempo,
REST, WebSocket y los no leídos del operador (#15), y los eventos (P-009).

## Piezas

Módulo `messaging` (`backend/src/main/java/com/communicatorone/messaging/`). No depende de ningún otro módulo: la
persona, la oportunidad y la cuenta de canal llegan como `UUID` (P-019), y la base controla que sean coherentes con FK
compuestas; la organización la asigna y filtra Hibernate con `@TenantId` (P-018). Es un módulo base: `channels`
depende de él (desde #42, por `ChannelType`), y la orquestación de entrada (#10) y de salida (#12) vive en
`channels` o en un módulo por encima, nunca en `messaging`, porque eso armaría un ciclo (P-038).

| Clase | Visibilidad | Qué hace |
|---|---|---|
| `Timeline` | pública (API) | `@Service` con `recordReceived` (`Timeline.java:26-29`), `recordSent` (`Timeline.java:31-34`), `applyStatus`, `@Transactional` (`Timeline.java:36-45`), e `isRecorded`, `@Transactional(readOnly = true)` (`Timeline.java:47-51`). El registro lo hace el privado `record` (`Timeline.java:63-76`); `applyStatus` e `isRecorded` validan la cuenta y el `externalId` con el privado `requireProviderKey` (`Timeline.java:53-56`). Recibe `MessageRepository`, el bean `TransactionTemplate` de Spring Boot y `Clock` en el constructor package-private (`Timeline.java:20-24`). |
| `ReceivedMessage` | pública (API) | `record (personId, opportunityId, channelAccountId, ChannelType channel, String externalId, MessageContent content, Instant occurredAt)` (`ReceivedMessage.java:6-7`). Sin validación propia: la hace `Message`. |
| `SentMessage` | pública (API) | `record (personId, opportunityId, channelAccountId, ChannelType channel, SenderType senderType, String externalId, MessageContent content)` (`SentMessage.java:5-6`). Sin hora: la pone el reloj. |
| `MessageContent` | pública (API) | `record (String text)`; el constructor compacto exige texto con `Assert.hasLength` («A message needs its text», `MessageContent.java:7-9`): rechaza nulo y vacío, acepta espacios y no recorta. `toString` devuelve `MessageContent[text]`, sin el texto (`MessageContent.java:11-14`). Hoy es solo texto. |
| `RecordedMessage` | pública (API) | `record (UUID id, boolean duplicate)` (`RecordedMessage.java:5`): el id del mensaje y si ya estaba registrado. |
| `ChannelType` | pública (API) | `WHATSAPP`, `INSTAGRAM`, `MESSENGER`, `TELEGRAM`, `EMAIL`, `SMS`, `WEBCHAT` y, desde #43, `SANDBOX`, el canal de prueba (`ChannelType.java:3-12`). Vive en `messaging` para que `channels` dependa de `messaging` y no al revés. |
| `SenderType` | pública (API) | `CUSTOMER`, `OPERATOR`, `BOT`, `SYSTEM` (`SenderType.java:3-8`). `CUSTOMER` es el único entrante; no hay columna de dirección. |
| `MessageStatus` | pública (API) | `SENT(0)`, `FAILED(1)`, `DELIVERED(2)`, `READ(3)`, `DELETED(4)`, con el rango en un campo `progress` (`MessageStatus.java:3-14`). El enum es público, pero `isAheadOf(other)` (`true` si el rango es mayor, `MessageStatus.java:16-18`) es package-private: la regla de avance solo la usa `Message.advanceTo`. |
| `StatusUpdateOutcome` | pública (API) | `ADVANCED`, `UNCHANGED`, `UNKNOWN_MESSAGE` (`StatusUpdateOutcome.java:3-7`). |
| `Message` | package-private | Entidad de la tabla `messages`: `id`, `@TenantId organizationId`, `personId`, `opportunityId`, `channelAccountId`, `channel`, `senderType` y `status` (guardados por nombre), `externalId`, `text`, `occurredAt`, `recordedAt` (`Message.java:17-50`). Fábricas `received(ReceivedMessage, now)` (`Message.java:71-76`) y `sent(SentMessage, now)` (`Message.java:78-85`), que pasan por el constructor privado que valida (`Message.java:55-69`); regla `advanceTo(MessageStatus)` (`Message.java:87-94`). Sin setters. |
| `MessageRepository` | package-private | `findIdByChannelAccountIdAndExternalId`, JPQL que devuelve solo el id (`MessageRepository.java:13-15`), y `lockByChannelAccountIdAndExternalId`, JPQL explícita con `@Lock(PESSIMISTIC_WRITE)` (`MessageRepository.java:17-20`); el nombre avisa que bloquea la fila. Las dos pasan por `@TenantId`. |

El `package-info.java` con `@ApplicationModule` ya existía desde el scaffold y `ModularStructureTest` ya declaraba
`messaging`. #8 suma la migración V6 (ver «Esquema»), que además agrega una restricción a `opportunities`.

Los nombres `InboundMessage` y `StatusUpdate` son los de lo que devuelve el adaptador de canal (records
package-private de `channels` desde #43, `docs/canales.md`, «Puerto de canal»); `ReceivedMessage` y `SentMessage` son
los comandos de `messaging`.

## API y contrato

```java
RecordedMessage recorded = timeline.recordReceived(new ReceivedMessage(personId, opportunityId, channelAccountId,
        ChannelType.WHATSAPP, "wamid.HBgNNTQ5MTE1NTU1MDEwMRUCABIYFjNFQjBDMEEz", new MessageContent("Hola, ¿tienen financiación?"),
        Instant.parse("2026-03-02T08:59:20Z")));
// RecordedMessage[id=7c1f…, duplicate=false]

timeline.recordSent(new SentMessage(personId, opportunityId, channelAccountId, ChannelType.WHATSAPP,
        SenderType.OPERATOR, "wamid.HBgNNTQ5MTE1NTU1MDEwMRUCABEYEjk4", new MessageContent("Sí, hasta 12 cuotas")));

StatusUpdateOutcome outcome = timeline.applyStatus(channelAccountId, "wamid.HBgNNTQ5MTE1NTU1MDEwMRUCABEYEjk4",
        MessageStatus.READ);
// ADVANCED

boolean known = timeline.isRecorded(channelAccountId, "wamid.HBgNNTQ5MTE1NTU1MDEwMRUCABIYFjNFQjBDMEEz");
// true
```

Pasar del payload del proveedor (por ejemplo, un `statuses[].status = "delivered"` de WhatsApp) a estos comandos es
trabajo del adaptador de canal (puerto y sandbox en #43, WhatsApp en #11; `docs/canales.md`) y del pipeline de #10:
`messaging` no conoce payloads.

| Operación y entrada | Salida |
|---|---|
| `recordReceived`, cuenta + `externalId` nuevos | `RecordedMessage(id nuevo, false)`; fila `CUSTOMER`, `DELIVERED`, `occurred_at` = hora del proveedor, `recorded_at` = reloj |
| `recordReceived`, cuenta + `externalId` ya registrados | `RecordedMessage(id existente, true)`; no escribe nada y descarta el contenido nuevo (gana la primera escritura) |
| `recordReceived`, mismo `externalId` en otra cuenta de canal | otro mensaje, `duplicate = false` |
| `recordReceived`, el mismo mensaje registrándose en paralelo | espera a la otra transacción; si confirma, `RecordedMessage(id del ganador, true)`; si se deshace, inserta el suyo (`false`) |
| `recordSent` con `OPERATOR`, `BOT` o `SYSTEM` | `RecordedMessage(id nuevo, false)`; fila `SENT`, `occurred_at` = `recorded_at` = reloj. Repetido: `(id existente, true)` |
| `recordSent` con `CUSTOMER` | `IllegalArgumentException` («A sent message comes from the organization, not the customer»), sin tocar la base |
| `record*` con `externalId` nulo, vacío o en blanco | `IllegalArgumentException` («A message needs the id its provider gave it»), sin tocar la base |
| `record*` con el comando nulo, o con persona, oportunidad, cuenta, canal, tipo de remitente, contenido u `occurredAt` nulos | `NullPointerException` con el nombre del campo (`received` o `sent` para el comando), sin tocar la base |
| `new MessageContent(null)` o `new MessageContent("")` | `IllegalArgumentException` («A message needs its text»), antes de llamar |
| `record*` con sincronización de transacción activa | `IllegalStateException` («A message is recorded in its own transaction, not inside an open one»), sin tocar la base |
| `record*` sin organización activa | `DataIntegrityViolationException` (FK `messages_organization_id_fkey` contra el centinela), nada guardado |
| `record*` con una persona o una oportunidad de otra organización | `DataIntegrityViolationException` (FK compuestas), nada guardado |
| `record*` con una oportunidad de otra persona de la misma organización | `DataIntegrityViolationException` (`messages_opportunity_id_person_id_organization_id_fkey`), nada guardado |
| `record*` con una cuenta de canal de otra organización, una que no existe, o con un `channel` distinto del tipo de la cuenta | `DataIntegrityViolationException` (`messages_channel_account_id_organization_id_channel_fkey`, V7, P-045), nada guardado |
| `applyStatus` con un estado de rango mayor al actual | `ADVANCED`; el estado cambia al confirmar |
| `applyStatus` con el mismo estado o uno de rango menor | `UNCHANGED`, sin cambios |
| `applyStatus` de un mensaje que no está (otra cuenta, otra organización o sin organización) | `UNKNOWN_MESSAGE`, sin cambios; el reporte se descarta |
| `applyStatus` con cuenta o estado nulos | `NullPointerException` |
| `applyStatus` con `externalId` sin texto | `IllegalArgumentException` («A message is recognized by the id its provider gave it»), el mismo texto que `isRecorded` |
| `isRecorded` de un mensaje registrado en esa cuenta y organización | `true` |
| `isRecorded` de otro `externalId`, otra cuenta u otra organización | `false` |
| `isRecorded` con cuenta nula o `externalId` sin texto | `NullPointerException` / `IllegalArgumentException` («A message is recognized by the id its provider gave it») |

Quien llama a `recordReceived` o `recordSent`:

1. **No tiene una transacción abierta.** La operación abre la suya; con sincronización de transacción activa (una
   transacción real o un ámbito `SUPPORTS`) lanza `IllegalStateException` (`Timeline.java:58-61`). Es la misma
   comprobación que `PersonDirectory.resolveOrCreate` (P-029) y `OpportunityTracker.recordActivity` (P-035): en
   PostgreSQL una violación aborta la transacción en curso, y la relectura del duplicado necesita una transacción
   limpia.
2. **Tiene organización activa**: `currentOrganization.callAs(organizationId, ...)` o un request con operador
   autenticado. #10 tiene que ligar la organización (resuelta desde la cuenta de canal con
   `ChannelAccountDirectory.resolve`, `docs/canales.md`) **antes** de llamar. Sin organización, el resolver devuelve el
   centinela `UUID(0, 0)` (`OrganizationTenantResolver.java:13`, `:23`): registrar falla por la FK de
   `organization_id` y `applyStatus` devuelve `UNKNOWN_MESSAGE`, porque la búsqueda filtra por el centinela.
3. **Pasa una persona y una oportunidad coherentes**: la persona de `PersonDirectory.resolveOrCreate` y la oportunidad
   que devolvió `OpportunityTracker.recordActivity` para esa persona. La base rechaza una oportunidad de otra persona u
   organización, pero no controla que esté abierta.
4. **Pasa un `externalId` único por cuenta de canal** (contrato del puerto, P-042). Si el id del proveedor solo es
   único dentro de un chat, el adaptador lo compone: por ejemplo, Telegram `chatId:messageId`.
5. **Pasa una cuenta de canal de la organización activa y su canal.** `channel` repite el tipo de la
   `ChannelAccount`, y desde #42 la base controla que coincidan: la FK de tres columnas
   `messages_channel_account_id_organization_id_channel_fkey` rechaza una cuenta que no existe, de otra organización o
   de otro tipo (P-045). #10 pasa `account.channelType()` de la cuenta que resolvió (`docs/canales.md`).

`applyStatus` es `@Transactional` con la propagación por defecto: si el llamador tiene una transacción, se une a ella
y el lock de la fila dura hasta que esa transacción termine. `isRecorded` es `@Transactional(readOnly = true)`: su
transacción de solo lectura termina antes de volver, así que no le deja una abierta al `recordReceived` que sigue; si
el llamador ya tiene una transacción, se une a ella.

Flujo entrante (#10), en tres transacciones de escritura (persona, oportunidad, mensaje), más la de solo lectura de
`isRecorded`:

```java
currentOrganization.callAs(organizationId, () -> {
    var personId = personDirectory.resolveOrCreate(identity);                     // tx 1
    if (timeline.isRecorded(channelAccountId, externalId)) {                      // solo lectura
        return null;                                                              // reenvío: no cuenta como actividad
    }
    var opportunityId = opportunityTracker.recordActivity(personId, source);      // tx 2
    var recorded = timeline.recordReceived(new ReceivedMessage(personId, opportunityId, channelAccountId,
            channel, externalId, content, occurredAt));                           // tx 3
    // #10 publica «mensaje recibido» solo si !recorded.duplicate()
    return recorded;
});
```

`isRecorded` no es autoritativo: dos copias del mismo webhook en paralelo pueden pasar las dos el chequeo y llamar las
dos a `recordActivity`. Lo autoritativo es `RecordedMessage.duplicate`: una sola de las dos recibe `false`.

## Reglas

### Estados

El estado refleja **solo lo que informa el proveedor**. El leído o no leído del operador (#15) es otro concepto y no va
en `status`.

Cada estado tiene un rango y un reporte se aplica solo si su rango es mayor que el del estado actual
(`Message.java:87-94`, `MessageStatus.java:16-18`). Así un reporte que llega tarde o repetido no cambia nada, sin
importar el orden en que el proveedor mande los webhooks.

| Estado | Rango | Cómo se llega | Reportes posteriores que lo mueven | Reportes posteriores que se ignoran |
|---|---|---|---|---|
| `SENT` | 0 | Saliente al registrarse (`recordSent`) | `FAILED`, `DELIVERED`, `READ`, `DELETED` | `SENT` |
| `FAILED` | 1 | Reporte de falla de un saliente | `DELIVERED`, `READ`, `DELETED` (el proveedor reintentó y entregó) | `SENT`, `FAILED` |
| `DELIVERED` | 2 | Reporte de entrega; los entrantes nacen así (`recordReceived`) | `READ`, `DELETED` | `SENT`, `FAILED` (falla tardía tras la entrega), `DELIVERED` |
| `READ` | 3 | Reporte de lectura | `DELETED` | `SENT`, `FAILED`, `DELIVERED` (entrega que llega después de la lectura), `READ` |
| `DELETED` | 4 | Reporte de borrado | ninguno: es terminal | todos |

```mermaid
stateDiagram-v2
    [*] --> SENT: recordSent
    [*] --> DELIVERED: recordReceived
    SENT --> FAILED
    SENT --> DELIVERED
    FAILED --> DELIVERED
    DELIVERED --> READ
    SENT --> READ
    FAILED --> READ
    SENT --> DELETED
    FAILED --> DELETED
    DELIVERED --> DELETED
    READ --> DELETED
    DELETED --> [*]
```

- **El rango aplica igual a entrantes y salientes**: `applyStatus` no mira la dirección. Un entrante nace `DELIVERED`
  (interpretación propia: si llegó, está entregado), así que solo puede pasar a `READ` o `DELETED`.
- **Mensaje desconocido**: el reporte se descarta y devuelve `UNKNOWN_MESSAGE`; no hay tabla de pendientes. Ver
  «Riesgos» (estados que llegan antes que el saliente).

### Registro

- **Idempotencia por cuenta + `externalId`** dentro de la organización (`UNIQUE (organization_id, channel_account_id,
  external_id)`). El mismo `externalId` en otra cuenta es otro mensaje.
- **Gana la primera escritura**: un duplicado devuelve el id del que ya estaba y descarta su propio contenido, hora,
  persona y oportunidad, aunque difieran. Tampoco mira la dirección: un saliente con el mismo `externalId` que un
  entrante de la misma cuenta se toma como duplicado de ese entrante.
- **Horas**: el entrante guarda `occurred_at` = la hora que informa el proveedor (sin validar) y `recorded_at` = el
  reloj; el saliente guarda las dos con el reloj (`Message.java:71-85`). El orden de la línea de tiempo es
  `occurred_at`, `recorded_at`, `id` (índice de V6; la consulta llega con #15).
- **El saliente se registra después de enviar**, cuando el proveedor ya devolvió su id: `external_id` es `NOT NULL`.
- **Solo texto**, sin recortar y sin límite de largo. `MessageContent.toString` no muestra el texto, para que loguear un
  comando no filtre datos personales.
- **Validación antes de la base**: `Message` valida en el constructor (`Message.java:58-68`) y `recordSent` rechaza
  `CUSTOMER` (`Message.java:80-82`) antes de buscar el duplicado; un comando inválido nunca toca la base.

## Diagramas

`recordReceived` / `recordSent` (`Timeline.java:26-34`, `:63-76`):

```mermaid
flowchart TD
    R[recordReceived / recordSent] --> G{sincronización de<br/>transacción activa?}
    G -->|sí| X1[IllegalStateException]
    G -->|no| M[Message.received / Message.sent<br/>now = clock.instant]
    M -->|externalId sin texto, CUSTOMER en sent| X2[IllegalArgumentException]
    M -->|comando o campo nulo| X3[NullPointerException]
    M -->|válido| F{findIdByChannelAccountIdAndExternalId<br/>sin transacción, organización activa}
    F -->|está| D[RecordedMessage id, true]
    F -->|no está| T[TransactionTemplate:<br/>saveAndFlush]
    T -->|ok| NEW[RecordedMessage id nuevo, false]
    T -->|DataIntegrityViolationException| RB[rollback]
    RB --> F2{relee sin transacción}
    F2 -->|está: lo confirmó otra| D2[RecordedMessage id del ganador, true]
    F2 -->|no está: no era una carrera<br/>FK, sin organización| X4[relanza la excepción]
```

`applyStatus` (`Timeline.java:36-45`):

```mermaid
flowchart TD
    A[applyStatus channelAccountId, externalId, status] --> V{nulos o externalId<br/>sin texto?}
    V -->|sí| X[NullPointerException /<br/>IllegalArgumentException]
    V -->|no| T[transacción @Transactional]
    T --> F[lockByChannelAccountIdAndExternalId<br/>FOR NO KEY UPDATE, organización activa]
    F -->|no está| U[UNKNOWN_MESSAGE]
    F -->|está| AD{advanceTo status:<br/>rango mayor que el actual?}
    AD -->|sí| ADV[status = reportado<br/>ADVANCED, UPDATE al confirmar]
    AD -->|no| UN[UNCHANGED]
```

Carrera de duplicados, como la reproduce `concurrentDuplicatedWebhooksRecordASingleMessage`:

```mermaid
sequenceDiagram
    participant A as Transacción A (rival)
    participant DB as PostgreSQL
    participant B as recordReceived
    A->>DB: INSERT messages (cuenta, externalId)
    Note over A,DB: sin confirmar
    B->>DB: SELECT id por cuenta + externalId (sin transacción)
    DB-->>B: nada (no ve la fila de A)
    B->>DB: INSERT messages (saveAndFlush)
    Note over B,DB: el UNIQUE espera a A
    A->>DB: COMMIT
    DB-->>B: 23505 messages_organization_id_channel_account_id_external_id_key
    Note over B: DataIntegrityViolationException, rollback
    B->>DB: SELECT id por cuenta + externalId (sin transacción)
    DB-->>B: el id de A
    Note over B: RecordedMessage(id de A, true)
```

Carrera de estados, como la reproduce `deliveryReportArrivingWhileAReadReportIsBeingAppliedDoesNotMoveTheStatusBack`
(ahí A es un hilo rival que hace el `UPDATE` a `READ` sin confirmar):

```mermaid
sequenceDiagram
    participant A as Reporte READ (rival)
    participant DB as PostgreSQL
    participant B as applyStatus DELIVERED
    A->>DB: UPDATE messages SET status = 'READ'
    Note over A,DB: sin confirmar, fila bloqueada
    B->>DB: SELECT ... FOR NO KEY UPDATE
    Note over B,DB: espera el lock de A
    A->>DB: COMMIT
    DB-->>B: la fila, ya READ
    Note over B: advanceTo(DELIVERED) = false
    B-->>B: UNCHANGED, sin UPDATE
```

## Flujo en detalle

### Registro (`Timeline.record`)

`recordReceived` y `recordSent` (`Timeline.java:26-34`):

1. Rechazan la sincronización de transacción activa (`Timeline.java:27`, `:32`, con el privado
   `requireNoOpenTransaction`, `Timeline.java:58-61`). Esa comprobación va primero: un comando nulo dentro de una
   transacción da `IllegalStateException`, no `NullPointerException`.
2. Leen el reloj una vez y arman el `Message` con `Message.received` o `Message.sent` (`Timeline.java:28`, `:33`).
   Las fábricas rechazan el comando nulo (`Message.java:72`, `:79`); el constructor privado valida `externalId` con
   `Assert.hasText` y el resto con `Objects.requireNonNull` (`Message.java:58-68`); `sent` rechaza antes el remitente
   `CUSTOMER` (`Message.java:80-82`).

`record` (`Timeline.java:63-76`):

1. Busca el id por cuenta + `externalId` **sin transacción** (`Timeline.java:64-68`): el JPQL
   (`MessageRepository.java:13-15`) pasa por `@TenantId`, así que solo ve mensajes de la organización activa. Si
   encuentra, devuelve `RecordedMessage(id, true)` sin abrir transacción de escritura y sin WARN: es el camino de cada
   reenvío del proveedor.
2. Si no encuentra, hace `saveAndFlush` dentro del `TransactionTemplate` (`Timeline.java:69-70`). Hibernate asigna
   `organization_id` y el `flush` hace que una violación salga ahí mismo como `DataIntegrityViolationException`.
3. Si el `INSERT` choca, la transacción ya se deshizo entera y relee con la misma consulta sin transacción
   (`Timeline.java:71-75`). Con READ COMMITTED ve el mensaje que confirmó la otra transacción y devuelve
   `RecordedMessage(id del ganador, true)`. Si no hay nada, la violación no era una carrera (FK de persona,
   oportunidad o cuenta de canal, sin organización) y relanza la excepción original.

### Estado (`Timeline.applyStatus`)

`applyStatus` (`Timeline.java:36-45`), en una transacción:

1. Valida la cuenta y el `externalId` con `requireProviderKey` (`Timeline.java:38`, `:53-56`), la misma validación
   que `isRecorded` (`Timeline.java:49`), y el estado con `Objects.requireNonNull` (`Timeline.java:39`).
2. Trae el mensaje con `lockByChannelAccountIdAndExternalId` con `@Lock(PESSIMISTIC_WRITE)`
   (`MessageRepository.java:17-20`), que en PostgreSQL Hibernate traduce a `FOR NO KEY UPDATE` (es lo que espera
   `MessagingFixture.anotherSessionIsWaitingToLockTheMessage`, `MessagingFixture.java:121-123`). `@TenantId` agrega el
   filtro por organización.
3. Si no está, `UNKNOWN_MESSAGE`. Si está, `advanceTo` decide (`Message.java:87-94`) y devuelve `ADVANCED` o
   `UNCHANGED` (`Timeline.java:40-44`). El cambio lo guarda el dirty checking al confirmar; no hay `UPDATE` masivo.

## Concurrencia

- **Duplicados**: el `UNIQUE (organization_id, channel_account_id, external_id)` garantiza una sola fila. La búsqueda
  previa (y `isRecorded`) evita trabajo en el caso común, pero no es autoritativa: dos registros en paralelo pueden no
  encontrar nada y llegar los dos al `INSERT`. El segundo espera al índice, falla con 23505 cuando el primero confirma y
  relee fuera de transacción (diagrama de arriba). Si el primero se deshace, el segundo inserta el suyo.
- **Estados**: el lock `FOR NO KEY UPDATE` serializa los reportes de un mismo mensaje. El que espera obtiene la fila ya
  confirmada (en READ COMMITTED, `SELECT ... FOR NO KEY UPDATE` devuelve la última versión confirmada de la fila
  bloqueada) y `advanceTo` decide sobre ella, así que el resultado no depende del orden de llegada. Si cada reporte va
  en su propia transacción, cada `applyStatus` bloquea una sola fila y no hay deadlocks entre reportes. Como
  `applyStatus` se une a la transacción del llamador, varios reportes aplicados en una misma transacción acumulan locks
  hasta el commit, y dos de esas transacciones que tomen mensajes en distinto orden pueden trabarse (deadlock, que
  PostgreSQL resuelve abortando una).
- **Registro frente a oportunidades**: el `INSERT` en `messages` toma `FOR KEY SHARE` sobre la fila de la oportunidad,
  la de la persona y, desde V7, la de la cuenta de canal (chequeo de las FK). Es compatible con el `FOR NO KEY UPDATE`
  de `recordActivity` y del job de cierre (`docs/oportunidades.md`, «La oportunidad vigente»): registrar un mensaje no
  espera a esos locks ni ellos al registro.
- **Estado frente a registro**: un reporte que llega antes de que el mensaje esté registrado devuelve
  `UNKNOWN_MESSAGE` y se pierde (ver «Riesgos»).

**WARN de Hibernate.** En la carrera de duplicados, Hibernate deja un WARN (`org.hibernate.orm.jdbc.error`) con el
SQLState 23505 y el nombre de la restricción. Con `logServerErrorDetail: false` (P-031) no lleva los valores en
conflicto; el test afirma que la salida nombra `messages_organization_id_channel_account_id_external_id_key` y no
contiene el texto del mensaje. Un duplicado que encuentra la búsqueda previa no deja WARN.

Descartadas (P-041, P-042): `INSERT … ON CONFLICT` (SQL nativo, se salta `@TenantId`); `UPDATE` nativo condicional
para el estado; `@Version`; tabla de estados pendientes.

## Esquema

`backend/src/main/resources/db/migration/V6__create_messages.sql`:

```sql
ALTER TABLE opportunities
    ADD CONSTRAINT opportunities_id_person_id_organization_id_key UNIQUE (id, person_id, organization_id);

CREATE TABLE messages (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations (id),
    person_id UUID NOT NULL,
    opportunity_id UUID NOT NULL,
    channel_account_id UUID NOT NULL,
    channel TEXT NOT NULL,
    sender_type TEXT NOT NULL,
    external_id TEXT NOT NULL,
    text TEXT NOT NULL,
    status TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT messages_person_id_organization_id_fkey
        FOREIGN KEY (person_id, organization_id) REFERENCES people (id, organization_id),
    CONSTRAINT messages_opportunity_id_person_id_organization_id_fkey
        FOREIGN KEY (opportunity_id, person_id, organization_id)
        REFERENCES opportunities (id, person_id, organization_id),
    CONSTRAINT messages_organization_id_channel_account_id_external_id_key
        UNIQUE (organization_id, channel_account_id, external_id)
);

CREATE INDEX messages_person_id_occurred_at_recorded_at_id_idx
    ON messages (person_id, occurred_at DESC, recorded_at DESC, id DESC);
CREATE INDEX messages_person_id_channel_account_id_occurred_at_customer_idx
    ON messages (person_id, channel_account_id, occurred_at DESC) WHERE sender_type = 'CUSTOMER';
CREATE INDEX messages_opportunity_id_idx ON messages (opportunity_id);
```

Restricciones:

- `opportunities_id_person_id_organization_id_key` (`V6__create_messages.sql:1-2`) es redundante con la clave primaria
  de `opportunities`, pero hace falta como destino de la FK de tres columnas. Es un cambio sobre una tabla de otro
  módulo, como el `UNIQUE` que V4 agregó a `operators`.
- `messages_opportunity_id_person_id_organization_id_fkey` (`V6__create_messages.sql:19-21`) garantiza que la
  oportunidad sea **de la misma persona** y de la misma organización que el mensaje, aunque alguien inserte por SQL.
  No controla que esté abierta.
- `messages_person_id_organization_id_fkey` (`V6__create_messages.sql:17-18`) es defensa en profundidad: la FK de tres
  columnas ya implica que la persona es de la organización (la oportunidad lo garantiza con su propia FK a `people`),
  pero esta no depende de eso.
- `messages_organization_id_fkey` (implícita, `V6__create_messages.sql:6`) es la que rechaza registrar sin
  organización (centinela).
- `channel_account_id` no tiene FK en V6 (P-039). La agrega V7 (#42, P-045):
  `messages_channel_account_id_organization_id_channel_fkey`, de tres columnas `(channel_account_id, organization_id,
  channel) → channel_accounts (id, organization_id, channel_type)` (`V7__create_channel_accounts.sql:14-17`). Garantiza
  que la cuenta exista, sea de la misma organización y tenga el tipo de `channel`. Es `NO ACTION`: una cuenta con
  mensajes no se puede borrar. Detalle en `docs/canales.md`, «Esquema».
- `channel`, `sender_type` y `status` son `TEXT` sin `CHECK` de valores, como `operators.role`.

Índices y las consultas que sirven (Hibernate agrega `organization_id = ?` por `@TenantId` a toda consulta de la
entidad):

| Índice | Consulta |
|---|---|
| `messages_organization_id_channel_account_id_external_id_key` (el `UNIQUE`, `V6__create_messages.sql:22-23`) | La idempotencia, y las dos búsquedas por cuenta + `externalId` de `MessageRepository` (`WHERE organization_id = ? AND channel_account_id = ? AND external_id = ?`): la del duplicado y la de `applyStatus`. También sirve al lado de `messages` de la FK a `channel_accounts` de V7 (los chequeos al borrar una cuenta o cambiarle la clave buscan por `organization_id` y `channel_account_id`, el prefijo del índice). Como empieza por `organization_id`, no hace falta un índice aparte por organización (`.claude/rules/flyway.md`). |
| `messages_person_id_occurred_at_recorded_at_id_idx` (`V6__create_messages.sql:26-27`) | La línea de tiempo de una persona (#15): `WHERE person_id = ? ORDER BY occurred_at DESC, recorded_at DESC, id DESC`, con paginación. Por empezar en `person_id`, también sirve al chequeo de la FK a `people`. |
| `messages_person_id_channel_account_id_occurred_at_customer_idx` (parcial, `V6__create_messages.sql:28-29`) | La ventana de conversación (#12): el último mensaje del cliente en una cuenta, `WHERE person_id = ? AND channel_account_id = ? AND sender_type = 'CUSTOMER' ORDER BY occurred_at DESC LIMIT 1`. El planner usa un índice parcial solo si puede probar el predicado, así que `sender_type = 'CUSTOMER'` va como literal, no como parámetro. |
| `messages_opportunity_id_idx` (`V6__create_messages.sql:30`) | El lado de `messages` de la FK `messages_opportunity_id_person_id_organization_id_fkey` (los chequeos al borrar una oportunidad o cambiar su clave, por ejemplo en una fusión de personas, buscan `WHERE opportunity_id = ? AND person_id = ? AND organization_id = ?`) y las consultas por oportunidad (métricas, #15). Alcanza con `opportunity_id`: es la clave primaria de `opportunities`, así que ya identifica una sola oportunidad, y `person_id` y `organization_id` no achican el resultado; sumarlos solo agranda el índice. |

Los índices van con `CREATE INDEX` común, no `CONCURRENTLY`: `messages` es nueva y `opportunities` todavía no tiene
datos productivos (ver «Riesgos»).

Fila de ejemplo después de `recordReceived` con ámbito Acme, con el reloj en `MutableClock.INITIAL_INSTANT`
(`TimelineTest.receivedMessageIsRecordedInThePersonTimeline`):

```
messages: id = <id>, organization_id = <Acme>, person_id = <persona>, opportunity_id = <oportunidad>,
          channel_account_id = <cuenta>, channel = WHATSAPP, sender_type = CUSTOMER, external_id = wamid.<uuid>,
          text = Hola, ¿tienen financiación?, status = DELIVERED,
          occurred_at = 2026-03-02 08:59:20+00, recorded_at = 2026-03-02 09:00:00+00
```

## Tests

`backend/src/test/java/com/communicatorone/messaging/MessageTest.java` (sin Spring; reloj en
`MutableClock.INITIAL_INSTANT`; los comandos los arma una `MessagingFixture.Conversation` con ids al azar). Prueba la
regla de estados y las fábricas de `Message`. 10 métodos, 17 ejecuciones con los parametrizados.

| Test | Qué cubre |
|---|---|
| `deliveryAndReadReportsAdvanceASentMessage` | Un saliente `SENT` pasa a `DELIVERED` y después a `READ`; las dos llamadas devuelven `true`. |
| `deliveryReportArrivingAfterTheReadReportDoesNotMoveTheStatusBack` | Con el mensaje en `READ`, un `DELIVERED` tardío devuelve `false` y deja `READ`. |
| `repeatedStatusReportChangesNothing` | Un `DELIVERED` repetido devuelve `false`. |
| `failureReportedAfterDeliveryIsIgnored` | Un `FAILED` después de `DELIVERED` devuelve `false` y deja `DELIVERED`. |
| `deliveryReportedAfterAFailureWins` | Un `DELIVERED` después de `FAILED` devuelve `true`. |
| `deletedMessageStaysDeletedWhateverIsReportedLater` | Parametrizado con los cuatro estados distintos de `DELETED`: ninguno mueve un mensaje borrado. |
| `receivedMessageArrivesDeliveredAndOnlyMovesForwardFromThere` | Un entrante nace `DELIVERED`; `FAILED` se ignora y `READ` se aplica: el rango vale igual que para los salientes. |
| `messageCannotBeSentAsTheCustomer` | `Message.sent` con `CUSTOMER` lanza `IllegalArgumentException` con su mensaje. |
| `receivedMessageWithoutExternalIdOrTextIsRejected` | Parametrizado: `externalId` nulo, vacío o en blanco, y texto nulo o vacío, dan `IllegalArgumentException` (el texto lo rechaza `MessageContent` al armar el comando; el `externalId`, el constructor de `Message`). |
| `messageTextIsNotRevealedWhenItsContentIsLogged` | `String.valueOf(new MessageContent("Mi DNI es 30111222"))` no contiene el número. |

`backend/src/test/java/com/communicatorone/messaging/TimelineTest.java` (`@SpringBootTest` con `RANDOM_PORT` +
Testcontainers, sin `@Transactional`; reloj fijo en `MutableClock.INITIAL_INSTANT`, el entrante fechado 40 s antes).
Cada test crea sus organizaciones y llama con `callAs` (`receiveIn`, `sendIn`, `applyStatusIn`, `isRecordedIn`). Las
filas se leen con `MessagingFixture` (`JdbcTemplate`), que compara la fila entera (`StoredMessage`), filtrando por
`organization_id` salvo `messagesInAnyOrganizationWithExternalId`, que cuenta en todas. La persona sale de
`PersonDirectory`, la oportunidad de `OpportunityTracker.recordActivity` y la cuenta de canal es una fila real de
`channel_accounts` que `MessagingFixture.channelAccount` inserta por SQL, de tipo `WHATSAPP` salvo que se le pase otro
(`MessagingFixture.java:61-73`; desde #42, sin depender de clases de `channels`). 24 métodos, 27 ejecuciones con los
parametrizados (antes de #42 eran 21 y 24).

| Test | Qué cubre |
|---|---|
| `receivedMessageIsRecordedInThePersonTimeline` | Fila entera: `CUSTOMER`, `DELIVERED`, `occurred_at` del proveedor, `recorded_at` del reloj; `duplicate = false`. |
| `duplicatedWebhookDoesNotDuplicateMessage` | El mismo entrante dos minutos después devuelve `(id del primero, true)` y la fila sigue con el `recorded_at` original. |
| `sameExternalIdOnAnotherChannelAccountIsADifferentMessage` | El mismo `externalId` en otra cuenta crea otro mensaje. |
| `concurrentDuplicatedWebhooksRecordASingleMessage` | La carrera del diagrama, determinista: un hilo rival inserta sin confirmar (`MessagingFixture.rivalStartsRecordingMessage`), el registro corre en otro hilo, Awaitility espera en `pg_stat_activity` una sesión bloqueada en `insert into messages` (`anotherSessionIsWaitingToWriteMessages`) y recién ahí confirma el rival. Afirma `(id del rival, true)`, una sola fila, y que la salida nombra la restricción sin el texto. |
| `sentMessageIsRecordedAsSent` | Parametrizado con `OPERATOR`, `BOT` y `SYSTEM`: fila `SENT` con `occurred_at` = `recorded_at` = reloj. |
| `sentMessageAcknowledgedTwiceIsRecordedOnce` | El mismo saliente registrado dos veces da `(id del primero, true)` y una fila. |
| `deliveryReportArrivingAfterTheReadReportDoesNotMoveTheStatusBack` | `READ` da `ADVANCED`, el `DELIVERED` tardío da `UNCHANGED`, y la fila queda `READ`. |
| `statusReportForAnUnknownMessageIsIgnored` | Otro `externalId` da `UNKNOWN_MESSAGE` y el mensaje existente no cambia. |
| `statusReportForTheSameExternalIdOnAnotherChannelAccountIsIgnored` | El mismo `externalId` en otra cuenta da `UNKNOWN_MESSAGE`. |
| `statusReportCannotChangeAMessageOfAnotherOrganization` | Globex aplicando `READ` con la cuenta y el `externalId` de Acme da `UNKNOWN_MESSAGE`; el de Acme no cambia y Globex no tiene filas. |
| `deliveryReportArrivingWhileAReadReportIsBeingAppliedDoesNotMoveTheStatusBack` | La carrera de estados: un rival hace `UPDATE` a `READ` sin confirmar (`rivalStartsChangingStatus`), `applyStatus(DELIVERED)` corre en otro hilo, Awaitility espera una sesión bloqueada en `from messages … for no key update` (`anotherSessionIsWaitingToLockTheMessage`) y confirma el rival. Afirma `UNCHANGED` y la fila en `READ`. |
| `alreadyRecordedMessageIsRecognized` | `isRecorded` da `true` para un mensaje registrado. |
| `messageNotYetRecordedIsNotRecognized` | `false` para otro `externalId`. |
| `messageRecordedOnAnotherChannelAccountIsNotRecognized` | `false` para el mismo `externalId` en otra cuenta. |
| `messageRecordedInAnotherOrganizationIsNotRecognized` | `false` desde Globex para un mensaje de Acme. |
| `messageOfAPersonOfAnotherOrganizationIsRejected` | Persona de Globex en un mensaje de Acme: `DataIntegrityViolationException` y ninguna fila con ese `externalId` en ninguna organización. |
| `messageCannotPointToAnOpportunityOfAnotherOrganization` | Oportunidad de Globex en un mensaje de Acme: `DataIntegrityViolationException` (FK de tres columnas) y ninguna fila. |
| `messageCannotPointToAnOpportunityOfAnotherPerson` | Oportunidad de otra persona de Acme: `DataIntegrityViolationException`, ninguna fila, y la salida nombra `messages_opportunity_id_person_id_organization_id_fkey`. |
| `messageCannotPointToAChannelAccountOfAnotherOrganization` | (#42) Cuenta de canal de Globex en un mensaje de Acme: `DataIntegrityViolationException`, ninguna fila en Acme ni en Globex ni con ese `externalId`, y la salida nombra `messages_channel_account_id_organization_id_channel_fkey`. |
| `messageCannotPointToAnUnknownChannelAccount` | (#42) Cuenta de canal inexistente (`UUID` al azar): `DataIntegrityViolationException`, ninguna fila, y la salida nombra la misma FK. |
| `messageOnAChannelOtherThanItsAccountTypeIsRejected` | (#42) Mensaje `WHATSAPP` en una cuenta `TELEGRAM` de Acme: `DataIntegrityViolationException`, ninguna fila, y la salida nombra la misma FK. |
| `messageCannotBeRecordedWithoutOrganization` | Sin ámbito ni usuario: `DataIntegrityViolationException` y ninguna fila en ninguna organización. |
| `messageCannotBeRecordedInsideAnOpenTransactionalScope` | Parametrizado con `Propagation.REQUIRED` (transacción real) y `Propagation.SUPPORTS` (ámbito sin transacción): `IllegalStateException` y nada guardado. |
| `receivedMessageWithoutExternalIdLeavesNothingInTheTimeline` | `recordReceived` con `externalId` en blanco da `IllegalArgumentException` y no deja filas en la organización: la validación corre antes de tocar la base. Las demás combinaciones las cubre `MessageTest.receivedMessageWithoutExternalIdOrTextIsRejected`. |

`TenantIsolationTest.everyBusinessEntityBelongsToAnOrganization` cubre que `Message` lleve `@TenantId`, y
`ModularStructureTest` que `messaging` no dependa de internos de otros módulos.

Comando: `./mvnw test -Dtest='MessageTest,TimelineTest'` desde `backend/` (skill `run-tests`).

## Riesgos y pendientes

- **Estados que llegan antes que el saliente se pierden.** El saliente se registra después de que el proveedor devolvió
  su id; si el webhook de entregado o leído llega antes que esa escritura, `applyStatus` devuelve `UNKNOWN_MESSAGE` y
  el reporte se descarta. Mitigación probable en #12: registrar el saliente **antes** de enviar, con `external_id`
  nulo, y completarlo con la respuesta. `ALTER COLUMN external_id DROP NOT NULL` es seguro: el `UNIQUE` trata los
  `NULL` como distintos.
- **Messenger e Instagram informan la lectura por watermark** (todo lo anterior a una hora está leído), no por id de
  mensaje: hace falta una operación aparte, que `applyStatus` no cubre.
- **Saliente proactivo sin oportunidad abierta**: `opportunity_id` es `NOT NULL` (D-001). #12 tiene que resolver qué
  oportunidad usa un mensaje que la organización inicia (por ejemplo, abrir una o usar la última).
- **`channel` duplica el tipo de la `ChannelAccount`**. Cerrado por P-045 (#42): la FK de tres columnas de V7 rechaza
  un mensaje cuyo `channel` no coincide con el tipo de su cuenta.
- **La FK no controla que la oportunidad esté abierta**: garantiza que sea de la misma persona y organización, pero el
  llamador tiene que pasar la que devolvió `recordActivity`.
- **Gana la primera escritura**: un duplicado con contenido distinto (un proveedor que reenvía editado) conserva el
  original. Tampoco se distingue la dirección (un saliente con el `externalId` de un entrante de la misma cuenta es su
  duplicado).
- **`occurred_at` del proveedor sin validar**: una hora futura o muy vieja desordena la línea de tiempo.
- **Entrante `DELIVERED` es interpretación propia**: ningún proveedor lo informa.
- **Solo texto**: adjuntos, plantillas y ubicación llegan con #11/#12, sin `jsonb` por ahora.
- **Índices antes que sus consultas**: el de la línea de tiempo y el de la ventana no los usa nadie hasta #15 y #12.
  #12 tiene que escribir la consulta de la ventana con `sender_type = 'CUSTOMER'` literal para que use el índice
  parcial.
- **V6 toca `opportunities`** (tabla del módulo `opportunities`) para el destino de la FK de tres columnas. El
  `ALTER TABLE opportunities ADD CONSTRAINT … UNIQUE` toma `ACCESS EXCLUSIVE` y construye el índice con ese lock, sin
  `lock_timeout`: si espera detrás de una transacción abierta sobre `opportunities`, encola detrás de él también las
  lecturas de la tabla. Riesgo bajo mientras no haya producción; mitigación futura: `SET lock_timeout` al inicio de la
  migración o `spring.flyway.init-sqls`. Con datos, el patrón es `CREATE UNIQUE INDEX CONCURRENTLY` en una migración
  propia y después `ADD CONSTRAINT … UNIQUE USING INDEX`; esa corrida necesita
  `spring.flyway.postgresql.transactional-lock=false`, porque con el lock transaccional de Flyway el `CONCURRENTLY`
  puede quedar colgado (`.claude/rules/flyway.md`).
- **El `CREATE TABLE messages` también espera locks**: al crear sus FK toma `SHARE ROW EXCLUSIVE` sobre `people`,
  `organizations` y `opportunities`, sin `lock_timeout`. Ese lock bloquea las escrituras en esas tablas (no las
  lecturas) mientras dure la migración, y la migración espera detrás de cualquier transacción que esté escribiendo en
  ellas. Riesgo bajo, como el del `ALTER`, y con la misma mitigación.
- **Una base local que corrió una V6 anterior** (de una versión previa de esta rama) falla la validación de Flyway por
  el checksum. Se recrea con `docker compose down -v && docker compose up -d`, no con `flyway repair` (que marcaría
  como aplicado un esquema distinto del de la V6 actual).
- **La fusión de personas (P-030)** cambia el `person_id` de oportunidades y mensajes. Con la FK de tres columnas, mover
  una oportunidad a otra persona rompe la FK de sus mensajes en el medio del `UPDATE`: hará falta declarar las FK
  `DEFERRABLE` o `ON UPDATE CASCADE`.
- **WARN de Hibernate en las carreras**: cada carrera de duplicados deja un WARN con el SQLState y el nombre de la
  restricción (sin valores, P-031). Ruido esperable en los logs.
- **Fixtures de test duplicados**: `MessagingFixture` es la tercera copia de `organization(name)` y del patrón «rival
  que escribe sin confirmar» (`PeopleFixture`, `OpportunitiesFixture`). A futuro, un helper de test compartido.
- Los tests de las carreras dependen de `pg_stat_activity` (PostgreSQL).
- **Dos `SELECT` iguales para un mensaje nuevo en el flujo de #10**: el flujo previsto («API y contrato») llama a
  `isRecorded` y después a `recordReceived`, que vuelve a buscar por cuenta + `externalId` antes de insertar
  (`Timeline.java:50`, `:64-65`). Para un mensaje nuevo, el caso común, son dos consultas idénticas por el `UNIQUE`.
  #10 puede decidir por `RecordedMessage.duplicate` (registrar primero y contar la actividad solo si es `false`, lo que
  obliga a resolver la oportunidad de otra forma, porque `ReceivedMessage` la necesita) o aceptar la consulta extra,
  que es barata (búsqueda por índice único).

## Pendiente: #10, #12, #15

- **#9 (canales y cuentas)**: hecho en #42 (`docs/canales.md`), con la rama apilada sobre la de #8: V7 crea
  `channel_accounts` y la FK de tres columnas `(channel_account_id, organization_id, channel)` de `messages`, que
  además controla que `channel` coincida con el tipo de la cuenta (P-045, en lugar de la FK de dos columnas de P-039);
  `MessagingFixture.channelAccount` crea una cuenta real.
- **#43 (puerto y sandbox)**: hecho (`docs/canales.md`). `SANDBOX` está en `ChannelType`; el puerto
  `ChannelAdapter` exige un `externalId` único por cuenta y estable entre reenvíos (P-042), y el sandbox lo toma del
  `id` de cada mensaje del payload. Para Telegram, componerlo (`chatId:messageId`) queda para su adaptador.
- **#10**: resolver la cuenta con `ChannelAccountDirectory.resolve` y ligar su organización con
  `currentOrganization.runAs(account.organizationId(), ...)` **antes** de llamar a `Timeline`, pasando
  `account.channelType()` como canal. Sin organización, registrar falla por `messages_organization_id_fkey` y
  `applyStatus` devuelve `UNKNOWN_MESSAGE` en silencio.
- **#10 (flujo entrante)**: vive en `channels` (o en un módulo por encima), no en `messaging`. Descarta el reenvío con
  `isRecorded` antes de `recordActivity`, registra con `recordReceived` y publica «mensaje recibido» solo si
  `RecordedMessage.duplicate` es `false`. Decide si acepta el `SELECT` repetido de un mensaje nuevo o se apoya solo en
  `RecordedMessage.duplicate` (ver «Riesgos»).
- **#12 (envío y ventana)**: vive fuera de `messaging`. Decide el registro antes del envío (riesgo de estados que llegan
  antes), la oportunidad del saliente proactivo, y escribe la consulta de la ventana sobre el índice parcial.
- **#15 (vista unificada)**: la consulta de la línea de tiempo, REST, WebSocket y los no leídos del operador (otro
  concepto que `status`). Amplía el retorno de `applyStatus` a un record con el resultado y el id del mensaje, para
  notificar el cambio.
