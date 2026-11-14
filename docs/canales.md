# Canales y cuentas de canal

Issues #42 y #43 (partes de #9). Decisiones en `docs/decisiones.md`: P-043 (entidad y alcance), P-044 (unicidad
global y resolución entre organizaciones), P-045 (FK de tres columnas desde `messages`, que reemplaza la de dos columnas
que anunciaba P-039), P-046 (forma del puerto `ChannelAdapter`), P-047 (registro de adaptadores) y P-048 (adaptador
`SANDBOX`). Se apoya en `docs/multi-tenant.md`, en los mensajes de `docs/mensajes.md` y en las identidades de
`docs/personas.md`.

## Resumen

Una `ChannelAccount` es **nuestra** identidad en un canal: el número de WhatsApp, la página de Facebook, la cuenta de
Instagram o el bot de Telegram por el que una organización recibe y manda mensajes. Es de una sola organización y la
identifica el par **tipo de canal + id que le dio el proveedor** (`externalAccountId`): el `phone_number_id` de
WhatsApp, el id de la página, el id del bot.

Cuando llega un webhook, el proveedor dice a qué cuenta suya iba dirigido, pero no a qué organización. Por eso el
módulo `channels` resuelve la cuenta **sin organización activa**: `ChannelAccountDirectory.resolve(channelType,
externalAccountId)` busca la cuenta en todas las organizaciones y la devuelve con su `organizationId`, para que el
pipeline entrante (#10) abra el ámbito de esa organización antes de resolver la persona, la oportunidad y el mensaje.

Se va a activar con cada webhook que procese #10. Hoy nadie llama a `resolve` fuera de los tests.

La base también controla, desde #42, que todo mensaje apunte a una cuenta que existe, que es de su misma organización
y cuyo tipo coincide con el `channel` del mensaje (FK de tres columnas, P-045).

Desde #43, cada canal se habla a través de un **puerto** común, `ChannelAdapter` (`docs/vision.md` §5): convierte el
cuerpo de un webhook en mensajes entrantes (`InboundMessage`) y en reportes de estado (`StatusUpdate`), y manda un
texto a una identidad de contacto desde una cuenta nuestra. `ChannelAdapterRegistry` da el adaptador de cada
`ChannelType`. El primer adaptador es el del canal de prueba `SANDBOX` (`SandboxChannelAdapter`): entiende un JSON
propio (ver «Formato del payload del sandbox») y guarda en memoria lo que «envía», para que los tests afirmen con
`hasSentTextTo(identidad, texto)` sin un proveedor real.

El puerto y el registro se van a activar con cada webhook que procese #10 y con cada envío de #12. Hoy nadie los usa
fuera de sus tests unitarios.

Fuera de #42 y #43: el pipeline entrante y el endpoint de webhooks (#10); credenciales, URL o secreto del webhook,
«tiene bot», alta, baja y listado de cuentas, y el adaptador de WhatsApp con la firma de Meta (#11); el envío, que va a
leer `allowsOutbound`, las fallas de envío, `windowState` y `startConversation` (#12).

## Piezas

Módulo `channels` (`backend/src/main/java/com/communicatorone/channels/`). Depende solo de tipos públicos de la raíz
de otros módulos: de `messaging`, `ChannelType` (P-038) y, desde #43, `MessageContent` y `MessageStatus`; de
`organizations`, `CurrentOrganization`; y, desde #43, de `people`, `ContactIdentity`, `IdentityKind` e
`InvalidContactIdentityException`. No hay ciclo: ni `messaging` ni `people` conocen a `channels`. Todo es
package-private y vive en el paquete raíz del módulo, sin subpaquetes: el único consumidor previsto es #10, que vive
en el mismo módulo (P-043), y los adaptadores van planos en la raíz con el canal como prefijo (`SandboxChannelAdapter`;
después `WhatsAppChannelAdapter`, P-046).

Cuentas de canal (#42):

| Clase | Visibilidad | Qué hace |
|---|---|---|
| `ChannelAccount` | package-private | Entidad de la tabla `channel_accounts` (`ChannelAccount.java:16-18`): `id`, `@TenantId organizationId` (`ChannelAccount.java:24-26`), `channelType` guardado por nombre, `externalAccountId`, `displayName` y `allowsOutbound`. Fábrica `register(channelType, externalAccountId, displayName, allowsOutbound)` (`ChannelAccount.java:48-54`), que exige tipo y nombre visible y normaliza el id con `normalizedExternalAccountId` (`strip`, `ChannelAccount.java:56-59`). Accesores `id()`, `organizationId()`, `channelType()` y `allowsOutbound()` (`ChannelAccount.java:61-75`). Sin setters, sin accesor de `displayName` (nadie lo lee todavía) y sin `toString` propio. |
| `ChannelAccountRepository` | package-private | `JpaRepository<ChannelAccount, UUID>` con la consulta derivada `findByChannelTypeAndExternalAccountId` (`ChannelAccountRepository.java:8-10`), filtrada por `@TenantId` como toda consulta derivada. |
| `ChannelAccountDirectory` | package-private | `@Component` con `resolve(ChannelType, String): Optional<ChannelAccount>` (`ChannelAccountDirectory.java:26-34`). Al entrar rechaza la sincronización de transacción activa (`ChannelAccountDirectory.java:27-28`, como `PersonDirectory.resolveOrCreate`, P-029); después busca la organización con `JdbcTemplate` (el privado `organizationOf`, `ChannelAccountDirectory.java:36-42`) y carga la cuenta con `callAs` y la consulta filtrada. Recibe `JdbcTemplate`, `CurrentOrganization` y el repositorio en el constructor package-private (`ChannelAccountDirectory.java:19-24`). Sin `@Transactional`. |

Puerto, registro y adaptador de prueba (#43):

| Clase | Visibilidad | Qué hace |
|---|---|---|
| `ChannelAdapter` | package-private | Interfaz del puerto (`ChannelAdapter.java:8-17`): `type()`, `parseInbound(WebhookPayload)`, `parseStatusUpdates(WebhookPayload)` y `send(ContactIdentity to, ChannelAccount from, MessageContent content)`. Contrato en «Puerto de canal». |
| `WebhookPayload` | package-private | `record (String body)`: el cuerpo del webhook tal como llegó. Exige `body` no nulo (`WebhookPayload.java:7-9`); el cuerpo vacío lo rechaza el adaptador. `toString` devuelve `WebhookPayload[body]`, sin el cuerpo, que trae datos personales (`WebhookPayload.java:11-14`). Sin headers ni bytes crudos (P-046). |
| `InboundMessage` | package-private | `record (externalAccountId, ContactIdentity sender, externalId, MessageContent content, Instant occurredAt)` (`InboundMessage.java:8-9`). El constructor compacto exige los cinco con `Assert` (`InboundMessage.java:11-17`); no recorta (el id de cuenta lo recorta `resolve`). No lleva el tipo de canal: lo da el adaptador o la cuenta resuelta (P-045). |
| `StatusUpdate` | package-private | `record (externalAccountId, externalId, MessageStatus status)` (`StatusUpdate.java:6`), con los tres obligatorios (`StatusUpdate.java:8-12`). |
| `SendResult` | package-private | `record (String externalId)`: el id que el proveedor le dio al mensaje enviado, obligatorio (`SendResult.java:5-9`). Las fallas de envío las modela #12. |
| `InvalidWebhookPayloadException` | package-private | Unchecked. Mensaje `Invalid <tipo> webhook payload`, sin causa encadenada ni detalle (`InvalidWebhookPayloadException.java:5-10`): el error del parser puede citar fragmentos del payload, como el valor de un teléfono (mismo criterio que `InvalidContactIdentityException`). |
| `ChannelAdapterRegistry` | package-private | `@Component` que recibe todos los beans `ChannelAdapter` (`ChannelAdapterRegistry.java:15-19`) y los indexa por `type()`. `adapterFor(ChannelType)` (`ChannelAdapterRegistry.java:21-27`). Contrato en «Registro de adaptadores». |
| `UnsupportedChannelException` | package-private | Unchecked. Mensaje `No channel adapter handles <tipo>` (`UnsupportedChannelException.java:5-10`). |
| `SandboxChannelAdapter` | package-private | `@Component` del canal `SANDBOX` (`SandboxChannelAdapter.java:19-133`), siempre activo. Parsea el JSON propio del sandbox con records anidados package-private y guarda los envíos en memoria; `hasSentTextTo` es la consulta para los tests. Detalle en «Adaptador `SANDBOX`». |

El `package-info.java` con `@ApplicationModule` ya existía desde el scaffold y `ModularStructureTest` ya declaraba
`channels`. #42 suma la migración V7 (ver «Esquema»), que además agrega una restricción a `messages`. #43 no tiene
migración: `channel_accounts.channel_type` y `messages.channel` son `TEXT` sin `CHECK`, así que `SANDBOX`
(`ChannelType.java:11`) entra sin tocar el esquema, y no aparece en `backend/openapi.json` (ningún endpoint lo
expone).

## Contrato de `resolve` (para #10)

```java
Optional<ChannelAccount> account = directory.resolve(ChannelType.WHATSAPP, "106540352242922");
```

| Entrada | Salida |
|---|---|
| Tipo + id de una cuenta registrada en cualquier organización, sin organización activa | `Optional` con la cuenta (desprendida): su `id`, su `organizationId`, su `channelType` y su `allowsOutbound` |
| Lo mismo, dentro de `callAs` de **otra** organización | La cuenta de su propia organización: la organización activa no influye |
| El id con espacios alrededor (`" 106540352242922  "`) | La misma cuenta: se recorta con la misma función que al registrar |
| Un id que no está registrado para ese tipo (aunque exista en otro tipo) | `Optional.empty()` |
| El mismo id con otro tipo de canal | La cuenta de ese tipo, si existe: `WHATSAPP "777"` y `TELEGRAM "777"` son dos cuentas |
| Tipo nulo, fuera de transacción | `IllegalArgumentException` («A channel account is resolved by its channel type») |
| Id nulo, vacío o en blanco, fuera de transacción | `IllegalArgumentException` («A channel account needs the id its provider gave it») |
| Cualquier entrada (cuenta existente, id desconocido o argumentos inválidos) con sincronización de transacción activa: dentro de una transacción o de un ámbito `SUPPORTS` | `IllegalStateException` («A channel account is resolved outside of a transaction, not inside an open one»), antes de validar argumentos y sin consultar la base |
| Sin sincronización de transacción pero con una sesión de JPA ligada al hilo (OSIV, `SYNCHRONIZATION_NEVER`, `EntityManager` ligado a mano), y la cuenta existe | `IllegalStateException` de `callAs` («The organization cannot be switched inside a transaction or open session»). Sin test propio en `ChannelAccountDirectoryTest` |
| Lo mismo, con un id desconocido | `Optional.empty()`: el chequeo de entrada no mira la sesión y el `callAs` no llega a ejecutarse |

Quien llama a `resolve`:

1. **No tiene una transacción ni una sesión de JPA abierta.** `resolve` no es `@Transactional` porque el segundo paso
   es un `callAs`, que lanza `IllegalStateException` si hay sincronización de transacción activa o una sesión ligada
   al hilo (`CurrentOrganization.java:32-38`, `:47-50`). Es el mismo motivo por el que `OperatorAuthentication.signIn`
   no es transaccional (P-022). Para que el error no dependa de que la cuenta exista, `resolve` comprueba al entrar
   `TransactionSynchronizationManager.isSynchronizationActive()` con `Assert.state`
   (`ChannelAccountDirectory.java:27-28`), igual que `PersonDirectory.resolveOrCreate` (P-029): llamado dentro de una
   transacción o de un ámbito `SUPPORTS`, falla siempre, también con un id desconocido. Ese chequeo no mira la sesión
   ligada al hilo sin sincronización (OSIV, que la app tiene apagado): ese caso lo sigue frenando `callAs`, solo si la
   cuenta existe.
2. **Trata el `Optional.empty()` como «webhook para una cuenta que no conocemos»**: no hay organización a la que
   asignarlo. Qué responde al proveedor lo decide #10.
3. **Después abre el ámbito de la organización de la cuenta** y pasa el tipo de la cuenta como canal del mensaje:

```java
directory.resolve(channelType, payloadAccountId).ifPresent(account ->
        currentOrganization.runAs(account.organizationId(), () -> {
            var personId = personDirectory.resolveOrCreate(identity);
            // ... isRecorded, recordActivity ...
            timeline.recordReceived(new ReceivedMessage(personId, opportunityId, account.id(),
                    account.channelType(), externalId, content, occurredAt));
        }));
```

Pasar `account.channelType()` (y no un tipo que el pipeline arme por su cuenta) es lo que garantiza que el mensaje
cumpla la FK de tres columnas: si `channel` no coincide con el tipo de la cuenta, la base rechaza el `INSERT`
(`DataIntegrityViolationException`, ver «Esquema»).

`resolve` devuelve la entidad desprendida (la sesión del `callAs` ya se cerró), como
`OperatorAuthentication.operatorWithEmail`. Tiene solo columnas básicas, así que no hay carga perezosa que pueda fallar
después.

## Diagramas

Chequeo de entrada y resolución en dos pasos (`ChannelAccountDirectory.java:26-42`):

```mermaid
flowchart TD
    R["resolve(channelType, externalAccountId)"] --> T{sincronización de<br/>transacción activa?}
    T -->|sí| X0[IllegalStateException<br/>resolved outside of a transaction]
    T -->|no| V{tipo nulo, o id<br/>nulo o en blanco?}
    V -->|sí| X1[IllegalArgumentException]
    V -->|no| N["normalizedExternalAccountId<br/>(strip)"]
    N --> J["Paso 1, JdbcTemplate, sin organización:<br/>SELECT organization_id FROM channel_accounts<br/>WHERE channel_type = ? AND external_account_id = ?"]
    J -->|ninguna fila| E[Optional.empty]
    J -->|organization_id| S{sesión de JPA<br/>ligada al hilo?}
    S -->|sí| X2[IllegalStateException<br/>de callAs]
    S -->|no| C["Paso 2, callAs(organizationId):<br/>findByChannelTypeAndExternalAccountId<br/>filtrada por @TenantId"]
    C --> OK[Optional con la cuenta desprendida]
```

Dónde encaja en el flujo entrante de #10:

```mermaid
sequenceDiagram
    participant W as Webhook (#10)
    participant D as ChannelAccountDirectory
    participant DB as PostgreSQL
    participant CO as CurrentOrganization
    W->>D: resolve(WHATSAPP, "106540352242922")
    D->>DB: SELECT organization_id ... (sin filtro de organización)
    DB-->>D: <Acme>
    D->>CO: callAs(<Acme>, ...)
    CO->>DB: SELECT ... FROM channel_accounts WHERE ... AND organization_id = <Acme>
    DB-->>D: la cuenta
    D-->>W: Optional[cuenta de Acme]
    W->>CO: runAs(account.organizationId(), ...)
    Note over W,CO: persona, oportunidad y mensaje<br/>con channel = account.channelType()
```

## Flujo en detalle

### Registro (`ChannelAccount.register`)

1. Exige el tipo con `Assert.notNull` («A channel account needs its channel type») y el nombre visible con
   `Assert.hasText` («A channel account needs a display name», `ChannelAccount.java:50-51`). El nombre se guarda tal
   cual, como `Operator.name`.
2. Normaliza el id con `normalizedExternalAccountId` (`ChannelAccount.java:56-59`): `Assert.hasText` («A channel
   account needs the id its provider gave it») y `strip`. No cambia mayúsculas: el id es opaco y se compara exacto.
   Sigue el patrón de `Operator.register` / `Operator.normalizedEmail`.
3. La organización la asigna Hibernate al guardar, con la organización activa (`@TenantId`, P-018). Hoy solo guarda
   `ChannelsFixture.account` en los tests; en desarrollo, las cuentas se cargan por SQL (ver «Alta de una cuenta en
   desarrollo»).

Ejemplo: `register(WHATSAPP, "  106540352242922 ", "Ventas", true)` guarda `external_account_id = 106540352242922`.

### Resolución (`ChannelAccountDirectory.resolve`)

1. Rechaza la sincronización de transacción activa con `Assert.state` («A channel account is resolved outside of a
   transaction, not inside an open one», `ChannelAccountDirectory.java:27-28`). Va primero: dentro de una transacción
   hasta una llamada con argumentos inválidos da `IllegalStateException`.
2. Valida el tipo (`ChannelAccountDirectory.java:29`) y normaliza el id con la misma función que el registro
   (`ChannelAccountDirectory.java:30`), así que un payload con espacios encuentra la cuenta.
3. **Paso 1** (`ChannelAccountDirectory.java:36-42`): `JdbcTemplate` con `SELECT organization_id FROM channel_accounts
   WHERE channel_type = ? AND external_account_id = ?`. Es la tercera consulta del sistema que cruza organizaciones a
   propósito, después de la del login (P-022) y la del job de cierre (P-037), y como ellas devuelve solo el id de la
   organización (`docs/multi-tenant.md`, `.claude/rules/java.md`). Por el `UNIQUE` global devuelve como mucho una fila.
4. **Paso 2** (`ChannelAccountDirectory.java:31-33`): con esa organización abre `currentOrganization.callAs` y carga
   la cuenta con la consulta derivada, que Hibernate filtra por `@TenantId` como cualquier otra lectura.

Son dos consultas por webhook, las dos sobre el índice único. Se descartó una sola consulta nativa que devuelva la
fila entera (se salta `@TenantId`; la regla es que las consultas que cruzan organizaciones devuelven solo ids de
organización).

## Puerto de canal (`ChannelAdapter`)

`ChannelAdapter.java:8-17`. Es la versión de `docs/vision.md` §5 que hoy hace falta: los dos métodos de parseo y el
envío de texto. `windowState` y `startConversation` llegan con #12, que trae la ventana de conversación y las
plantillas (P-046).

```java
interface ChannelAdapter {
    ChannelType type();
    List<InboundMessage> parseInbound(WebhookPayload payload);
    List<StatusUpdate> parseStatusUpdates(WebhookPayload payload);
    SendResult send(ContactIdentity to, ChannelAccount from, MessageContent content);
}
```

| Método | Contrato |
|---|---|
| `type()` | El `ChannelType` que atiende. Es la clave del registro: dos adaptadores con el mismo tipo impiden arrancar la app. |
| `parseInbound(payload)` | Los mensajes de clientes que trae el webhook, en el orden del payload, como `InboundMessage`. Cada uno lleva el id de **nuestra** cuenta (`externalAccountId`, para `resolve`), la identidad del remitente ya normalizada (`ContactIdentity`), el `externalId` del mensaje, el texto y la hora que informa el proveedor. El `externalId` es único por cuenta y estable entre reenvíos (P-042): parsear dos veces el mismo payload da lo mismo. Si el payload no trae mensajes, lista vacía. Payload inválido: `InvalidWebhookPayloadException`. |
| `parseStatusUpdates(payload)` | Los reportes de estado de mensajes salientes, en el orden del payload, como `StatusUpdate(externalAccountId, externalId, status)`. No deduplica ni reordena: un `READ` seguido de un `DELIVERED` del mismo mensaje sale así, y la regla de avance la aplica `Timeline.applyStatus` con `Message.advanceTo` (P-041). Sin reportes, lista vacía. Payload inválido: `InvalidWebhookPayloadException`. |
| `send(to, from, content)` | Manda el texto a la identidad desde nuestra cuenta y devuelve `SendResult` con el id que le dio el proveedor (el que después llega en los `StatusUpdate`). Las fallas de envío y la validación de la ventana y de `allowsOutbound` son de #12. |

El adaptador no toca la base, no conoce la organización (la saca #10 de la cuenta con `resolve`) ni la persona: recibe
y devuelve value objects (`ContactIdentity`, `MessageContent`) y la cuenta de canal, no la entidad `Person`, que es
interna de `people` (P-019).

Del lado de #10, la idea es: `registry.adapterFor(tipo)` → `parseInbound` → por cada `InboundMessage`,
`directory.resolve(tipo, externalAccountId)` y el flujo de «Contrato de `resolve`», con `externalId`, `content` y
`occurredAt` del `InboundMessage` y `sender` como identidad para `PersonDirectory.resolveOrCreate`.

## Registro de adaptadores (`ChannelAdapterRegistry`)

`@Component` que Spring arma con la lista de todos los beans `ChannelAdapter` (`ChannelAdapterRegistry.java:15-19`):
un mapa inmutable por `type()`.

| Entrada | Salida |
|---|---|
| `new ChannelAdapterRegistry(List.of(sandbox, whatsApp))`, después `adapterFor(SANDBOX)` | El mismo objeto `sandbox` |
| `adapterFor(WHATSAPP)` sin adaptador de WhatsApp | `UnsupportedChannelException` («No channel adapter handles WHATSAPP», `ChannelAdapterRegistry.java:23-25`) |
| Dos adaptadores con el mismo `type()` en el constructor | `IllegalStateException` («Channel type SANDBOX has two adapters: SandboxChannelAdapter and AnotherSandboxAdapter», `ChannelAdapterRegistry.java:29-32`). En la app, el contexto de Spring no levanta |

Un tipo sin adaptador no impide arrancar: falla recién al pedirlo (hoy solo `SANDBOX` tiene adaptador). Ninguna de
las dos excepciones se traduce a HTTP: no hay endpoint (P-047).

## Adaptador `SANDBOX` (`SandboxChannelAdapter`)

Canal de prueba en memoria: sirve como borde falso en los tests de los flujos que vienen (#10, #12), en lugar de
Meta o Telegram (`.claude/rules/tests.md`), y para probar a mano cuando exista el endpoint de webhooks. Es un bean
siempre activo, sin perfil ni propiedad (P-048).

### Formato del payload del sandbox

Un JSON propio (no imita a ningún proveedor), con un objeto por webhook:

```json
{
  "account": "sandbox-acme",
  "messages": [
    {"id": "in-1", "from": {"kind": "PHONE", "value": "+54 9 11 5555-0101"},
     "text": "Hola, ¿tienen financiación?", "sentAt": "2026-03-02T08:59:20Z"},
    {"id": "in-2", "from": {"kind": "TELEGRAM_USER", "value": "juan_perez"},
     "text": "¿Hacen envíos al interior?", "sentAt": "2026-03-02T09:01:05Z"}
  ],
  "statuses": [
    {"messageId": "sandbox-out-1", "status": "READ"},
    {"messageId": "sandbox-out-1", "status": "DELIVERED"},
    {"messageId": "sandbox-out-2", "status": "FAILED"}
  ]
}
```

| Campo | Regla | Pasa a |
|---|---|---|
| `account` | Obligatorio, no en blanco (`SandboxChannelAdapter.java:64`) | `externalAccountId` de todos los mensajes y estados del payload |
| `messages` | Opcional: ausente o `null` es lista vacía; un elemento `null` es inválido (`SandboxChannelAdapter.java:65-68`) | Un `InboundMessage` por elemento, en orden |
| `messages[].id` | Obligatorio, no en blanco (`SandboxChannelAdapter.java:83`) | `externalId` (único por cuenta, P-042) |
| `messages[].from` | Obligatorio, objeto con `kind` y `value` (`SandboxChannelAdapter.java:84`) | `sender` |
| `messages[].from.kind` | Obligatorio (`SandboxChannelAdapter.java:102`); nombre exacto de `IdentityKind`, en mayúsculas: `PHONE`, `EMAIL`, `INSTAGRAM_USER`, `MESSENGER_USER`, `TELEGRAM_USER`. Un nombre desconocido lo rechaza Jackson al leer el enum | `sender.kind` |
| `messages[].from.value` | Obligatorio; se normaliza con `new ContactIdentity(kind, value)` (`SandboxChannelAdapter.java:106-108`): teléfono a E.164, email a minúsculas, usuarios recortados (`docs/personas.md`) | `sender.value` |
| `messages[].text` | Obligatorio y no vacío (`MessageContent`); se aceptan espacios y no se recorta | `content` |
| `messages[].sentAt` | Obligatorio, instante ISO-8601 (`SandboxChannelAdapter.java:86`). No se completa con el reloj | `occurredAt` |
| `statuses` | Opcional, como `messages` | Un `StatusUpdate` por elemento, en orden |
| `statuses[].messageId` | Obligatorio, no en blanco (`SandboxChannelAdapter.java:119`): el `externalId` que devolvió `send` | `externalId` |
| `statuses[].status` | Obligatorio (`SandboxChannelAdapter.java:120`, que solo exige no nulo); nombre exacto de `MessageStatus`: `SENT`, `FAILED`, `DELIVERED`, `READ`, `DELETED`. El nombre lo controla Jackson al leer el enum | `status` |

Salida del ejemplo (como en `SandboxChannelAdapterTest`):

```java
sandbox.parseInbound(payload);
// [InboundMessage("sandbox-acme", ContactIdentity.phone("+5491155550101"), "in-1",
//          new MessageContent("Hola, ¿tienen financiación?"), Instant.parse("2026-03-02T08:59:20Z")),
//  InboundMessage("sandbox-acme", new ContactIdentity(TELEGRAM_USER, "juan_perez"), "in-2",
//          new MessageContent("¿Hacen envíos al interior?"), Instant.parse("2026-03-02T09:01:05Z"))]

sandbox.parseStatusUpdates(payload);
// [StatusUpdate("sandbox-acme", "sandbox-out-1", READ),
//  StatusUpdate("sandbox-acme", "sandbox-out-1", DELIVERED),
//  StatusUpdate("sandbox-acme", "sandbox-out-2", FAILED)]
```

Por la configuración por defecto de Jackson 3 (`JsonMapper.shared()`, `SandboxChannelAdapter.java:22`), y sin test
propio: los campos desconocidos se ignoran, el texto después del objeto JSON se rechaza, un `sentAt` con desfase
(`-03:00`) se convierte a UTC y los escalares se convierten a texto (`"text": 123` es `"123"`). El mapper es estático
y propio: el formato no depende de la configuración `spring.jackson.*` de la app.

### Parseo y payloads inválidos

`parseInbound` y `parseStatusUpdates` llaman al mismo privado `parsed` (`SandboxChannelAdapter.java:51-59`), que lee
el JSON en records anidados package-private (`SandboxWebhook`, `SandboxMessage`, `SandboxSender`, `SandboxStatus`,
`SandboxChannelAdapter.java:61-126`) que validan presencia con `Assert` en su constructor compacto, y arma **las dos**
listas en un `SandboxNotifications` (`SandboxChannelAdapter.java:55`, `:128-129`). Por eso es todo o nada: un
estado inválido hace fallar también `parseInbound`, y un mensaje inválido, `parseStatusUpdates`.

Atrapa exactamente `JacksonException`, `IllegalArgumentException` e `InvalidContactIdentityException`
(`SandboxChannelAdapter.java:56-58`) y lanza `InvalidWebhookPayloadException(SANDBOX)`: mensaje
«Invalid SANDBOX webhook payload», sin causa encadenada (el error de Jackson o de `ContactIdentity` puede citar el
payload). No hay `catch (Exception)`: un error que no sea de datos se propaga tal cual.

| Payload | Por qué es inválido |
|---|---|
| `esto no es JSON`, `""` | `JacksonException` |
| `null` (JSON válido) | `readValue` devuelve `null`; lo rechaza `Assert.notNull` (`SandboxChannelAdapter.java:54`) |
| Sin `account`, o `"account": "  "` | `Assert.hasText` de `SandboxWebhook` |
| Mensaje sin `id`, `from`, `text` o `sentAt` | `Assert` de `SandboxMessage` |
| `"text": ""` | `MessageContent` exige texto no vacío |
| `"kind": "FAX"`, `"status": "SEEN"` | Jackson no conoce ese valor del enum |
| `"value": "+54 12"` con `PHONE`, `"value": "ana.perez"` con `EMAIL` | `InvalidContactIdentityException` |
| `"sentAt": "ayer a la tarde"` | Jackson no lo lee como `Instant` |
| `"messages": [null]`, `"statuses": [null]` | `Assert.noNullElements` de `SandboxWebhook` |
| Estado sin `messageId` o sin `status` | `Assert` de `SandboxStatus` |

`WebhookPayload` con `body` nulo no llega al adaptador: lo rechaza su propio constructor con
`IllegalArgumentException` («A webhook payload needs its body»).

Un `id` repetido dentro del mismo payload no se rechaza: salen dos `InboundMessage` con el mismo `externalId`, y la
deduplicación es la de `Timeline.recordReceived` (P-042).

Los `toString` de los records del parseo no exponen datos personales: `SandboxMessage[<id>]`
(`SandboxChannelAdapter.java:93-96`) y `SandboxSender[<kind>]` (`SandboxChannelAdapter.java:110-113`); los de
`ContactIdentity` y `MessageContent` ya ocultan el valor y el texto.

### Envíos en memoria y `hasSentTextTo`

```java
SendResult result = sandbox.send(ContactIdentity.phone("+5491155550101"), cuentaSandbox,
        new MessageContent("Sí, hasta 12 cuotas"));
// SendResult[externalId=sandbox-6f1c2d3e-…]   (un UUID nuevo por envío)

sandbox.hasSentTextTo(ContactIdentity.phone("+5491155550101"), "Sí, hasta 12 cuotas");   // true
sandbox.hasSentTextTo(ContactIdentity.phone("+5491155550101"), "Hasta 6 cuotas");        // false
sandbox.hasSentTextTo(ContactIdentity.phone("+5491155550202"), "Sí, hasta 12 cuotas");   // false
```

- `send` (`SandboxChannelAdapter.java:41-45`) agrega un `SandboxDelivery(to, content)` a una
  `ConcurrentLinkedQueue` (`SandboxChannelAdapter.java:24`, `:131-132`) y devuelve `sandbox-<UUID aleatorio>`: dos
  envíos iguales tienen ids distintos. No guarda la cuenta `from` ni la valida (ni su tipo ni `allowsOutbound`).
- `hasSentTextTo(to, text)` (`SandboxChannelAdapter.java:47-49`, package-private) busca un envío igual: misma
  identidad (por tipo y valor normalizado, así que `phone("+54 9 11 5555-0101")` también coincide) y mismo texto
  exacto. No mira la cuenta de origen. Un texto vacío da `IllegalArgumentException` (lo construye como
  `MessageContent`).
- No hay método para vaciar los envíos: en un contexto de Spring compartido el bean acumula los de todos los tests,
  así que cada test usa identidades propias.

### Diagramas

Piezas del puerto:

```mermaid
classDiagram
    class ChannelAdapter {
        <<interface>>
        type() ChannelType
        parseInbound(WebhookPayload) List~InboundMessage~
        parseStatusUpdates(WebhookPayload) List~StatusUpdate~
        send(ContactIdentity, ChannelAccount, MessageContent) SendResult
    }
    class ChannelAdapterRegistry {
        adapterFor(ChannelType) ChannelAdapter
    }
    class SandboxChannelAdapter {
        hasSentTextTo(ContactIdentity, String) boolean
    }
    ChannelAdapterRegistry o-- "por tipo" ChannelAdapter
    ChannelAdapter <|.. SandboxChannelAdapter
    ChannelAdapter ..> InboundMessage
    ChannelAdapter ..> StatusUpdate
    ChannelAdapter ..> SendResult
```

Parseo del sandbox (`SandboxChannelAdapter.java:31-39`, `:51-59`):

```mermaid
flowchart TD
    P["parseInbound / parseStatusUpdates<br/>(WebhookPayload)"] --> R["JsonMapper.shared().readValue<br/>→ SandboxWebhook"]
    R -->|JSON roto, enum o fecha desconocidos,<br/>falta un campo| X[InvalidWebhookPayloadException<br/>Invalid SANDBOX webhook payload]
    R -->|null| X
    R --> B["arma las dos listas:<br/>InboundMessage (ContactIdentity, MessageContent)<br/>y StatusUpdate"]
    B -->|identidad o texto inválidos| X
    B --> S[SandboxNotifications]
    S -->|parseInbound| I[List de InboundMessage]
    S -->|parseStatusUpdates| U[List de StatusUpdate]
```

Envío y consulta en un test:

```mermaid
sequenceDiagram
    participant T as Test o flujo de #12
    participant S as SandboxChannelAdapter
    participant Q as ConcurrentLinkedQueue
    T->>S: send(phone(+5491155550101), cuenta, "Sí, hasta 12 cuotas")
    S->>Q: add(SandboxDelivery(to, content))
    S-->>T: SendResult(sandbox-<uuid>)
    T->>S: hasSentTextTo(phone(+5491155550101), "Sí, hasta 12 cuotas")
    S->>Q: contains(SandboxDelivery(to, content))
    S-->>T: true
```

## Esquema

`backend/src/main/resources/db/migration/V7__create_channel_accounts.sql`:

```sql
CREATE TABLE channel_accounts (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations (id),
    channel_type TEXT NOT NULL,
    external_account_id TEXT NOT NULL,
    display_name TEXT NOT NULL,
    allows_outbound BOOLEAN NOT NULL,
    CONSTRAINT channel_accounts_channel_type_external_account_id_key UNIQUE (channel_type, external_account_id),
    CONSTRAINT channel_accounts_id_organization_id_channel_type_key UNIQUE (id, organization_id, channel_type)
);

CREATE INDEX channel_accounts_organization_id_idx ON channel_accounts (organization_id);

ALTER TABLE messages
    ADD CONSTRAINT messages_channel_account_id_organization_id_channel_fkey
        FOREIGN KEY (channel_account_id, organization_id, channel)
        REFERENCES channel_accounts (id, organization_id, channel_type);
```

Restricciones:

- `channel_accounts_channel_type_external_account_id_key` (`V7__create_channel_accounts.sql:8`) es **global**, sin
  `organization_id`: un mismo número o página no puede pertenecer a dos organizaciones, porque entonces un webhook no
  sabría a cuál asignarse (P-044). Un intento de registrarla en otra organización falla con
  `DataIntegrityViolationException` que nombra la restricción.
- `channel_accounts_id_organization_id_channel_type_key` (`V7__create_channel_accounts.sql:9`) es redundante con la
  clave primaria, pero hace falta como destino de la FK de tres columnas de `messages`.
- `messages_channel_account_id_organization_id_channel_fkey` (`V7__create_channel_accounts.sql:14-17`) garantiza que la
  cuenta de un mensaje exista, sea de la misma organización y tenga el mismo tipo que su `channel`, aunque alguien
  inserte por SQL (P-045). Implica la FK de dos columnas que anunciaba P-039, así que no se agregó el `UNIQUE (id,
  organization_id)` que esa decisión preveía. Es `NO ACTION`: borrar una cuenta con mensajes, o cambiarle el tipo,
  falla.
- La FK implícita a `organizations` (`V7__create_channel_accounts.sql:3`) rechaza guardar sin organización
  (centinela `UUID(0, 0)`).
- Sin `CHECK` de valores de `channel_type` ni de id recortado o no vacío, como `messages.channel` y `operators.role`:
  lo garantiza `register` y, en el SQL de desarrollo, quien lo escribe.

Índices y las consultas que sirven:

| Índice | Consulta |
|---|---|
| `channel_accounts_channel_type_external_account_id_key` (el `UNIQUE` global) | El paso 1 de `resolve` (`WHERE channel_type = ? AND external_account_id = ?`) y el paso 2, al que Hibernate le agrega `AND organization_id = ?`. |
| `channel_accounts_id_organization_id_channel_type_key` | Destino de la FK de `messages`: el `INSERT` de un mensaje busca la cuenta por `(id, organization_id, channel_type)`. |
| `channel_accounts_organization_id_idx` (`V7__create_channel_accounts.sql:12`) | El lado de `channel_accounts` de la FK a `organizations` y el futuro listado de cuentas de una organización (#11). Lo pide `.claude/rules/flyway.md`. |
| `messages_organization_id_channel_account_id_external_id_key` (de V6) | El lado de `messages` de la FK nueva: al borrar una cuenta o cambiarle la clave, el chequeo busca mensajes por `organization_id` y `channel_account_id`, que son el prefijo de ese `UNIQUE`. No hizo falta un índice nuevo en `messages`. |

Fila de ejemplo después de `ChannelsFixture.account(acme, WHATSAPP, "  106540352242922 ", true)`:

```
channel_accounts: id = <id>, organization_id = <Acme>, channel_type = WHATSAPP,
                  external_account_id = 106540352242922, display_name = Ventas, allows_outbound = true
```

Concurrencia: el `INSERT` de un mensaje toma `FOR KEY SHARE` sobre la fila de su cuenta (chequeo de la FK). No choca
con otros mensajes ni con el rival de `concurrentDuplicatedWebhooksRecordASingleMessage`; solo esperaría a un `DELETE`
de la cuenta o a un cambio de su clave, que hoy nadie hace.

## Alta de una cuenta en desarrollo

No hay endpoint ni servicio de alta (llega con #11). Con el Postgres de compose levantado y las migraciones ya
aplicadas, se carga por SQL. La fila tiene que cumplir lo que garantizaría `ChannelAccount.register`, porque la base no
lo controla:

- `channel_type` con el nombre exacto del enum `ChannelType` (`WHATSAPP`, `INSTAGRAM`, `MESSENGER`, `TELEGRAM`,
  `EMAIL`, `SMS`, `WEBCHAT`, `SANDBOX`; `ChannelType.java:3-12`; un valor distinto rompe la carga de la entidad);
- `external_account_id` no vacío y sin espacios alrededor (si no, `resolve`, que recorta lo que recibe y rechaza un id
  en blanco, no lo encuentra);
- `display_name` no vacío ni en blanco (`register` lo exige con `Assert.hasText`; la columna solo es `NOT NULL`);
- `organization_id` de una organización existente (FK; ver «Alta de un operador en desarrollo» en
  `docs/autenticacion.md` para crear una).

Desde la raíz del repo, con los valores por defecto de `.env.example` y la organización de ejemplo de
`docs/autenticacion.md`:

```bash
docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U communicator_one -d communicator_one <<'SQL'
INSERT INTO channel_accounts (id, organization_id, channel_type, external_account_id, display_name, allows_outbound)
VALUES (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'WHATSAPP', '106540352242922', 'Ventas', true);
SQL
```

Un segundo `INSERT` con el mismo `channel_type` y `external_account_id` falla por
`channel_accounts_channel_type_external_account_id_key`, aunque sea para otra organización.

Una cuenta del canal de prueba es igual, con `channel_type = 'SANDBOX'` y como `external_account_id` el mismo valor
que van a traer los payloads en `account` (por ejemplo, `'sandbox-acme'`). Mientras no haya endpoint de webhooks
(#10), no hay forma de mandarle un payload a la app corriendo.

## Tests

`backend/src/test/java/com/communicatorone/channels/ChannelAccountTest.java` (sin Spring). 1 método, 7 ejecuciones.

| Test | Qué cubre |
|---|---|
| `channelAccountWithoutTypeExternalIdOrDisplayNameIsRejected` | Parametrizado: tipo nulo; id nulo, `""` y `"   "`; nombre nulo, `""` y `"   "`. Cada uno da `IllegalArgumentException` con su mensaje exacto. |

`backend/src/test/java/com/communicatorone/channels/ChannelAccountDirectoryTest.java` (`@SpringBootTest` con
`RANDOM_PORT` + Testcontainers, sin `@Transactional`). Cada test crea sus organizaciones con `ChannelsFixture`, que
registra cuentas con `callAs` + `ChannelAccount.register` (nombre «Ventas») y compara con el record
`ChannelsFixture.RegisteredAccount(id, organizationId, channelType, allowsOutbound)`. Como la base es compartida y el
`UNIQUE` es global, los ids externos llevan un sufijo al azar (`ChannelsFixture.externalAccountId(prefix)`). 9 métodos,
12 ejecuciones con los parametrizados.

| Test | Qué cubre |
|---|---|
| `eachAccountResolvesToItsOwnOrganizationWithoutAnActiveOrganization` | Acme (`allowsOutbound = true`) y Globex (`false`) con cuentas `WHATSAPP`: sin ámbito ni usuario, cada id resuelve a su cuenta con id, organización, tipo y `allowsOutbound`. |
| `unknownExternalAccountIdResolvesNothing` | Con una cuenta en Acme, otro id del mismo tipo da vacío. |
| `sameExternalAccountIdOnAnotherChannelIsAnotherAccount` | El mismo id en `WHATSAPP` (Acme) y en `TELEGRAM` (Globex): se registran las dos y cada tipo resuelve a la suya. |
| `externalAccountIdCannotBelongToTwoOrganizations` | Registrar en Globex el id `WHATSAPP` de Acme da `DataIntegrityViolationException` con `channel_accounts_channel_type_external_account_id_key` en el mensaje; Globex queda con 0 cuentas y `resolve` sigue devolviendo la de Acme. |
| `channelAccountOfAnotherOrganizationIsNotVisible` | Bajo `callAs(globex)`, `findById` y `findByChannelTypeAndExternalAccountId` de la cuenta de Acme dan vacío; bajo `callAs(acme)` la encuentran (control). |
| `accountRegisteredWithSurroundingSpacesResolvesByItsTrimmedId` | Registrada con espacios alrededor: la resuelven el id recortado y el id con otros espacios. |
| `resolutionIgnoresAnotherActiveOrganization` | `resolve` dentro de `callAs(globex)` devuelve la cuenta de Acme. |
| `resolvingWithoutChannelTypeOrExternalAccountIdIsRejected` | Parametrizado: `(null, "123")`, `(WHATSAPP, null)` y `(WHATSAPP, "  ")` dan `IllegalArgumentException` con su mensaje. |
| `channelAccountCannotBeResolvedInsideAnOpenTransactionalScope` | Parametrizado con `Propagation.REQUIRED` (transacción real) y `Propagation.SUPPORTS` (ámbito sin transacción): `resolve` de un id `WHATSAPP` que no está registrado da `IllegalStateException` con el mensaje exacto. Usa un id desconocido a propósito: así prueba que el chequeo es al entrar y no depende de llegar al `callAs`. |

`backend/src/test/java/com/communicatorone/messaging/TimelineTest.java` suma tres tests de la FK nueva (detalle en
`docs/mensajes.md`, «Tests»): `messageCannotPointToAChannelAccountOfAnotherOrganization`,
`messageCannotPointToAnUnknownChannelAccount` y `messageOnAChannelOtherThanItsAccountTypeIsRejected`. Los tres afirman
`DataIntegrityViolationException`, ninguna fila con ese `externalId` y, en la salida, el nombre de
`messages_channel_account_id_organization_id_channel_fkey`. Para eso, `MessagingFixture.channelAccount` ahora inserta
por SQL una cuenta real (`WHATSAPP` por defecto, o del tipo que se le pase; `MessagingFixture.java:61-73`), sin
depender de clases de `channels`.

`backend/src/test/java/com/communicatorone/channels/ChannelAdapterRegistryTest.java` (#43, unitario, sin Spring ni
base). Usa el `SandboxChannelAdapter` real y adaptadores de mentira declarados en el propio test
(`WhatsAppAdapterStandIn`, `AnotherSandboxAdapter`) que solo responden `type()`. 3 métodos, 3 ejecuciones.

| Test | Qué cubre |
|---|---|
| `registryReturnsTheAdapterOfItsChannelType` | Con el sandbox y uno de WhatsApp, `adapterFor` devuelve la misma instancia de cada tipo. |
| `channelTypeWithoutAdapterIsRejected` | Solo con el sandbox, `adapterFor(WHATSAPP)` da `UnsupportedChannelException` con el mensaje exacto. |
| `twoAdaptersForTheSameChannelTypeAreRejected` | Dos adaptadores `SANDBOX` en el constructor dan `IllegalStateException` con el mensaje exacto, que nombra las dos clases. |

`backend/src/test/java/com/communicatorone/channels/SandboxChannelAdapterTest.java` (#43, unitario, sin Spring ni
base). Los payloads van en text blocks del propio test (formato propio, P-048), no en `src/test/resources`. `send`
usa `ChannelAccount.register(SANDBOX, "sandbox-acme", "Pruebas", true)` sin persistir. 8 métodos, 45 ejecuciones
(el parametrizado corre 19 payloads en los dos métodos de parseo).

| Test | Qué cubre |
|---|---|
| `sandboxPayloadBecomesInboundMessagesOfItsAccount` | El payload de ejemplo da dos `InboundMessage` iguales a los esperados, en orden: teléfono con espacios normalizado a E.164, usuario de Telegram, cuenta del payload, `externalId`, texto y hora. |
| `reparsingTheSamePayloadGivesTheSameMessages` | Parsear dos veces el mismo payload da listas iguales, con los `externalId` `in-1` e `in-2` (idempotencia de #10, P-042). |
| `sandboxPayloadBecomesStatusUpdatesOfItsAccountInTheOrderTheyArrived` | Tres estados, con `READ` antes que `DELIVERED` para el mismo mensaje: salen tal cual y en el orden del payload (el parser no reordena ni deduplica). |
| `payloadWithOnlyStatusesHasNoInboundMessages` | Sin `messages`: `parseInbound` da vacío y `parseStatusUpdates`, el estado. |
| `payloadWithOnlyMessagesHasNoStatusUpdates` | Sin `statuses`: `parseStatusUpdates` da vacío y `parseInbound`, el mensaje. |
| `malformedSandboxPayloadIsRejected` | Parametrizado, cada caso en `parseInbound` y en `parseStatusUpdates`: no JSON, vacío, `null`, sin `account` o en blanco, mensaje sin `id`, `from`, `text` o `sentAt`, texto vacío, `kind` desconocido, teléfono y email inválidos, fecha mal formada, mensaje `null`, estado sin `messageId` o sin `status`, `status` desconocido y estado `null`. Afirma `InvalidWebhookPayloadException`, el mensaje exacto y que no tiene causa. |
| `sendingRecordsTheTextForItsRecipient` | `send` devuelve un id `sandbox-…`; `hasSentTextTo` da `true` para ese destinatario y texto, y `false` con otro texto o con otro destinatario. |
| `eachSendGetsItsOwnExternalId` | Dos envíos iguales a un email tienen ids distintos con prefijo `sandbox-`, y el texto consta como enviado. |

Sin test en #43: el registro armado por Spring con los beans reales (lo cubre el primer test de integración de #10) y
los comportamientos por defecto de Jackson descritos en «Formato del payload del sandbox».

`TenantIsolationTest.everyBusinessEntityBelongsToAnOrganization` cubre que `ChannelAccount` lleve `@TenantId`, y
`ModularStructureTest` que `channels` no use internos de `messaging`, `organizations` ni `people`.

El caso de una sesión de JPA ligada al hilo sin sincronización de transacción (OSIV) no tiene test en
`ChannelAccountDirectoryTest`: lo frena `callAs`, probado en
`TenantIsolationTest.organizationCannotBeSwitchedWhileASessionIsOpen`.

Comando: `./mvnw test -Dtest='ChannelAccount*Test,TimelineTest'` desde `backend/` (skill `run-tests`); los de #43, sin
Docker: `./mvnw test -Dtest='ChannelAdapterRegistryTest,SandboxChannelAdapterTest'`.

## Riesgos y pendientes

- **El `UNIQUE` global revela que la cuenta existe en otra organización.** Cuando haya alta de cuentas (#11), una
  organización que intente registrar un número que ya es de otra va a recibir un error de unicidad, y con eso sabe que
  ese número está dado de alta en el sistema. P-031 oculta el `DETAIL` (los valores) en el log, pero el rechazo en sí
  es la señal. #11 decide qué responde (por ejemplo, un error genérico) y si exige verificar la propiedad de la cuenta.
- **Una cuenta con mensajes no se puede borrar** (ni cambiarle el tipo): la FK de `messages` es `NO ACTION`. La baja
  tiene que ser un estado (activa/inactiva), no un `DELETE` (#11).
- **Visibilidad: resuelta con paquete plano.** #43 dejó el puerto, el registro y el sandbox en la raíz de
  `channels`, todo package-private (P-046), así que `ChannelAccount` y `ChannelAccountDirectory` siguen sin ser
  `public`. Los próximos adaptadores van en la misma raíz con prefijo (`WhatsAppChannelAdapter`). Si #10 termina en un
  módulo por encima de `channels`, hace falta un record público en vez de exponer la entidad.
- **Sin control de valores en la base**: un `INSERT` por SQL con un `channel_type` que no existe en el enum rompe la
  carga de la entidad, y uno con espacios en el id no se resuelve.
- **La V7 valida las filas existentes de `messages`** al agregar la FK: una base local con mensajes insertados a mano
  (con cuentas al azar, como en #8) hace fallar la migración. Se recrea con `docker compose down -v && docker compose
  up -d`. El `ALTER TABLE messages` toma `SHARE ROW EXCLUSIVE` sin `lock_timeout`; riesgo bajo mientras no haya
  producción (la misma mitigación que en `docs/mensajes.md`, «Riesgos»).
- **Webhooks repetidos y desordenados.** El sandbox entrega el mismo `externalId` al reparsear y no reordena los
  estados (lo fijan sus tests), pero el escenario completo (el mismo webhook dos veces, estados fuera de orden) se
  prueba en `TimelineTest` (`duplicatedWebhookDoesNotDuplicateMessage`,
  `deliveryReportArrivingAfterTheReadReportDoesNotMoveTheStatusBack`, entre otros) y se va a probar de punta a punta
  en #10. Un `id` repetido dentro del mismo payload tampoco se rechaza en el parser: lo deduplica `Timeline`.
- **Datos personales en memoria sin tope.** `SandboxChannelAdapter` es un bean siempre activo y guarda cada envío
  (identidad y texto) en una cola que nunca se vacía. Hoy no pasa nada porque nadie le envía fuera de los tests, pero
  con una cuenta `SANDBOX` cargada en un servidor de larga vida, los envíos a esa cuenta se acumulan en memoria. El
  freno (perfil, propiedad o bean solo de test) lo decide #10 al abrir el endpoint de webhooks (P-048).
- **Excepciones package-private.** `InvalidWebhookPayloadException` y `UnsupportedChannelException` no son públicas y
  no se traducen a HTTP. Si #10 las traduce en `ApiExceptionHandler` (paquete raíz), las tiene que hacer públicas en la
  raíz de `channels` (P-046, P-047).
- **El registro armado por Spring no tiene test en #43**: solo se prueba construyéndolo a mano. Que el contexto lo
  arme con los beans reales (hoy solo el sandbox) lo cubre el primer test de integración de #10.
- **`WebhookPayload` es solo el cuerpo como texto**, sin headers ni bytes crudos. La firma `X-Hub-Signature-256` de
  Meta se calcula sobre los bytes exactos del cuerpo: #11 reevalúa si suma headers o `byte[]` (P-046).
- **#10 (pipeline entrante)**: llama a `resolve` fuera de transacción, decide qué hacer con un webhook de una cuenta
  desconocida y abre `runAs(account.organizationId(), ...)` pasando `account.channelType()` como canal.
- **#10 (pipeline entrante)**, por #43: usa `ChannelAdapterRegistry.adapterFor` y los dos métodos de parseo, decide
  qué responde ante `InvalidWebhookPayloadException` y `UnsupportedChannelException`, y el freno del sandbox.
- **#11**: credenciales, URL o secreto del webhook, «tiene bot», alta, baja (como estado) y listado de cuentas; el
  adaptador de WhatsApp (`WhatsAppChannelAdapter`, con payloads de Meta en `src/test/resources`) y la firma.
- **#12**: el envío lee `allowsOutbound`, que hoy solo leen los tests; modela las fallas de envío y suma al puerto
  `windowState` y `startConversation` (`docs/vision.md` §5).
