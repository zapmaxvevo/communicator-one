package com.communicatorone.channels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.communicatorone.messaging.ChannelType;
import com.communicatorone.messaging.MessageContent;
import com.communicatorone.messaging.MessageStatus;
import com.communicatorone.people.ContactIdentity;
import com.communicatorone.people.IdentityKind;
import java.time.Instant;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SandboxChannelAdapterTest {

    private static final String MESSAGES_AND_STATUSES = """
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
            """;

    private static final String VALID_MESSAGE = """
            {"id": "in-1", "from": {"kind": "PHONE", "value": "+5491155550101"},
             "text": "Hola", "sentAt": "2026-03-02T08:59:20Z"}""";

    private final SandboxChannelAdapter sandbox = new SandboxChannelAdapter();
    private final ChannelAccount sandboxAccount = ChannelAccount.register(ChannelType.SANDBOX, "sandbox-acme",
            "Pruebas", true);

    @Test
    void sandboxPayloadBecomesInboundMessagesOfItsAccount() {
        // Given
        var payload = new WebhookPayload(MESSAGES_AND_STATUSES);

        // When
        var inbound = sandbox.parseInbound(payload);

        // Then
        assertThat(inbound).containsExactly(
                new InboundMessage("sandbox-acme", ContactIdentity.phone("+5491155550101"), "in-1",
                        new MessageContent("Hola, ¿tienen financiación?"), Instant.parse("2026-03-02T08:59:20Z")),
                new InboundMessage("sandbox-acme", new ContactIdentity(IdentityKind.TELEGRAM_USER, "juan_perez"),
                        "in-2", new MessageContent("¿Hacen envíos al interior?"),
                        Instant.parse("2026-03-02T09:01:05Z")));
    }

    @Test
    void reparsingTheSamePayloadGivesTheSameMessages() {
        // Given
        var payload = new WebhookPayload(MESSAGES_AND_STATUSES);
        var firstDelivery = sandbox.parseInbound(payload);

        // When
        var redelivery = sandbox.parseInbound(payload);

        // Then
        assertThat(redelivery).isEqualTo(firstDelivery)
                .extracting(InboundMessage::externalId).containsExactly("in-1", "in-2");
    }

    @Test
    void sandboxPayloadBecomesStatusUpdatesOfItsAccountInTheOrderTheyArrived() {
        // Given
        var payload = new WebhookPayload(MESSAGES_AND_STATUSES);

        // When
        var statuses = sandbox.parseStatusUpdates(payload);

        // Then
        assertThat(statuses).containsExactly(
                new StatusUpdate("sandbox-acme", "sandbox-out-1", MessageStatus.READ),
                new StatusUpdate("sandbox-acme", "sandbox-out-1", MessageStatus.DELIVERED),
                new StatusUpdate("sandbox-acme", "sandbox-out-2", MessageStatus.FAILED));
    }

    @Test
    void payloadWithOnlyStatusesHasNoInboundMessages() {
        // Given
        var payload = new WebhookPayload("""
                {"account": "sandbox-acme", "statuses": [{"messageId": "sandbox-out-1", "status": "DELIVERED"}]}
                """);

        // When
        var inbound = sandbox.parseInbound(payload);
        var statuses = sandbox.parseStatusUpdates(payload);

        // Then
        assertThat(inbound).isEmpty();
        assertThat(statuses).containsExactly(
                new StatusUpdate("sandbox-acme", "sandbox-out-1", MessageStatus.DELIVERED));
    }

    @Test
    void payloadWithOnlyMessagesHasNoStatusUpdates() {
        // Given
        var payload = new WebhookPayload("""
                {"account": "sandbox-acme", "messages": [%s]}
                """.formatted(VALID_MESSAGE));

        // When
        var statuses = sandbox.parseStatusUpdates(payload);
        var inbound = sandbox.parseInbound(payload);

        // Then
        assertThat(statuses).isEmpty();
        assertThat(inbound).containsExactly(new InboundMessage("sandbox-acme",
                ContactIdentity.phone("+5491155550101"), "in-1", new MessageContent("Hola"),
                Instant.parse("2026-03-02T08:59:20Z")));
    }

    @ParameterizedTest(name = "{0} ({2})")
    @MethodSource("malformedPayloadsInBothParsings")
    void malformedSandboxPayloadIsRejected(String malformation, String body, Parsing parsing) {
        // Given
        var payload = new WebhookPayload(body);

        // When
        var rejection = catchThrowable(() -> parsing.apply(sandbox, payload));

        // Then
        assertThat(rejection).isInstanceOf(InvalidWebhookPayloadException.class)
                .hasMessage("Invalid SANDBOX webhook payload")
                .hasNoCause();
    }

    @Test
    void sendingRecordsTheTextForItsRecipient() {
        // Given
        var recipient = ContactIdentity.phone("+5491155550101");
        var someoneElse = ContactIdentity.phone("+5491155550202");

        // When
        var result = sandbox.send(recipient, sandboxAccount, new MessageContent("Sí, hasta 12 cuotas"));

        // Then
        assertThat(result.externalId()).startsWith("sandbox-");
        assertThat(sandbox.hasSentTextTo(recipient, "Sí, hasta 12 cuotas")).isTrue();
        assertThat(sandbox.hasSentTextTo(recipient, "Hasta 6 cuotas")).isFalse();
        assertThat(sandbox.hasSentTextTo(someoneElse, "Sí, hasta 12 cuotas")).isFalse();
    }

    @Test
    void eachSendGetsItsOwnExternalId() {
        // Given
        var recipient = ContactIdentity.email("ana.perez@acme.test");
        var content = new MessageContent("Te paso el presupuesto");

        // When
        var first = sandbox.send(recipient, sandboxAccount, content);
        var second = sandbox.send(recipient, sandboxAccount, content);

        // Then
        assertThat(first.externalId()).isNotEqualTo(second.externalId());
    }

    static Stream<Arguments> malformedPayloadsInBothParsings() {
        var parsings = List.of(
                Named.<Parsing>of("inbound messages", SandboxChannelAdapter::parseInbound),
                Named.<Parsing>of("status updates", SandboxChannelAdapter::parseStatusUpdates));
        return malformedPayloads().flatMap(malformed -> parsings.stream()
                .map(parsing -> Arguments.of(malformed.malformation(), malformed.body(), parsing)));
    }

    private static Stream<MalformedPayload> malformedPayloads() {
        return Stream.of(
                new MalformedPayload("not JSON", "esto no es JSON"),
                new MalformedPayload("empty body", ""),
                new MalformedPayload("JSON null", "null"),
                new MalformedPayload("without account", """
                        {"messages": [%s]}""".formatted(VALID_MESSAGE)),
                new MalformedPayload("blank account", """
                        {"account": "  ", "messages": [%s]}""".formatted(VALID_MESSAGE)),
                new MalformedPayload("misspelled messages key", """
                        {"account": "sandbox-acme", "mesages": [%s]}""".formatted(VALID_MESSAGE)),
                new MalformedPayload("message with an unknown field", withMessage("""
                        {"id": "in-1", "from": {"kind": "PHONE", "value": "+5491155550101"},
                         "text": "Hola", "sentAt": "2026-03-02T08:59:20Z", "channel": "WHATSAPP"}""")),
                new MalformedPayload("numeric sending time", withMessage("""
                        {"id": "in-1", "from": {"kind": "PHONE", "value": "+5491155550101"},
                         "text": "Hola", "sentAt": 1700000000}""")),
                new MalformedPayload("message without id", withMessage("""
                        {"from": {"kind": "PHONE", "value": "+5491155550101"},
                         "text": "Hola", "sentAt": "2026-03-02T08:59:20Z"}""")),
                new MalformedPayload("message without sender", withMessage("""
                        {"id": "in-1", "text": "Hola", "sentAt": "2026-03-02T08:59:20Z"}""")),
                new MalformedPayload("sender without value", withMessage("""
                        {"id": "in-1", "from": {"kind": "PHONE"},
                         "text": "Hola", "sentAt": "2026-03-02T08:59:20Z"}""")),
                new MalformedPayload("message without text", withMessage("""
                        {"id": "in-1", "from": {"kind": "PHONE", "value": "+5491155550101"},
                         "sentAt": "2026-03-02T08:59:20Z"}""")),
                new MalformedPayload("message without sending time", withMessage("""
                        {"id": "in-1", "from": {"kind": "PHONE", "value": "+5491155550101"}, "text": "Hola"}""")),
                new MalformedPayload("message with empty text", withMessage("""
                        {"id": "in-1", "from": {"kind": "PHONE", "value": "+5491155550101"},
                         "text": "", "sentAt": "2026-03-02T08:59:20Z"}""")),
                new MalformedPayload("unknown identity kind", withMessage("""
                        {"id": "in-1", "from": {"kind": "FAX", "value": "+5491155550101"},
                         "text": "Hola", "sentAt": "2026-03-02T08:59:20Z"}""")),
                new MalformedPayload("numeric identity kind", withMessage("""
                        {"id": "in-1", "from": {"kind": 0, "value": "+5491155550101"},
                         "text": "Hola", "sentAt": "2026-03-02T08:59:20Z"}""")),
                new MalformedPayload("invalid phone", withMessage("""
                        {"id": "in-1", "from": {"kind": "PHONE", "value": "+54 12"},
                         "text": "Hola", "sentAt": "2026-03-02T08:59:20Z"}""")),
                new MalformedPayload("invalid email", withMessage("""
                        {"id": "in-1", "from": {"kind": "EMAIL", "value": "ana.perez"},
                         "text": "Hola", "sentAt": "2026-03-02T08:59:20Z"}""")),
                new MalformedPayload("malformed sending time", withMessage("""
                        {"id": "in-1", "from": {"kind": "PHONE", "value": "+5491155550101"},
                         "text": "Hola", "sentAt": "ayer a la tarde"}""")),
                new MalformedPayload("null message", """
                        {"account": "sandbox-acme", "messages": [null]}"""),
                new MalformedPayload("status without message id", withStatus("""
                        {"status": "READ"}""")),
                new MalformedPayload("status without status", withStatus("""
                        {"messageId": "sandbox-out-1"}""")),
                new MalformedPayload("unknown status", withStatus("""
                        {"messageId": "sandbox-out-1", "status": "SEEN"}""")),
                new MalformedPayload("numeric status", withStatus("""
                        {"messageId": "sandbox-out-1", "status": 1}""")),
                new MalformedPayload("status as a numeric string", withStatus("""
                        {"messageId": "sandbox-out-1", "status": "1"}""")),
                new MalformedPayload("null status", """
                        {"account": "sandbox-acme", "statuses": [null]}"""));
    }

    private static String withMessage(String message) {
        return """
                {"account": "sandbox-acme", "messages": [%s]}""".formatted(message);
    }

    private static String withStatus(String status) {
        return """
                {"account": "sandbox-acme", "statuses": [%s]}""".formatted(status);
    }

    record MalformedPayload(String malformation, String body) {
    }

    interface Parsing extends BiFunction<SandboxChannelAdapter, WebhookPayload, List<?>> {
    }
}
