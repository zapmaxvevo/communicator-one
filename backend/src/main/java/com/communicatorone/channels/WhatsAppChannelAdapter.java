package com.communicatorone.channels;

import com.communicatorone.messaging.ChannelType;
import com.communicatorone.messaging.MessageContent;
import com.communicatorone.messaging.MessageStatus;
import com.communicatorone.people.ContactIdentity;
import com.communicatorone.people.InvalidContactIdentityException;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
class WhatsAppChannelAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppChannelAdapter.class);

    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private static final String BUSINESS_ACCOUNT_OBJECT = "whatsapp_business_account";
    private static final String MESSAGES_FIELD = "messages";
    private static final String TEXT_TYPE = "text";
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern OLD_MEXICAN_MOBILE = Pattern.compile("521\\d{10}");
    private static final Map<String, MessageStatus> DOMAIN_STATUSES = Map.of(
            "sent", MessageStatus.SENT,
            "delivered", MessageStatus.DELIVERED,
            "read", MessageStatus.READ,
            "failed", MessageStatus.FAILED);

    @Override
    public ChannelType type() {
        return ChannelType.WHATSAPP;
    }

    @Override
    public List<InboundMessage> parseInbound(WebhookPayload payload) {
        var notifications = parsed(payload);
        if (notifications.ignoredMessages() > 0) {
            log.info("Ignored {} inbound WHATSAPP messages", notifications.ignoredMessages());
        }
        return notifications.inboundMessages();
    }

    @Override
    public List<StatusUpdate> parseStatusUpdates(WebhookPayload payload) {
        return parsed(payload).statusUpdates();
    }

    @Override
    public SendResult send(ContactIdentity to, ChannelAccount from, MessageContent content) {
        throw new UnsupportedOperationException("Sending through WhatsApp is not supported yet");
    }

    private static WhatsAppNotifications parsed(WebhookPayload payload) {
        try {
            var webhook = JSON.readValue(payload.body(), WhatsAppWebhook.class);
            Assert.notNull(webhook, "A WhatsApp webhook is a JSON object");
            return webhook.notifications();
        } catch (JacksonException | IllegalArgumentException | DateTimeException invalid) {
            throw new InvalidWebhookPayloadException(ChannelType.WHATSAPP);
        }
    }

    record WhatsAppWebhook(String object, List<WhatsAppEntry> entry) {

        WhatsAppWebhook {
            Assert.isTrue(BUSINESS_ACCOUNT_OBJECT.equals(object), "A WhatsApp webhook is of a business account");
            Assert.notNull(entry, "A WhatsApp webhook needs its entries");
            Assert.noNullElements(entry, "A WhatsApp webhook has no null entries");
        }

        WhatsAppNotifications notifications() {
            var values = entry.stream()
                    .flatMap(each -> each.changes().stream())
                    .map(WhatsAppChange::messagesValue)
                    .flatMap(Optional::stream)
                    .toList();
            var inboundMessages = values.stream().flatMap(value -> value.inboundMessages().stream()).toList();
            var statusUpdates = values.stream().flatMap(value -> value.statusUpdates().stream()).toList();
            var receivedMessages = values.stream().mapToInt(value -> value.messages().size()).sum();
            return new WhatsAppNotifications(inboundMessages, statusUpdates, receivedMessages - inboundMessages.size());
        }
    }

    record WhatsAppEntry(List<WhatsAppChange> changes) {

        WhatsAppEntry {
            Assert.notNull(changes, "A WhatsApp entry needs its changes");
            Assert.noNullElements(changes, "A WhatsApp entry has no null changes");
        }
    }

    record WhatsAppChange(String field, JsonNode value) {

        Optional<WhatsAppValue> messagesValue() {
            if (!MESSAGES_FIELD.equals(field)) {
                return Optional.empty();
            }
            Assert.notNull(value, "A WhatsApp messages change needs its value");
            var messagesValue = JSON.treeToValue(value, WhatsAppValue.class);
            Assert.notNull(messagesValue, "A WhatsApp messages change needs a value object");
            return Optional.of(messagesValue);
        }

        @Override
        public String toString() {
            return "WhatsAppChange[" + field + "]";
        }
    }

    record WhatsAppValue(WhatsAppMetadata metadata, List<WhatsAppContact> contacts, List<WhatsAppMessage> messages,
            List<WhatsAppStatus> statuses) {

        WhatsAppValue {
            Assert.notNull(metadata, "A WhatsApp messages change needs its metadata");
            contacts = Objects.requireNonNullElse(contacts, List.of());
            messages = Objects.requireNonNullElse(messages, List.of());
            statuses = Objects.requireNonNullElse(statuses, List.of());
            Assert.noNullElements(contacts, "A WhatsApp messages change has no null contacts");
            Assert.noNullElements(messages, "A WhatsApp messages change has no null messages");
            Assert.noNullElements(statuses, "A WhatsApp messages change has no null statuses");
        }

        List<InboundMessage> inboundMessages() {
            return messages.stream()
                    .map(message -> message.inboundTo(metadata.phoneNumberId(), contacts))
                    .flatMap(Optional::stream)
                    .toList();
        }

        List<StatusUpdate> statusUpdates() {
            return statuses.stream()
                    .map(status -> status.updateOf(metadata.phoneNumberId()))
                    .flatMap(Optional::stream)
                    .toList();
        }
    }

    record WhatsAppMetadata(@JsonProperty("phone_number_id") String phoneNumberId) {

        WhatsAppMetadata {
            Assert.hasText(phoneNumberId, "A WhatsApp messages change needs the phone number it was sent to");
        }
    }

    record WhatsAppContact(@JsonProperty("wa_id") String waId, WhatsAppProfile profile) {

        Optional<String> profileNameOf(String sender) {
            if (waId == null || !waId.equals(sender) || profile == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(profile.name());
        }

        @Override
        public String toString() {
            return "WhatsAppContact[profile]";
        }
    }

    record WhatsAppProfile(String name) {

        @Override
        public String toString() {
            return "WhatsAppProfile[name]";
        }
    }

    record WhatsAppMessage(String from, String id, String timestamp, String type, WhatsAppText text) {

        Optional<InboundMessage> inboundTo(String account, List<WhatsAppContact> contacts) {
            if (!TEXT_TYPE.equals(type)) {
                return Optional.empty();
            }
            return sender().map(sender -> textFrom(sender, account, profileNameIn(contacts)));
        }

        private InboundMessage textFrom(ContactIdentity sender, String account, String senderDisplayName) {
            Assert.notNull(text, "A WhatsApp text message needs its text");
            return new InboundMessage(account, sender, id, new MessageContent(text.body()),
                    Instant.ofEpochSecond(Long.parseLong(timestamp)), senderDisplayName);
        }

        private String profileNameIn(List<WhatsAppContact> contacts) {
            return contacts.stream()
                    .map(contact -> contact.profileNameOf(from))
                    .flatMap(Optional::stream)
                    .findFirst()
                    .orElse(null);
        }

        private Optional<ContactIdentity> sender() {
            if (from == null || !DIGITS.matcher(from).matches()) {
                return Optional.empty();
            }
            var phone = OLD_MEXICAN_MOBILE.matcher(from).matches() ? "+52" + from.substring(3) : "+" + from;
            try {
                return Optional.of(ContactIdentity.phone(phone));
            } catch (InvalidContactIdentityException notAPhone) {
                return Optional.empty();
            }
        }

        @Override
        public String toString() {
            return "WhatsAppMessage[" + type + "]";
        }
    }

    record WhatsAppText(String body) {

        @Override
        public String toString() {
            return "WhatsAppText[body]";
        }
    }

    record WhatsAppStatus(String id, String status) {

        WhatsAppStatus {
            Assert.notNull(status, "A WhatsApp status needs its status");
        }

        Optional<StatusUpdate> updateOf(String account) {
            return Optional.ofNullable(DOMAIN_STATUSES.get(status))
                    .map(domainStatus -> new StatusUpdate(account, id, domainStatus));
        }

        @Override
        public String toString() {
            return "WhatsAppStatus[" + status + "]";
        }
    }

    record WhatsAppNotifications(List<InboundMessage> inboundMessages, List<StatusUpdate> statusUpdates,
            int ignoredMessages) {
    }
}
