# Código limpio: nombres, funciones y estructura

## Contenido

- Nombres significativos
- Funciones pequeñas
- Separar dominio de infraestructura
- Dependencias explícitas
- Evitar efectos secundarios ocultos
- Inmutabilidad cuando simplifique el diseño
- Fail fast
- Excepciones con significado
- Evitar métodos que hagan demasiado

---

## Nombres significativos

Preferir nombres que expliquen el dominio.

Evitar:

```java
data
result
obj
tmp
process()
handle()
doIt()
```

cuando el contexto permita nombres más precisos.

Preferir:

```java
person
opportunity
template
assignment
resolveOrCreate()
assignOwner()
startConversation()
```

Los nombres deben expresar intención, no implementación. Usar el glosario de `CLAUDE.md`.

---

## Funciones pequeñas

Una función debería hacer una cosa.

Si una función:

1. valida datos,
2. consulta la base,
3. transforma información,
4. aplica reglas,
5. envía un mensaje,
6. registra logs,

probablemente tiene demasiadas responsabilidades.

Extraer comportamiento solamente cuando mejore la comprensión o reduzca responsabilidades.

No dividir funciones simplemente para cumplir una cantidad arbitraria de líneas.

---

## Separar dominio de infraestructura

Mantener separadas:

* Reglas de negocio.
* Persistencia.
* HTTP/API.
* Integraciones externas (Meta, Telegram, email).
* Presentación.
* Configuración.

El dominio no debería conocer detalles innecesarios de frameworks o proveedores externos.

Ejemplo:

```text
Dominio
   ↓
Caso de uso (servicio de aplicación)
   ↓
Puerto (repositorio / ChannelAdapter)
   ↓
Adaptador / infraestructura
```

No permitir que una integración externa contamine toda la lógica de negocio: el formato del webhook de Meta muere en
su adaptador, que lo normaliza a `InboundMessage`.

---

## Dependencias explícitas

Preferir recibir dependencias explícitamente:

```java
class OutboundMessaging {
    private final ChannelAdapterRegistry adapters;
    private final MessageRepository messages;

    OutboundMessaging(ChannelAdapterRegistry adapters, MessageRepository messages) {
        this.adapters = adapters;
        this.messages = messages;
    }
}
```

sobre instanciarlas internamente:

```java
class OutboundMessaging {
    private final WhatsAppClient whatsApp = new WhatsAppClient();
}
```

Esto mejora:

* Testabilidad.
* Reutilización.
* Comprensión.
* Sustitución de implementaciones.

---

## Evitar efectos secundarios ocultos

Una función debería dejar claro si modifica estado, persiste información o ejecuta acciones externas.

Evitar funciones aparentemente inocentes que internamente:

* Escriben en la base.
* Envían mensajes.
* Modifican estado global.
* Llaman APIs externas.

El comportamiento importante debe ser visible en la interfaz del componente. Un `windowState(...)` consulta; nunca
envía nada.

---

## Inmutabilidad cuando simplifique el diseño

Preferir objetos inmutables (`record`, campos `final`) cuando no sea necesario modificar su estado.

Esto reduce:

* Estados inválidos.
* Efectos secundarios.
* Bugs difíciles de reproducir.
* Dependencias temporales.

No forzar inmutabilidad cuando complique innecesariamente el código.

---

## Fail fast

Detectar estados inválidos lo antes posible.

Preferir:

```java
var account = channelAccounts.findByExternalId(payload.accountId())
        .orElseThrow(() -> new ChannelAccountNotFoundException(payload.accountId()));
```

antes que dejar que el sistema continúe hasta producir un error mucho más difícil de entender.

Los errores deben representar problemas concretos del dominio cuando sea posible.

---

## Excepciones con significado

Evitar:

```java
catch (Exception e) {
    ...
}
```

salvo que exista una razón concreta.

Capturar excepciones específicas.

Preferir errores que expliquen qué ocurrió:

```java
PersonNotFoundException
ConversationWindowClosedException
TemplateNotApprovedException
ChannelProviderException
```

sobre errores genéricos que obliguen a interpretar el stack trace para entender el problema.

---

## Evitar métodos que hagan demasiado

Si un método necesita demasiados parámetros, probablemente está faltando un concepto.

Ejemplo problemático:

```java
createLead(name, email, phone, instagramUser, source, campaign, channel, organizationId, ...)
```

Evaluar si algunos parámetros representan un objeto del dominio:

```java
createLead(organization, contactIdentities, leadSource)
```

No crear objetos artificiales únicamente para reducir la cantidad de parámetros.
