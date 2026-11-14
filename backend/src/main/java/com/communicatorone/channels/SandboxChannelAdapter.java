package com.communicatorone.channels;

import com.communicatorone.messaging.ChannelType;
import com.communicatorone.messaging.MessageContent;
import com.communicatorone.messaging.MessageStatus;
import com.communicatorone.people.ContactIdentity;
import com.communicatorone.people.IdentityKind;
import com.communicatorone.people.InvalidContactIdentityException;
import java.time.Instant;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
class SandboxChannelAdapter implements ChannelAdapter {

    private static final JsonMapper JSON = JsonMapper.shared();

    private final Queue<SandboxDelivery> deliveries = new ConcurrentLinkedQueue<>();

    @Override
    public ChannelType type() {
        return ChannelType.SANDBOX;
    }

    @Override
    public List<InboundMessage> parseInbound(WebhookPayload payload) {
        return parsed(payload).inboundMessages();
    }

    @Override
    public List<StatusUpdate> parseStatusUpdates(WebhookPayload payload) {
        return parsed(payload).statusUpdates();
    }

    @Override
    public SendResult send(ContactIdentity to, ChannelAccount from, MessageContent content) {
        deliveries.add(new SandboxDelivery(to, content));
        return new SendResult("sandbox-" + UUID.randomUUID());
    }

    boolean hasSentTextTo(ContactIdentity to, String text) {
        return deliveries.contains(new SandboxDelivery(to, new MessageContent(text)));
    }

    private static SandboxNotifications parsed(WebhookPayload payload) {
        try {
            var webhook = JSON.readValue(payload.body(), SandboxWebhook.class);
            Assert.notNull(webhook, "A sandbox webhook is a JSON object");
            return new SandboxNotifications(webhook.inboundMessages(), webhook.statusUpdates());
        } catch (JacksonException | IllegalArgumentException | InvalidContactIdentityException invalid) {
            throw new InvalidWebhookPayloadException(ChannelType.SANDBOX);
        }
    }

    record SandboxWebhook(String account, List<SandboxMessage> messages, List<SandboxStatus> statuses) {

        SandboxWebhook {
            Assert.hasText(account, "A sandbox webhook needs its account");
            messages = messages == null ? List.of() : messages;
            statuses = statuses == null ? List.of() : statuses;
            Assert.noNullElements(messages, "A sandbox webhook has no null messages");
            Assert.noNullElements(statuses, "A sandbox webhook has no null statuses");
        }

        List<InboundMessage> inboundMessages() {
            return messages.stream().map(message -> message.inboundTo(account)).toList();
        }

        List<StatusUpdate> statusUpdates() {
            return statuses.stream().map(status -> status.updateOf(account)).toList();
        }
    }

    record SandboxMessage(String id, SandboxSender from, String text, Instant sentAt) {

        SandboxMessage {
            Assert.notNull(from, "A sandbox message needs its sender");
        }

        InboundMessage inboundTo(String account) {
            return new InboundMessage(account, from.identity(), id, new MessageContent(text), sentAt);
        }

        @Override
        public String toString() {
            return "SandboxMessage[" + id + "]";
        }
    }

    record SandboxSender(IdentityKind kind, String value) {

        SandboxSender {
            Assert.notNull(kind, "A sandbox sender needs its identity kind");
        }

        ContactIdentity identity() {
            return new ContactIdentity(kind, value);
        }

        @Override
        public String toString() {
            return "SandboxSender[" + kind + "]";
        }
    }

    record SandboxStatus(String messageId, MessageStatus status) {

        StatusUpdate updateOf(String account) {
            return new StatusUpdate(account, messageId, status);
        }
    }

    record SandboxNotifications(List<InboundMessage> inboundMessages, List<StatusUpdate> statusUpdates) {
    }

    record SandboxDelivery(ContactIdentity to, MessageContent content) {
    }
}
