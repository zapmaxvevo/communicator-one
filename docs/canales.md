# Canales y cuentas de canal

Issues #42 y #43 (partes de #9) y #10 (pipeline de entrada). Decisiones en `docs/decisiones.md`: P-043 (entidad y
alcance), P-044 (unicidad global y resolución entre organizaciones), P-045 (FK de tres columnas desde `messages`, que
reemplaza la de dos columnas que anunciaba P-039), P-046 (forma del puerto `ChannelAdapter`), P-047 (registro de
adaptadores), P-048 (adaptador `SANDBOX`), P-049 (ubicación del pipeline y sin endpoint), P-050 (orden, transacciones
y casos borde del pipeline) y P-051 (evento `MessageReceived` y listeners). Se apoya en `docs/multi-tenant.md`, en los
mensajes de `docs/mensajes.md`, en las identidades de `docs/personas.md` y en las oportunidades de
`docs/oportunidades.md`.

## Resumen

Una `ChannelAccount` es **nuestra** identidad en un canal: el número de WhatsApp, la página de Facebook, la cuenta de
Instagram o el bot de Telegram por el que una organización recibe y manda mensajes. Es de una sola organización y la
identifica el par **tipo de canal + id que le dio el proveedor** (`externalAccountId`): el `phone_number_id` de
WhatsApp, el id de la página, el id del bot.

Cuando llega un webhook, el proveedor dice a qué cuenta suya iba dirigido, pero no a qué organización. Por eso el
módulo `channels` resuelve la cuenta **sin organización activa**: `ChannelAccountDirectory.resolve(channelType,
externalAccountId)` busca la cuenta en todas las organizaciones y la devuelve con su `organizationId`, para que el
pipeline entrante (`InboundPipeline`, #10) abra el ámbito de esa organización antes de resolver la persona, la
oportunidad y el mensaje.

La base también controla, desde #42, que todo mensaje apunte a una cuenta que existe, que es de su misma organización
y cuyo tipo coincide con el `channel` del mensaje (FK de tres columnas, P-045).

Desde #43, cada canal se habla a través de un **puerto** común, `ChannelAdapter` (`docs/vision.md` §5): convierte el
cuerpo de un webhook en mensajes entrantes (`InboundMessage`) y en reportes de estado (`StatusUpdate`), y manda un
texto a una identidad de contacto desde una cuenta nuestra. `ChannelAdapterRegistry` da el adaptador de cada
`ChannelType`. El primer adaptador es el del canal de prueba `SANDBOX` (`SandboxChannelAdapter`): entiende un JSON
propio (ver «Formato del payload del sandbox») y guarda en memoria lo que «envía», para que los tests afirmen con
`hasSentTextTo(identidad, texto)` sin un proveedor real.

Desde #10, `InboundPipeline.receive(channelType, payload)` procesa un webhook de punta a punta: parsea con el adaptador
del canal todos los mensajes y estados, y recién después escribe. Por cada mensaje resuelve la cuenta (y con ella la
organización), descarta el reenvío, resuelve o crea la persona, registra la actividad en su oportunidad, guarda el
mensaje y, si no era un duplicado, publica el evento `MessageReceived`. Por cada estado, lo aplica con
`Timeline.applyStatus` al mensaje con ese `externalId` en la cuenta. Un mensaje o estado para una cuenta que no
conocemos se descarta con un WARN. Se va a activar con cada webhook cuando una tarea exponga el endpoint; hoy solo lo
llaman los tests (ver «Pipeline de entrada (#10)»).

Fuera de #42, #43 y #10: el endpoint de webhooks, su seguridad (ruta pública y firma) y la traducción a HTTP de las
excepciones del pipeline (la tarea que exponga el endpoint, P-049); credenciales, URL o secreto del webhook, «tiene
bot», alta, baja y listado de cuentas, el adaptador de WhatsApp con la firma de Meta y el nombre visible de la persona
(#11); el envío, que va a leer `allowsOutbound`, las fallas de envío, `windowState` y `startConversation` (#12); los
listeners de `MessageReceived` y los eventos de `opportunities` que necesiten garantía de entrega (#13 a #15, P-051).

## Piezas

Módulo `channels` (`backend/src/main/java/com/communicatorone/channels/`). Depende solo de tipos públicos de la raíz
de otros módulos: de `messaging`, `ChannelType` (P-038), desde #43 `MessageContent` y `MessageStatus`, y desde #10
`Timeline` y `ReceivedMessage`; de `organizations`, `CurrentOrganization`; de `people`, desde #43 `ContactIdentity`,
`IdentityKind` e `InvalidContactIdentityException`, y desde #10 `PersonDirectory`; y, desde #10, de `opportunities`,
`OpportunityTracker` (dependencia nueva, P-049). No hay ciclo: ni `messaging`, ni `people`, ni `opportunities` conocen
a `channels`. Todo es package-private salvo el evento `MessageReceived` (público desde #10, para que otros módulos lo
escuchen) y vive en el paquete raíz del módulo, sin subpaquetes: el pipeline de #10 vive en el mismo módulo (P-043,
P-049), y los adaptadores van planos en la raíz con el canal como prefijo (`SandboxChannelAdapter`; después
`WhatsAppChannelAdapter`, P-046).

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
| `SandboxChannelAdapter` | package-private | `@Component` del canal `SANDBOX` (`SandboxChannelAdapter.java:22-132`), siempre activo. Parsea el JSON propio del sandbox con records anidados package-private y guarda los envíos en memoria; `hasSentTextTo` es la consulta para los tests. Detalle en «Adaptador `SANDBOX`». |

Pipeline de entrada (#10):

| Clase | Visibilidad | Qué hace |
|---|---|---|
| `InboundPipeline` | package-private | `@Service` sin `@Transactional` (`InboundPipeline.java:16-17`) con un único método, `receive(ChannelType, WebhookPayload)` (`InboundPipeline.java:43-54`). Recibe en el constructor package-private `ChannelAdapterRegistry`, `ChannelAccountDirectory`, `CurrentOrganization`, `PersonDirectory`, `OpportunityTracker`, `Timeline` y el `ApplicationEventPublisher` de Spring (`InboundPipeline.java:31-41`). Privados: `inOrganizationOf` (resolver la cuenta y correr la acción en el `runAs` de su organización, o descartar con WARN), `loggable` (sanear el id para el log) y `accept` (un mensaje). Contrato en «Pipeline de entrada (#10)». |
| `MessageReceived` | **pública** (API) | `record (UUID organizationId, UUID messageId, UUID personId, UUID opportunityId, UUID channelAccountId, ChannelType channel)` (`MessageReceived.java:6-8`): el evento de un mensaje entrante nuevo. Sin texto ni identidad del remitente (datos personales). Semántica en «Evento `MessageReceived`». |

El `package-info.java` con `@ApplicationModule` ya existía desde el scaffold y `ModularStructureTest` ya declaraba
`channels`. #42 suma la migración V7 (ver «Esquema»), que además agrega una restricción a `messages`. #43 no tiene
migración: `channel_accounts.channel_type` y `messages.channel` son `TEXT` sin `CHECK`, así que `SANDBOX`
(`ChannelType.java:11`) entra sin tocar el esquema, y no aparece en `backend/openapi.json` (ningún endpoint lo
expone). #10 tampoco tiene migración ni cambia `SecurityConfiguration`, `ApiExceptionHandler`, `openapi.json` ni el
`pom.xml` (sin `spring-modulith-starter-jpa`, P-009).

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
   asignarlo. `InboundPipeline` lo descarta con un WARN y sigue (ver «Cuenta desconocida»).
3. **Después abre el ámbito de la organización de la cuenta** y pasa el tipo de la cuenta como canal del mensaje. Así
   lo hace `InboundPipeline` (`InboundPipeline.java:48`, `:56-62`, `:75-76`):

```java
inOrganizationOf(channelType, inbound.externalAccountId(), account -> accept(account, inbound));
// inOrganizationOf:
accounts.resolve(channelType, externalAccountId).ifPresentOrElse(
        account -> currentOrganization.runAs(account.organizationId(), () -> action.accept(account)),
        () -> log.warn(...));
// dentro de accept:
timeline.recordReceived(new ReceivedMessage(personId, opportunityId, account.id(),
        account.channelType(), inbound.externalId(), inbound.content(), inbound.occurredAt()));
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
    participant W as InboundPipeline (#10)
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

El adaptador no toca la base, no conoce la organización (la saca `InboundPipeline` de la cuenta con `resolve`) ni la
persona: recibe y devuelve value objects (`ContactIdentity`, `MessageContent`) y la cuenta de canal, no la entidad
`Person`, que es interna de `people` (P-019).

`InboundPipeline` usa `registry.adapterFor(tipo)`, `parseInbound` y `parseStatusUpdates`; de cada `InboundMessage`
toma `externalAccountId` para `resolve`, `sender` como identidad para `PersonDirectory.resolveOrCreate`, y
`externalId`, `content` y `occurredAt` para el mensaje (ver «Pipeline de entrada (#10)»).

## Registro de adaptadores (`ChannelAdapterRegistry`)

`@Component` que Spring arma con la lista de todos los beans `ChannelAdapter` (`ChannelAdapterRegistry.java:15-19`):
un mapa inmutable por `type()`.

| Entrada | Salida |
|---|---|
| `new ChannelAdapterRegistry(List.of(sandbox, whatsApp))`, después `adapterFor(SANDBOX)` | El mismo objeto `sandbox` |
| `adapterFor(WHATSAPP)` sin adaptador de WhatsApp | `UnsupportedChannelException` («No channel adapter handles WHATSAPP», `ChannelAdapterRegistry.java:23-25`) |
| Dos adaptadores con el mismo `type()` en el constructor | `IllegalStateException` («Channel type SANDBOX has two adapters: SandboxChannelAdapter and AdapterStandIn», `ChannelAdapterRegistry.java:29-32`). En la app, el contexto de Spring no levanta |

Un tipo sin adaptador no impide arrancar: falla recién al pedirlo (hoy solo `SANDBOX` tiene adaptador). Ninguna de
las dos excepciones se traduce a HTTP: no hay endpoint (P-047; #10 tampoco lo expone, P-049).

## Adaptador `SANDBOX` (`SandboxChannelAdapter`)

Canal de prueba en memoria: sirve como borde falso en los tests de los flujos (#10, y después #12), en lugar de
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
| `account` | Obligatorio, no en blanco (`SandboxChannelAdapter.java:71`) | `externalAccountId` de todos los mensajes y estados del payload |
| `messages` | Opcional: ausente o `null` es lista vacía; un elemento `null` es inválido (`SandboxChannelAdapter.java:72-75`) | Un `InboundMessage` por elemento, en orden |
| `messages[].id` | Obligatorio, no en blanco (lo exige `InboundMessage`, `InboundMessage.java:14`) | `externalId` (único por cuenta, P-042) |
| `messages[].from` | Obligatorio, objeto con `kind` y `value` (`SandboxChannelAdapter.java:90`) | `sender` |
| `messages[].from.kind` | Obligatorio (`SandboxChannelAdapter.java:107`); nombre exacto de `IdentityKind`, en mayúsculas: `PHONE`, `EMAIL`, `INSTAGRAM_USER`, `MESSENGER_USER`, `TELEGRAM_USER`. Un nombre desconocido o un número (`0`) los rechaza Jackson al leer el enum | `sender.kind` |
| `messages[].from.value` | Obligatorio; se normaliza con `new ContactIdentity(kind, value)` (`SandboxChannelAdapter.java:110-112`), que también rechaza el valor nulo o en blanco: teléfono a E.164, email a minúsculas, usuarios recortados (`docs/personas.md`) | `sender.value` |
| `messages[].text` | Obligatorio y no vacío (lo exige `MessageContent`); se aceptan espacios y no se recorta | `content` |
| `messages[].sentAt` | Obligatorio (`SandboxChannelAdapter.java:91`); texto con un instante ISO-8601, que se convierte con `Instant.parse` (`SandboxChannelAdapter.java:95`). Un desfase (`2026-03-02T05:59:20-03:00`) pasa a UTC; un número, un epoch como texto (`"1700000000"`) o espacios alrededor se rechazan. No se completa con el reloj | `occurredAt` |
| `statuses` | Opcional, como `messages` | Un `StatusUpdate` por elemento, en orden |
| `statuses[].messageId` | Obligatorio, no en blanco (lo exige `StatusUpdate`, `StatusUpdate.java:10`): el `externalId` que devolvió `send` | `externalId` |
| `statuses[].status` | Obligatorio (lo exige `StatusUpdate`, `StatusUpdate.java:11`, que solo pide no nulo); nombre exacto de `MessageStatus`: `SENT`, `FAILED`, `DELIVERED`, `READ`, `DELETED`. El nombre lo controla Jackson al leer el enum, que también rechaza un número (`1`) o un número como texto (`"1"`) | `status` |

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

El mapper es estático y propio, con dos reglas estrictas sobre la configuración por defecto de Jackson 3
(`SandboxChannelAdapter.java:25-28`):

- `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES`: un campo desconocido, en la raíz o en cualquier elemento (por
  ejemplo, `"mesages"` mal escrito o un `"channel"` de más en un mensaje), hace inválido el payload. Así un error de
  tipeo no se convierte en una lista vacía en silencio.
- `EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS`: `kind` y `status` se escriben solo por nombre. Un número (`"kind": 0`,
  `"status": 1`) o un número como texto (`"status": "1"`) se rechaza en vez de tomarse como posición del enum, que
  cambiaría de significado si se reordena `IdentityKind` o `MessageStatus`.

El formato no depende de la configuración `spring.jackson.*` de la app.

Otros comportamientos de Jackson 3, sin test propio: el texto después del objeto JSON se rechaza, y en los campos de
texto (`account`, `id`, `text`, `value`, `messageId`) un escalar se convierte a texto (`"text": 123` es `"123"`).
`sentAt` no depende de esa conversión: se lee como texto y lo valida `Instant.parse`.

### Parseo y payloads inválidos

`parseInbound` y `parseStatusUpdates` llaman al mismo privado `parsed` (`SandboxChannelAdapter.java:57-66`), que lee
el JSON en records anidados package-private (`SandboxWebhook`, `SandboxMessage`, `SandboxSender`, `SandboxStatus`,
`SandboxChannelAdapter.java:68-125`), y arma **las dos** listas en un `SandboxNotifications`
(`SandboxChannelAdapter.java:61`, `:127-128`). Los records anidados validan solo lo que no valida nadie más: `account`
y que no haya elementos `null` en `SandboxWebhook` (`SandboxChannelAdapter.java:70-76`), `from` y `sentAt` en
`SandboxMessage` (`:90-91`) y `kind` en `SandboxSender` (`:107`). El resto lo rechazan, dentro del mismo `try`,
`Instant.parse` y los constructores de `InboundMessage`, `MessageContent`, `ContactIdentity` y `StatusUpdate` al armar
las listas. Como cada llamada arma las dos
listas, es todo o nada: un estado inválido hace fallar también `parseInbound`, y un mensaje inválido, `parseStatusUpdates`.

Atrapa exactamente `JacksonException`, `IllegalArgumentException`, `InvalidContactIdentityException` y
`DateTimeParseException` (`SandboxChannelAdapter.java:62-65`) y lanza `InvalidWebhookPayloadException(SANDBOX)`: mensaje
«Invalid SANDBOX webhook payload», sin causa encadenada (el error de Jackson, de `ContactIdentity` o de `Instant.parse`
puede citar el payload). No hay `catch (Exception)`: un error que no sea de datos se propaga tal cual.

| Payload | Por qué es inválido |
|---|---|
| `esto no es JSON`, `""` | `JacksonException` |
| `null` (JSON válido) | `readValue` devuelve `null`; lo rechaza `Assert.notNull` (`SandboxChannelAdapter.java:60`) |
| Sin `account`, o `"account": "  "` | `Assert.hasText` de `SandboxWebhook` |
| `"mesages": [...]` (clave mal escrita), un mensaje con `"channel": "WHATSAPP"` de más | Jackson rechaza la propiedad desconocida (`FAIL_ON_UNKNOWN_PROPERTIES`) |
| Mensaje sin `from` o sin `sentAt` | `Assert` de `SandboxMessage` |
| Mensaje sin `id` | `Assert` de `InboundMessage` |
| Mensaje sin `text`, o `"text": ""` | `MessageContent` exige texto no vacío |
| Remitente sin `value` (`"from": {"kind": "PHONE"}`) | `InvalidContactIdentityException` |
| `"kind": "FAX"`, `"status": "SEEN"` | Jackson no conoce ese valor del enum |
| `"kind": 0`, `"status": 1`, `"status": "1"` | Jackson rechaza el número como valor del enum (`FAIL_ON_NUMBERS_FOR_ENUMS`, `InvalidFormatException`) |
| `"value": "+54 12"` con `PHONE`, `"value": "ana.perez"` con `EMAIL` | `InvalidContactIdentityException` |
| `"sentAt": "ayer a la tarde"`, `"sentAt": 1700000000` | `Instant.parse` lanza `DateTimeParseException` (el número llega como el texto `"1700000000"`) |
| `"messages": [null]`, `"statuses": [null]` | `Assert.noNullElements` de `SandboxWebhook` |
| Estado sin `messageId` o sin `status` | `Assert` de `StatusUpdate` |

`WebhookPayload` con `body` nulo no llega al adaptador: lo rechaza su propio constructor con
`IllegalArgumentException` («A webhook payload needs its body»).

Un `id` repetido dentro del mismo payload no se rechaza: salen dos `InboundMessage` con el mismo `externalId`, y la
deduplicación es la de `Timeline.recordReceived` (P-042).

Los `toString` de los records del parseo no exponen datos personales: `SandboxMessage[<id>]`
(`SandboxChannelAdapter.java:98-101`) y `SandboxSender[<kind>]` (`SandboxChannelAdapter.java:114-117`); los de
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

- `send` (`SandboxChannelAdapter.java:47-51`) agrega un `SandboxDelivery(to, content)` a una
  `ConcurrentLinkedQueue` (`SandboxChannelAdapter.java:30`, `:130-131`) y devuelve `sandbox-<UUID aleatorio>`: dos
  envíos iguales tienen ids distintos. No guarda la cuenta `from` ni la valida (ni su tipo ni `allowsOutbound`).
- `hasSentTextTo(to, text)` (`SandboxChannelAdapter.java:53-55`, package-private) busca un envío igual: misma
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

Parseo del sandbox (`SandboxChannelAdapter.java:37-45`, `:57-66`):

```mermaid
flowchart TD
    P["parseInbound / parseStatusUpdates<br/>(WebhookPayload)"] --> R["JSON.readValue (mapper estricto)<br/>→ SandboxWebhook"]
    R -->|JSON roto, campo o enum desconocidos,<br/>enum como número,<br/>falta account, from, sentAt o kind,<br/>elemento null| X[InvalidWebhookPayloadException<br/>Invalid SANDBOX webhook payload]
    R -->|null| X
    R --> B["arma las dos listas:<br/>InboundMessage (ContactIdentity, MessageContent)<br/>y StatusUpdate"]
    B -->|falta id, messageId o status, fecha inválida,<br/>identidad o texto inválidos| X
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

## Pipeline de entrada (#10)

`InboundPipeline` (`InboundPipeline.java:16-82`) orquesta un webhook entrante: es el flujo entrante de
`docs/vision.md` §5, pasos 1 a 5, más los reportes de estado de los mensajes salientes. Vive en `channels` porque usa
las piezas package-private del módulo (cuenta, directorio, registro, payload) y porque `messaging` no puede orquestar
sin armar un ciclo (P-038, P-049). No tiene endpoint HTTP: lo expone, con su seguridad y la traducción de las
excepciones, la tarea que abra el endpoint de webhooks (P-049).

### Contrato de `receive`

```java
void receive(ChannelType channelType, WebhookPayload payload)
```

- **Entradas:** el tipo de canal (de la ruta del futuro endpoint) y el cuerpo del webhook tal como llegó. No valida sus
  argumentos: un tipo nulo falla en el mapa del registro (`NullPointerException`), un `WebhookPayload` nulo falla en el
  adaptador (`NullPointerException` de `payload.body()` en `SandboxChannelAdapter.parsed`, que no está entre las
  excepciones que atrapa, `SandboxChannelAdapter.java:59`, `:62-63`; no es `InvalidWebhookPayloadException`) y el
  contenido del payload lo valida el adaptador. Se llama **sin transacción ni sesión de JPA abiertas** y sin
  organización activa: la organización sale de cada cuenta.
- **Salida:** nada. Los efectos son los registros en la base (persona e identidad, oportunidad, mensaje, estado) y el
  evento `MessageReceived` por cada mensaje nuevo.
- **Excepciones que propaga** (no atrapa ninguna):

| Excepción | Cuándo | Qué quedó escrito |
|---|---|---|
| `UnsupportedChannelException` | El tipo no tiene adaptador (`ChannelAdapterRegistry.adapterFor`) | Nada |
| `InvalidWebhookPayloadException` | El adaptador rechaza el payload (todo o nada, P-048), aunque traiga partes válidas | Nada |
| `IllegalStateException` de `resolve` | Se llamó dentro de una transacción o de un ámbito `SUPPORTS`, con al menos un mensaje o estado en el payload | Nada: `resolve` es el primer paso de cada mensaje y de cada estado. Sin test propio |
| Cualquier error de `resolveOrCreate`, `recordActivity`, `recordReceived` o `applyStatus` (por ejemplo, `DataAccessException`) | Falla la base en un mensaje o estado | Los mensajes anteriores del payload y, del que falló, lo que ya confirmó (persona, actividad); ver «Carreras y falla parcial» |

Ninguna de las dos excepciones del canal es pública ni se traduce a HTTP todavía (P-046, P-047, P-049).

Ejemplo, con una cuenta `SANDBOX` de Acme registrada como `sandbox-acme` y el reloj en `2026-03-02T09:00:00Z`:

```json
{
  "account": "sandbox-acme",
  "messages": [
    {"id": "in-1", "from": {"kind": "PHONE", "value": "+54 9 11 5555-0101"},
     "text": "Hola, ¿tienen financiación?", "sentAt": "2026-03-02T08:59:20Z"}
  ],
  "statuses": [
    {"messageId": "sandbox-out-1", "status": "READ"}
  ]
}
```

```
pipeline.receive(SANDBOX, payload);

person_identities: person_id = <Ana>, kind = PHONE, value = +5491155550101
opportunities:     id = <op>, person_id = <Ana>, source = SANDBOX, status = OPEN,
                   opened_at = last_activity_at = 2026-03-02T09:00:00Z
messages:          id = <msg>, person_id = <Ana>, opportunity_id = <op>, channel_account_id = <cuenta>,
                   channel = SANDBOX, sender_type = CUSTOMER, external_id = in-1, status = DELIVERED,
                   occurred_at = 2026-03-02T08:59:20Z, recorded_at = 2026-03-02T09:00:00Z
evento:            MessageReceived(<Acme>, <msg>, <Ana>, <op>, <cuenta>, SANDBOX)
sandbox-out-1:     pasa a READ si es un mensaje de esa cuenta con un estado de rango menor;
                   si no existe en esa cuenta, se ignora
```

| Entrada | Resultado |
|---|---|
| Mensaje de una identidad desconocida | Persona nueva con esa identidad normalizada, oportunidad `OPEN` con `source` = nombre del canal y `openedAt` = `lastActivityAt` = reloj, mensaje `CUSTOMER`/`DELIVERED` ligado a las dos, un `MessageReceived` |
| El mismo payload otra vez (reenvío del proveedor), con el reloj más adelante | Nada nuevo: ni persona, ni actividad (`lastActivityAt` no cambia), ni mensaje, ni evento |
| El mismo `id` dos veces en un payload | Un mensaje y un evento |
| Persona conocida, mensaje nuevo | Misma persona y misma oportunidad, `lastActivityAt` adelantado al reloj, otro mensaje, otro evento |
| Persona que vuelve pasada la ventana de reingreso (N + W) | La oportunidad anterior queda `CLOSED`/`NO_RESPONSE` en `L + N` (cierre al vuelo de `recordActivity`) y el mensaje cae en una oportunidad nueva |
| Dos remitentes en un payload | Dos personas, dos oportunidades, cada mensaje y evento con lo suyo |
| El mismo teléfono a cuentas de Acme y de Globex | Una persona por organización; cada mensaje y evento en la organización de la cuenta que lo recibió |
| La misma persona a dos cuentas de la misma organización | Una persona y una oportunidad, dos mensajes, cada uno con su cuenta |
| Mensaje o estado para una cuenta que no está registrada | WARN y descarte, sin excepción ni escritura en ninguna organización |
| Payload con un teléfono inválido junto a uno válido | `InvalidWebhookPayloadException`; nada guardado, sin evento |
| Estado `READ` y después `DELIVERED` del mismo saliente | Queda `READ` (`Message.advanceTo`, P-041); sin evento |
| Estado de un `externalId` que no está en esa cuenta | Ignorado (`UNKNOWN_MESSAGE`), sin error; los demás estados del payload se aplican |

### Parsear todo, después escribir

`receive` (`InboundPipeline.java:43-54`):

1. `adapters.adapterFor(channelType)` (`:44`).
2. `adapter.parseInbound(payload)` y `adapter.parseStatusUpdates(payload)` (`:45-46`), **antes de la primera
   escritura**. Con el sandbox, cada parseo arma las dos listas (P-048), así que un payload inválido falla en el
   primero; pero el orden no depende de eso: un adaptador que validara cada lista por separado tampoco dejaría nada a
   medio escribir.
3. Los mensajes, en el orden del payload (`:47-49`): por cada uno, `inOrganizationOf(channelType,
   externalAccountId, account -> accept(account, inbound))`, que resuelve la cuenta y, si existe, corre `accept` dentro
   de `currentOrganization.runAs(account.organizationId(), ...)`.
4. Después los estados, en el orden del payload (`:50-53`): por cada uno, `inOrganizationOf` con
   `account -> timeline.applyStatus(account.id(), update.externalId(), update.status())`.
   Van después de los mensajes porque un estado del mismo payload puede referirse a un mensaje recién guardado.

Cada mensaje y cada estado resuelve su cuenta y abre su propio `runAs`, aunque el payload del sandbox traiga una sola
cuenta: en el puerto, cada `InboundMessage` y cada `StatusUpdate` trae su propio `externalAccountId` (P-046).

### Un mensaje (`accept`)

Dentro del `runAs` de la organización de la cuenta (`InboundPipeline.java:69-81`), sin transacción propia:

1. `timeline.isRecorded(account.id(), inbound.externalId())` (`:70-72`, transacción de solo lectura). Si ya está,
   corta: un reenvío no crea persona, no cuenta como actividad y no publica evento.
2. `people.resolveOrCreate(inbound.sender())` (`:73`, transacción 1, P-029).
3. `opportunities.recordActivity(personId, account.channelType().name())` (`:74`, transacción 2, P-035). La fuente es
   el nombre del canal de la cuenta (`"SANDBOX"`, después `"WHATSAPP"`); solo se usa si se abre una oportunidad nueva.
4. `timeline.recordReceived(new ReceivedMessage(...))` (`:75-76`, transacción 3, P-042), con el canal tomado de la
   cuenta (`account.channelType()`), que es lo que cumple la FK de tres columnas (P-045).
5. Si `!recorded.duplicate()`, `events.publishEvent(new MessageReceived(...))` (`:77-80`), con el `organizationId` de
   la cuenta (el accesor de la entidad, `.claude/rules/java.md`). Se publica fuera de toda transacción: la 3 ya
   confirmó.

Para un mensaje nuevo, `isRecorded` y `recordReceived` hacen la misma búsqueda por cuenta + `externalId`: se acepta esa
consulta repetida, barata (índice único), a cambio de no contar un reenvío como actividad (P-050, `docs/mensajes.md`).

Las tres transacciones están separadas porque `resolveOrCreate`, `recordActivity` y `recordReceived` rechazan una
transacción abierta (en PostgreSQL una violación aborta la transacción en curso, y su reintento o relectura necesita
una limpia), y porque `runAs` tiene que abrirse fuera de toda transacción (`docs/multi-tenant.md`).

```mermaid
sequenceDiagram
    participant P as InboundPipeline
    participant A as SandboxChannelAdapter
    participant D as ChannelAccountDirectory
    participant CO as CurrentOrganization
    participant T as Timeline
    participant PD as PersonDirectory
    participant OT as OpportunityTracker
    participant E as ApplicationEventPublisher
    P->>A: parseInbound(payload) y parseStatusUpdates(payload)
    Note over P,A: payload inválido: InvalidWebhookPayloadException, nada escrito
    loop cada InboundMessage, en orden
        P->>D: resolve(SANDBOX, externalAccountId)
        alt cuenta desconocida
            D-->>P: Optional.empty
            Note over P: WARN y sigue con el próximo
        else cuenta de Acme
            D-->>P: cuenta
            P->>CO: runAs(Acme, accept)
            CO->>T: isRecorded(cuenta, externalId)
            alt ya registrado
                T-->>CO: true (corta: sin actividad ni evento)
            else nuevo
                CO->>PD: resolveOrCreate(sender) [tx 1]
                CO->>OT: recordActivity(personId, "SANDBOX") [tx 2]
                CO->>T: recordReceived(ReceivedMessage) [tx 3]
                T-->>CO: RecordedMessage(id, duplicate)
                opt duplicate = false
                    CO->>E: publishEvent(MessageReceived)
                end
            end
        end
    end
    loop cada StatusUpdate, en orden
        P->>D: resolve(SANDBOX, externalAccountId)
        P->>CO: runAs(organización, applyStatus)
        CO->>T: applyStatus(cuenta, externalId, status)
        Note over T: ADVANCED / UNCHANGED / UNKNOWN_MESSAGE, sin evento
    end
```

### Estados de entrega

Por cada `StatusUpdate`: `resolve` de la cuenta, `runAs` de su organización y `Timeline.applyStatus(account.id(),
externalId, status)` (`InboundPipeline.java:50-53`), que es `@Transactional` y bloquea la fila del mensaje. El
resultado (`ADVANCED`, `UNCHANGED` o `UNKNOWN_MESSAGE`) no se usa: un estado que no hace avanzar (llegó tarde, P-041) o
de un mensaje que no está en esa cuenta se ignora sin error y sin log, y el pipeline sigue con el próximo. Los estados
no publican evento (la notificación del cambio de estado es de #15, `docs/mensajes.md`).

### Cuenta desconocida

`inOrganizationOf` (`InboundPipeline.java:56-62`) llama a `resolve` y, si vuelve vacío, loguea un WARN (`:60-61`) en vez
de abrir el `runAs` (`:59`), así que el mensaje o el estado se descarta sin excepción ni escritura: no hay organización
a la que asignarlo (P-050), y el resto del payload se sigue procesando. El mismo WARN sirve para mensajes y estados:

```
Inbound SANDBOX notification for unknown channel account sandbox-desconocida was discarded
```

Lleva solo el tipo de canal y el id de nuestra cuenta, nunca el remitente ni el texto. El id viene del payload, que
todavía no tiene firma verificada: antes de loguearlo, el privado `loggable` (`InboundPipeline.java:64-67`) lo trunca a
64 caracteres (`MAX_LOGGED_ID_LENGTH`, `:20`) y reemplaza cada carácter de control ASCII (`CONTROL_CHARACTERS`,
`\p{Cntrl}`, `:21`; entre ellos CR y LF) por `_`, para que un payload no pueda falsear líneas del log ni inflarlo. Por
ejemplo, `"cuenta\r\nWARN falso"` se loguea como `cuenta__WARN falso`.

### Carreras y falla parcial

- **Dos copias del mismo webhook en paralelo**: las dos pueden pasar `isRecorded`. La persona converge en una
  (P-029); `recordActivity` serializa las dos actividades sobre la misma oportunidad (P-035), así que solo
  `lastActivityAt` puede quedar en el mayor de dos instantes casi iguales, lo que es inofensivo; `recordReceived`
  inserta un solo mensaje y la otra copia recibe `duplicate = true`. Resultado: un mensaje y un evento (P-050). Lo
  prueba `copyOfTheWebhookRecordedConcurrentlyElsewherePublishesNoEvent`, con la copia rival insertada por SQL sin
  confirmar.
- **Falla dentro de un mensaje**: si falla la transacción 2 o la 3, lo anterior queda confirmado (la persona; con la 3,
  también la actividad, sin mensaje). El reintento del proveedor la encuentra y vuelve a registrar actividad, que
  adelanta otra vez `lastActivityAt` (`docs/oportunidades.md`, «Riesgos»).
- **Falla a mitad de un payload**: la excepción corta el procesamiento; los mensajes anteriores ya están confirmados, y
  los siguientes y todos los estados del payload no se procesan. En el reintento, `isRecorded` saltea los confirmados.

### Evento `MessageReceived`

```java
public record MessageReceived(UUID organizationId, UUID messageId, UUID personId, UUID opportunityId,
        UUID channelAccountId, ChannelType channel) {}
```

- Lo publica `InboundPipeline` con el `ApplicationEventPublisher` de Spring, **solo si el mensaje no era un duplicado**
  (`InboundPipeline.java:77-80`), dentro del `runAs` pero **fuera de toda transacción**. Un reenvío o una copia que
  perdió la carrera no publica nada.
- Es de `channels` (no de `messaging`, que no orquesta, P-038) y es público para que otros módulos lo escuchen. Lleva
  solo ids y el canal: ni el texto ni la identidad del remitente. No dice si la oportunidad es nueva: eso lo resuelve
  #13 (P-051).
- **Entrega como mucho una vez.** No hay registro de publicaciones (`event_publication`; sin
  `spring-modulith-starter-jpa`, P-009 sigue vigente): si el proceso cae entre la transacción 3 y la publicación, o si
  un listener falla, el evento se pierde, y el reintento del webhook no lo vuelve a publicar porque `isRecorded` corta.
  Lo que necesite garantía (el reparto de #13, el temporizador de #14) va a colgar de un evento de `opportunities`
  publicado dentro de su transacción, y entonces se suma el starter con su migración (P-051).
- **Listeners**: la convención única es `@Async @TransactionalEventListener(fallbackExecution = true)`, sin
  `@Transactional`, con `runAs(event.organizationId(), ...)` (`.claude/rules/java.md`, `docs/multi-tenant.md`). Como
  `MessageReceived` se publica sin transacción, corre en el momento (por `fallbackExecution`) y en otro hilo (por
  `@Async`). Fuera de los tests todavía no hay listeners, ni `@EnableAsync`. Un `@EventListener` síncrono, como el
  colector de los tests, corre en el hilo del webhook dentro del `runAs`, y una excepción suya cortaría el resto del
  payload.

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
que van a traer los payloads en `account` (por ejemplo, `'sandbox-acme'`). Mientras ninguna tarea exponga un endpoint
de webhooks (#10 no lo hace, P-049), no hay forma de mandarle un payload a la app corriendo.

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
base). Usa el `SandboxChannelAdapter` real y un adaptador de mentira declarado en el propio test,
`AdapterStandIn(ChannelType)`, que solo responde `type()`. 3 métodos, 3 ejecuciones.

| Test | Qué cubre |
|---|---|
| `registryReturnsTheAdapterOfItsChannelType` | Con el sandbox y uno de WhatsApp, `adapterFor` devuelve la misma instancia de cada tipo. |
| `channelTypeWithoutAdapterIsRejected` | Solo con el sandbox, `adapterFor(WHATSAPP)` da `UnsupportedChannelException` con el mensaje exacto. |
| `twoAdaptersForTheSameChannelTypeAreRejected` | Dos adaptadores `SANDBOX` en el constructor dan `IllegalStateException` con el mensaje exacto, que nombra las dos clases. |

`backend/src/test/java/com/communicatorone/channels/SandboxChannelAdapterTest.java` (#43, unitario, sin Spring ni
base). Los payloads van en text blocks del propio test (formato propio, P-048), no en `src/test/resources`. `send`
usa `ChannelAccount.register(SANDBOX, "sandbox-acme", "Pruebas", true)` sin persistir. 8 métodos, 59 ejecuciones
(el parametrizado corre 26 payloads en los dos métodos de parseo).

| Test | Qué cubre |
|---|---|
| `sandboxPayloadBecomesInboundMessagesOfItsAccount` | El payload de ejemplo da dos `InboundMessage` iguales a los esperados, en orden: teléfono con espacios normalizado a E.164, usuario de Telegram, cuenta del payload, `externalId`, texto y hora. |
| `reparsingTheSamePayloadGivesTheSameMessages` | Parsear dos veces el mismo payload da listas iguales, con los `externalId` `in-1` e `in-2` (idempotencia de #10, P-042). |
| `sandboxPayloadBecomesStatusUpdatesOfItsAccountInTheOrderTheyArrived` | Tres estados, con `READ` antes que `DELIVERED` para el mismo mensaje: salen tal cual y en el orden del payload (el parser no reordena ni deduplica). |
| `payloadWithOnlyStatusesHasNoInboundMessages` | Sin `messages`: `parseInbound` da vacío y `parseStatusUpdates`, el estado. |
| `payloadWithOnlyMessagesHasNoStatusUpdates` | Sin `statuses`: `parseStatusUpdates` da vacío y `parseInbound`, el mensaje. |
| `malformedSandboxPayloadIsRejected` | Parametrizado, cada caso en `parseInbound` y en `parseStatusUpdates`: no JSON, vacío, `null`, sin `account` o en blanco, clave `messages` mal escrita, mensaje con un campo desconocido, `sentAt` numérico, mensaje sin `id`, `from`, `text` o `sentAt`, remitente sin `value`, texto vacío, `kind` desconocido o numérico, teléfono y email inválidos, fecha mal formada, mensaje `null`, estado sin `messageId` o sin `status`, `status` desconocido, numérico o como texto numérico, y estado `null`. Afirma `InvalidWebhookPayloadException`, el mensaje exacto y que no tiene causa. |
| `sendingRecordsTheTextForItsRecipient` | `send` devuelve un id `sandbox-…`; `hasSentTextTo` da `true` para ese destinatario y texto, y `false` con otro texto o con otro destinatario. |
| `eachSendGetsItsOwnExternalId` | Dos envíos iguales a un email tienen ids distintos. |

Sin test en #43: los comportamientos de Jackson marcados «sin test propio» en «Formato del payload del sandbox», y el
`sentAt` con desfase horario, con epoch como texto o con espacios alrededor. El registro armado por Spring con los
beans reales lo cubre desde #10 `InboundPipelineTest`, que usa el `InboundPipeline` del contexto.

`backend/src/test/java/com/communicatorone/channels/InboundPipelineTest.java` (#10). `@SpringBootTest` con
`RANDOM_PORT`, para compartir el contexto de Spring con los demás tests de `channels` (`ChannelAccountDirectoryTest`
usa lo mismo), + `@Import(TestcontainersConfiguration.class)` + `@TestConstructor(ALL)`, sin `@Transactional`. Corre
el pipeline real del contexto (registro con el `SandboxChannelAdapter`, directorio, `PersonDirectory`,
`OpportunityTracker`, `Timeline`) con payloads del sandbox; el único fake es el reloj (`MutableClock`, fijado en
`@BeforeEach`). Piezas de apoyo:

- `ChannelsFixture.sandboxAccount(organizationId)`: registra una cuenta `SANDBOX` con id externo al azar y devuelve
  `SandboxAccount(id, organizationId, externalAccountId)`.
- `InboundPipelineFixture`: arma los payloads (`messagesTo`, `statusesTo`, text block con el formato de P-048), siembra
  una conversación (`conversationWith`, con `resolveOrCreate` + `recordActivity`) y un saliente (`operatorReplied`, con
  `Timeline.recordSent`), y consulta la base con `JdbcTemplate` filtrando por organización (`identitiesOf`,
  `opportunitiesOf`, `messagesOf`) o en todas (`identitiesInAnyOrganizationWithValue`,
  `messagesInAnyOrganizationWithExternalId`). Para la carrera, `rivalStartsRecordingMessage` inserta por SQL un mensaje
  sin confirmar y `anotherSessionIsWaitingToWriteMessages` mira `pg_stat_activity`. Las filas se comparan enteras con
  los records `StoredIdentity`, `StoredOpportunity` y `StoredMessage`, y el evento esperado sale de la fila guardada
  con `StoredMessage.event(organizationId)`.
- `ReceivedMessages`: `@Component` de `src/test` con un `@EventListener` síncrono que junta los `MessageReceived` en
  una `ConcurrentLinkedQueue`; se vacía en `@BeforeEach` y se consulta por organización (`in`) o entera (`all`). No se
  usa `@RecordApplicationEvents` porque está ligado al hilo del test, y la carrera publica desde otro.

14 métodos, 14 ejecuciones.

| Test | Qué cubre |
|---|---|
| `messageFromUnknownIdentityCreatesPersonWithOpenOpportunityAndRecordsTheMessage` | Punta a punta con el teléfono escrito con espacios: una identidad `PHONE` en E.164; oportunidad `OPEN` con `source = SANDBOX` y `openedAt` = `lastActivityAt` = reloj; mensaje `CUSTOMER`/`DELIVERED` en el canal y la cuenta, con `externalId`, texto, `occurredAt` = `sentAt` y `recordedAt` = reloj, ligado a la persona y la oportunidad; exactamente un `MessageReceived` con esos ids. |
| `duplicatedWebhookDoesNotDuplicateMessageNorCountAsActivity` | El mismo payload dos veces, con el reloj 5 minutos adelante: una identidad, una oportunidad con `lastActivityAt` del primer instante, un mensaje, un evento. |
| `repeatedMessageInsideOneWebhookIsRecordedOnce` | El mismo `id` dos veces en un payload: una identidad, una oportunidad, un mensaje, un evento. |
| `knownPersonWritingAgainAddsTheMessageToHerOpenOpportunity` | Segundo webhook 2 horas después, con otro `id`: una persona, una oportunidad con `lastActivityAt` adelantado, dos mensajes en ella, dos eventos. |
| `personReturningAfterReentryWindowGetsNewOpportunityWithTheMessage` | Con los defaults (N = 7, W = 3 días), vuelve en `NOW + N + W`: la anterior `CLOSED`/`NO_RESPONSE` en `NOW + N`, una nueva `OPEN` con el segundo mensaje; cada evento con su oportunidad. |
| `webhookWithMessagesFromTwoPeopleRecordsEachOnItsOwnTimeline` | Dos remitentes en un payload: dos personas, dos oportunidades, cada mensaje y evento con su persona y oportunidad, en el orden del payload. |
| `messageIsRecordedInTheOrganizationOfTheAccountThatReceivedIt` | El mismo teléfono a una cuenta de Acme y a una de Globex: una persona distinta por organización, cada mensaje, oportunidad y evento en la suya, nada cruzado. |
| `samePersonWritingToTwoAccountsOfTheOrganizationKeepsOneOpportunity` | Dos cuentas `SANDBOX` de Acme: una persona, una oportunidad con `lastActivityAt` adelantado, dos mensajes con su cuenta cada uno, dos eventos. |
| `messageForUnknownChannelAccountIsDiscardedWithoutTouchingAnyOrganization` | Cuenta no registrada: sin excepción; ninguna identidad con ese teléfono ni mensaje con ese `externalId` en ninguna organización; Acme sin identidades ni oportunidades; ningún evento. |
| `invalidWebhookPayloadIsRejectedWithoutRecordingAnything` | Un teléfono sin `+` junto a uno válido: `InvalidWebhookPayloadException`; Acme sin identidades, oportunidades ni mensajes; ningún evento. |
| `copyOfTheWebhookRecordedConcurrentlyElsewherePublishesNoEvent` | La persona ya tiene conversación; un rival inserta el mismo `externalId` sin confirmar; el pipeline corre en otro hilo y se espera a que quede bloqueado en el `INSERT`; el rival confirma. Una identidad, la misma oportunidad, un solo mensaje (el del rival) y ningún evento. |
| `deliveryReportArrivingAfterTheReadReportDoesNotMoveTheStatusBack` | Un saliente recibe `READ` en un webhook y `DELIVERED` en otro: queda `READ`; ningún evento. |
| `statusForUnknownMessageIsIgnoredWithoutFailingTheWebhook` | Un estado de un `externalId` que no existe, seguido de uno válido: sin excepción, el saliente pasa a `DELIVERED`; ningún evento. |
| `statusForUnknownChannelAccountIsDiscarded` | Un estado a una cuenta no registrada con el `externalId` de un saliente real: sin excepción, el saliente sigue `SENT` sin cambios; ningún evento. |

Sin test en #10: el canal sin adaptador (lo cubre `ChannelAdapterRegistryTest.channelTypeWithoutAdapterIsRejected`; un
test con `WHATSAPP` se rompería con #11), la llamada dentro de una transacción (la frena `resolve`, probado en
`ChannelAccountDirectoryTest`), una falla de la base a mitad de un payload, y el texto y el saneamiento del WARN de
cuenta desconocida.

`TenantIsolationTest.everyBusinessEntityBelongsToAnOrganization` cubre que `ChannelAccount` lleve `@TenantId`, y
`ModularStructureTest` que `channels` no use internos de `messaging`, `organizations`, `people` ni `opportunities`.

El caso de una sesión de JPA ligada al hilo sin sincronización de transacción (OSIV) no tiene test en
`ChannelAccountDirectoryTest`: lo frena `callAs`, probado en
`TenantIsolationTest.organizationCannotBeSwitchedWhileASessionIsOpen`.

Comando: `./mvnw test -Dtest='ChannelAccount*Test,TimelineTest'` desde `backend/` (skill `run-tests`); los de #43, sin
Docker: `./mvnw test -Dtest='ChannelAdapterRegistryTest,SandboxChannelAdapterTest'`; el de #10, con Docker:
`./mvnw test -Dtest=InboundPipelineTest`.

## Riesgos y pendientes

- **El `UNIQUE` global revela que la cuenta existe en otra organización.** Cuando haya alta de cuentas (#11), una
  organización que intente registrar un número que ya es de otra va a recibir un error de unicidad, y con eso sabe que
  ese número está dado de alta en el sistema. P-031 oculta el `DETAIL` (los valores) en el log, pero el rechazo en sí
  es la señal. #11 decide qué responde (por ejemplo, un error genérico) y si exige verificar la propiedad de la cuenta.
- **Una cuenta con mensajes no se puede borrar** (ni cambiarle el tipo): la FK de `messages` es `NO ACTION`. La baja
  tiene que ser un estado (activa/inactiva), no un `DELETE` (#11).
- **Visibilidad: resuelta con paquete plano.** #43 dejó el puerto, el registro y el sandbox en la raíz de
  `channels`, todo package-private (P-046), así que `ChannelAccount` y `ChannelAccountDirectory` siguen sin ser
  `public`. Los próximos adaptadores van en la misma raíz con prefijo (`WhatsAppChannelAdapter`). #10 quedó en el
  mismo módulo (P-049), así que tampoco hizo falta un record público de la cuenta; lo único público es el evento
  `MessageReceived`.
- **Sin control de valores en la base**: un `INSERT` por SQL con un `channel_type` que no existe en el enum rompe la
  carga de la entidad, y uno con espacios en el id no se resuelve.
- **La V7 valida las filas existentes de `messages`** al agregar la FK: una base local con mensajes insertados a mano
  (con cuentas al azar, como en #8) hace fallar la migración. Se recrea con `docker compose down -v && docker compose
  up -d`. El `ALTER TABLE messages` toma `SHARE ROW EXCLUSIVE` sin `lock_timeout`; riesgo bajo mientras no haya
  producción (la misma mitigación que en `docs/mensajes.md`, «Riesgos»).
- **Webhooks repetidos y desordenados: resuelto en #10.** El mismo webhook dos veces, un `id` repetido en un payload
  y un `DELIVERED` después de un `READ` se prueban de punta a punta en `InboundPipelineTest`. El parser sigue sin
  rechazar un `id` repetido: lo deduplica `Timeline`.
- **Datos personales en memoria sin tope.** `SandboxChannelAdapter` es un bean siempre activo y guarda cada envío
  (identidad y texto) en una cola que nunca se vacía. Hoy no pasa nada porque nadie le envía fuera de los tests, pero
  con una cuenta `SANDBOX` cargada en un servidor de larga vida, los envíos a esa cuenta se acumulan en memoria. El
  freno (perfil, propiedad o bean solo de test) lo decide la tarea que exponga un endpoint para `SANDBOX` (P-048,
  ajustada en #10).
- **Excepciones package-private.** `InvalidWebhookPayloadException` y `UnsupportedChannelException` no son públicas y
  no se traducen a HTTP, y `InboundPipeline.receive` las propaga. La tarea que exponga el endpoint de webhooks decide
  qué responde al proveedor y, si las traduce en `ApiExceptionHandler` (paquete raíz), las hace públicas en la raíz de
  `channels` (P-046, P-047, P-049).
- **El pipeline no tiene endpoint** (P-049): hoy nadie fuera de los tests puede mandarle un webhook. La ruta, su
  seguridad (pública y con firma del proveedor, `.claude/rules/java.md`) y la respuesta ante payload inválido o canal
  sin adaptador quedan para la tarea que lo exponga.
- **`MessageReceived` se entrega como mucho una vez** (P-051): sin registro de publicaciones, una caída entre el
  guardado del mensaje y la publicación, o un listener que falla, pierden el evento, y el reenvío del webhook no lo
  repite. Sirve para notificar (WebSocket, #15); lo que necesite garantía cuelga de un evento de `opportunities`.
- **Sin `@EnableAsync`**: hasta que la tarea del primer listener la agregue, `@Async` no tiene efecto y un listener de
  `MessageReceived` correría en el hilo del webhook, donde una excepción suya cortaría el resto del payload (ver
  «Evento `MessageReceived`»).
- **Falla parcial de un payload**: una falla de la base a mitad de un payload deja confirmados los mensajes anteriores
  y no procesa los siguientes ni los estados; el reintento del proveedor saltea los confirmados. Un mensaje que falla
  entre `recordActivity` y `recordReceived` deja la actividad sin mensaje (`docs/oportunidades.md`).
- **`WebhookPayload` es solo el cuerpo como texto**, sin headers ni bytes crudos. La firma `X-Hub-Signature-256` de
  Meta se calcula sobre los bytes exactos del cuerpo: #11 reevalúa si suma headers o `byte[]` (P-046).
- **#10 (pipeline entrante): hecho.** Llama a `resolve` fuera de transacción, descarta con WARN la cuenta
  desconocida, abre `runAs(account.organizationId(), ...)` pasando `account.channelType()` como canal, usa el registro
  y los dos métodos de parseo, y aplica los estados (ver «Pipeline de entrada (#10)»).
- **#11**: credenciales, URL o secreto del webhook, «tiene bot», alta, baja (como estado) y listado de cuentas; el
  adaptador de WhatsApp (`WhatsAppChannelAdapter`, con payloads de Meta en `src/test/resources`) y la firma; el nombre
  visible de la persona que traiga el proveedor (`profile.name`, P-030 ajustada en #10).
- **#12**: el envío lee `allowsOutbound`, que hoy solo leen los tests; modela las fallas de envío y suma al puerto
  `windowState` y `startConversation` (`docs/vision.md` §5). Además: `Timeline.applyStatus` no distingue la dirección
  del mensaje, así que un estado con el `externalId` de un **entrante** de esa cuenta lo haría avanzar (por ejemplo, de
  `DELIVERED` a `READ`). Con los proveedores reales los ids de entrantes y salientes no chocan, y el sandbox no tiene
  endpoint; #12 decide si `applyStatus` se limita a los salientes.
