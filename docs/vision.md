# Visión: un prospecto, una comunicación

## 1. La idea

Una persona puede hablarnos por muchos canales (WhatsApp, Instagram, Facebook Messenger, Telegram, email, SMS, webchat, portales de avisos), pero para el negocio existe **una sola relación** con esa persona, y el equipo la ve y la responde desde **un único lugar**.

El canal es un detalle de transporte. Lo importante es la persona y la historia completa.

## 2. Problema que resuelve

- Los leads entran por cualquier lado: formulario, campaña, aviso, mensaje directo, llamada, importación.
- Cada canal tiene su propia app o bandeja: la información queda dispersa y nadie ve la historia completa.
- Se pierde contexto cuando la persona cambia de canal ("te escribí por Instagram ayer, ahora te hablo por WhatsApp").
- Si cambia el operador, se pierde la historia.
- No hay métricas unificadas (tiempos de respuesta, no leídos, conversión) porque los datos están en muchos lugares.

## 3. Principios

1. **La persona es el centro.** Todo cuelga de ella: identidades, mensajes, oportunidades.
2. **Muchas identidades, una persona.** Teléfono, usuario de Instagram, email o chat de Telegram apuntan a la misma persona.
3. **Una sola línea de tiempo.** Los mensajes de todos los canales se ordenan en un mismo hilo, cada uno marcado con su canal.
4. **Canales como adaptadores.** Sumar un canal es implementar una interfaz fija. El core no sabe nada de Meta, Telegram ni de ningún proveedor.
5. **Responder por donde corresponde.** Por defecto, por el último canal que usó la persona; el operador puede elegir otro, respetando las reglas de cada canal (ej. ventana de 24 h de WhatsApp).
6. **Un único lugar para el equipo.** Vista web propia (después celular, escritorio y terminal, todos sobre la misma API).
7. **Cada mensaje sabe quién lo mandó:** cliente, operador, bot/IA o sistema.

## 4. Modelo de dominio

```
Organization  (tenant: la empresa cliente)
 ├── Person                         permanente
 │    ├── ContactIdentity *         tipo de dato + valor normalizado (teléfono E.164, email, id opaco del canal)
 │    ├── Message *                 UNA línea de tiempo, para siempre
 │    │    ├── channel              WHATSAPP, INSTAGRAM, MESSENGER, TELEGRAM, EMAIL, SMS, WEBCHAT…
 │    │    ├── senderType           CUSTOMER | OPERATOR | BOT | SYSTEM
 │    │    ├── channelAccount       por qué cuenta nuestra entró o salió
 │    │    ├── externalId           id del proveedor → idempotencia y matching de estados
 │    │    ├── content              texto, adjuntos, plantilla, ubicación…
 │    │    ├── status               SENT, DELIVERED, READ, FAILED, DELETED
 │    │    └── opportunity          la oportunidad abierta en ese momento
 │    └── Opportunity *             episodio con ciclo de vida
 │         ├── owner (Operator), status, source (campaña, formulario, canal…)
 │         ├── result               WON, LOST, NO_RESPONSE
 │         └── openedAt, closedAt, lastActivityAt
 ├── ChannelAccount *               nuestro número de WhatsApp, página de FB, cuenta de IG, bot de Telegram
 │    └── credenciales, webhook, permite salientes, tiene bot
 ├── Operator *                     disponibilidad: ONLINE, AWAY, ON_LEAVE
 └── configuración                  reparto, temporizadores, horario de atención, ventanas
```

- `ContactIdentity` permite que "Juan por WhatsApp" y "Juan por Instagram" sean la misma persona. Es tipo de dato + valor normalizado (teléfono E.164, email, id opaco del canal), no canal + id externo: el mismo teléfono por WhatsApp y por SMS es la misma identidad (P-027 en `docs/decisiones.md`, detalle en `docs/personas.md`).
- `ChannelAccount` es nuestra identidad en cada canal. Un mensaje entrante se asigna a la organización según la cuenta que lo recibió.
- `externalId` es clave: los webhooks llegan duplicados y los estados (entregado, leído) llegan aparte.

## 5. Arquitectura de canales

Cada canal implementa la misma interfaz (puerto en arquitectura hexagonal). Conceptualmente:

```java
public interface ChannelAdapter {
    ChannelType type();
    List<InboundMessage> parseInbound(WebhookPayload payload);
    List<StatusUpdate> parseStatusUpdates(WebhookPayload payload);
    SendResult send(ContactIdentity to, ChannelAccount from, MessageContent content);
    ConversationWindowState windowState(Person person, ChannelAccount account); // qué se puede enviar ahora
    SendResult startConversation(ContactIdentity to, ChannelAccount from, Template template);
}
```

**Flujo entrante** (igual para todos los canales):
1. Llega el webhook; el adaptador lo normaliza a `InboundMessage`.
2. Se resuelve la `ChannelAccount` → organización.
3. Se resuelve la `ContactIdentity` → persona. Si no existe, se crea (lead nuevo).
4. Se obtiene la oportunidad abierta, se reabre o se crea una nueva (según ventana de reingreso).
5. Se guarda el `Message` (idempotente por `externalId`), se actualizan fechas y no leídos.
6. Si la oportunidad es nueva, se asigna (estrategia de reparto) y arranca el temporizador de atención.
7. Se notifica a los clientes conectados por WebSocket.

**Flujo saliente:**
1. El operador responde desde la vista unificada.
2. Se elige canal (el último usado o el que elija el operador) y cuenta.
3. El adaptador valida el estado de la ventana (texto libre, solo plantilla o bloqueado) y envía.
4. Se guarda el `Message` como `SENT`; los webhooks de estado lo actualizan después.

**Estados de ventana** (patrón probado en un proyecto anterior con WhatsApp vía Meta):
- `OPEN`: se puede mandar texto libre.
- `AWAITING_REPLY`: se mandó una plantilla y se espera respuesta; la ventana sigue cerrada.
- `REQUIRES_TEMPLATE`: ventana cerrada; solo se puede iniciar con plantilla aprobada.
- `BLOCKED`: no se puede enviar (con motivo).

Cada estado le dice a la UI qué puede hacer el operador.

Un mismo canal puede tener varios proveedores (ej. WhatsApp vía Meta Cloud API o vía un BSP). El core no se entera.

## 6. Lecciones de proyectos anteriores

- **Una línea de tiempo por persona, no una conversación por canal.** Separar por canal y agregar después complica no leídos, orden y métricas.
- **Core genérico, clientes que solo configuran.** Atar el core a un canal o a una herramienta (ej. Slack) hace que cada cliente nuevo repita lógica.
- **Índices desde el día 1** en lo que consultan los webhooks (emisor, receptor, fecha). Sin el índice de la ventana de 24 h, el webhook se colgaba.
- **Guardar y normalizar el id del proveedor.** Los formatos cambian (ej. WhatsApp pasó de teléfono a user id por portfolio).
- **Todo idempotente.** Los webhooks llegan repetidos y fuera de orden.

## 7. Alcance

**MVP:**
1. Multi-tenant con usuarios y operadores.
2. Person, ContactIdentity, Opportunity, Message, ChannelAccount.
3. WhatsApp Cloud API como primer canal real; luego Instagram/Messenger (misma plataforma Meta); luego Telegram.
4. Reparto automático y temporizador de atención.
5. Bandeja web unificada: no leídos primero, línea de tiempo con el canal de cada mensaje, responder eligiendo canal.
6. Alta de leads por API o formulario.

**Después:** email, SMS, webchat embebible, bot/IA, fusión asistida de duplicados, métricas (primera respuesta, SLA, conversión por fuente y canal), plantillas y campañas salientes, apps de celular, escritorio y terminal, servidor MCP.

## 8. Fuentes

Esta visión y `docs/decisiones.md` salen de estas fuentes. Lo necesario para trabajar ya está escrito en este repo;
las fuentes sirven para ver el razonamiento completo o cómo se resolvió algo antes.

| Fuente | Dónde | Acceso |
|---|---|---|
| Visión ilustrada (diagrama, ejemplo de línea de tiempo, análisis de reparto) | https://claude.ai/artifact/JxPq7baHAZ6Vtyu5KLXLBn | Privado, solo el dueño |
| Notas semilla originales | `~/work/notas/core-negocio-comunicacion-unificada.md` | Solo en la máquina local |
| Delivery Run (conversaciones multimedio por prospecto) | `~/work/deliveryrun/run0km/` | Solo en la máquina local |
| OpenDA (WhatsApp ↔ Slack/CRM) | `~/work/openda/app/` | Solo en la máquina local |

Un agente en la nube no puede leer ninguna: si una tarea necesita algo de ahí, se trae al repo (a este archivo o a
`docs/decisiones.md`) antes de implementar.

### Equivalencias con el código anterior

Las lecciones de la sección 6 salen de estos dos proyectos.

| Acá | Antes | Archivo | Qué cambia |
|---|---|---|---|
| `Message` en la línea de tiempo de `Person` | `Conversacion` (una por prospecto y canal) + `ConversacionMultimedia` (Run) | `run0km/conversaciones/models/conversacion.py`, `conversacion_multimedia.py` | En Run la vista unificada era un agregado encima; acá es el modelo principal. |
| `ChannelAdapter` | `MedioDeConversacion` y subclases por canal (Run) | `run0km/conversaciones/medios.py` | Mismo patrón, como puerto hexagonal. |
| Varios proveedores por canal | `GestorDeConversaciones`, `SeleccionDeCanalDeWhatsapp`, WhatsApp vía Meta o vía Cover (Run) | `run0km/conversaciones/gestor.py`, `seleccion_de_canal_de_whatsapp.py`, `meta/canal_de_comunicacion_via_meta.py`, `canal_de_comunicacion_de_whatsapp_via_cover.py` | El core no se entera del proveedor. |
| `ConversationWindowState` (D-005) | `EstadoDeConversacionDeMeta` + `Abierta`, `EsperandoRespuesta`, `RequiereApertura`, `Bloqueada` (Run) | `run0km/conversaciones/estado_de_conversacion.py` | Se generaliza: cada adaptador devuelve su estado. |
| `reentryWindow` de `Opportunity` (D-002) | `GrupoDeRepetidos` (Run) | `run0km/prospectos/models/repetidos.py` | Pasa a ser configurable por organización. |
| `ChannelAccount` | `Telefono` (OpenDA) | `openda/app/whatsapp/models.py` | Generalizado a cualquier canal. |
| Varias `ContactIdentity` por persona | `BusinessScopedUserId` (OpenDA) | `openda/app/whatsapp/models.py` | Caso real de dos identidades de WhatsApp para la misma persona. |
| `Message` (`senderType`, `externalId`, `status`) | `Mensaje` (OpenDA) | `openda/app/slack/models.py` | `origen` → `senderType`; `wa_msj_id` → `externalId` normalizado. |
| `Opportunity` + vista web | `Comunicacion` (OpenDA) | `openda/app/slack/models.py` | En OpenDA estaba atada a WhatsApp + Slack; acá el core es genérico y la vista es web propia. |

Diagrama de conversaciones de Run: `run0km/conversaciones/doc/grafico_conversacion.png`.
