package com.communicatorone.channels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.communicatorone.messaging.MessageContent;
import com.communicatorone.messaging.MessageStatus;
import com.communicatorone.people.ContactIdentity;
import java.time.Instant;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class WhatsAppChannelAdapterTest {

    private static final String ACCOUNT = "106540352242922";
    private static final String ANA_MESSAGE_ID = "wamid.HBgNNTQ5MTE1NTU1MDEwMRUCABIYFjNFQjBBMUYyQzk4RDQ3RTVCNjAxAA==";
    private static final String JUAN_MESSAGE_ID = "wamid.HBgLMzQ2MTIzNDU2NzgVAgASGBYzRUIwQjJFM0Q4N0U1OEY2QzcwMgA=";
    private static final String REPLY_TO_ANA = "wamid.HBgNNTQ5MTE1NTU1MDEwMRUCABEYEjQyQTdGOEQ5RTBGMUEyQjNDNAA=";
    private static final String REPLY_TO_JUAN = "wamid.HBgLMzQ2MTIzNDU2NzgVAgARGBI1QjhFOUYwQTFCMkMzRDRFNUYA";

    private static final InboundMessage ANA_ASKS_FOR_FINANCING = new InboundMessage(ACCOUNT,
            ContactIdentity.phone("+5491155550101"), ANA_MESSAGE_ID, new MessageContent("Hola, ¿tienen financiación?"),
            Instant.parse("2026-03-02T08:59:20Z"));

    private static final String ANA_TEXT = """
            {"from": "5491155550101", "id": "%s", "timestamp": "1772441960", "type": "text",
             "text": {"body": "Hola, ¿tienen financiación?"}}""".formatted(ANA_MESSAGE_ID);

    private static final String JUAN_TEXT = """
            {"from": "34612345678", "id": "%s", "timestamp": "1772442065", "type": "text",
             "text": {"body": "¿Hacen envíos a Madrid?"}}""".formatted(JUAN_MESSAGE_ID);

    private final WhatsAppChannelAdapter whatsApp = new WhatsAppChannelAdapter();

    @Test
    void metaTextMessageBecomesInboundMessageOfTheReceivingPhoneNumber() {
        // Given
        var payload = WhatsAppExamples.payload("text-message.json");

        // When
        var inbound = whatsApp.parseInbound(payload);
        var statuses = whatsApp.parseStatusUpdates(payload);

        // Then
        assertThat(inbound).containsExactly(ANA_ASKS_FOR_FINANCING);
        assertThat(statuses).isEmpty();
    }

    @Test
    void mexicanSenderWithTheOldMobilePrefixKeepsTheirE164PhoneWithoutTheOne() {
        // Given
        var payload = WhatsAppExamples.payload("text-message-mexico.json");

        // When
        var inbound = whatsApp.parseInbound(payload);
        var statuses = whatsApp.parseStatusUpdates(payload);

        // Then
        assertThat(inbound).containsExactly(new InboundMessage(ACCOUNT, ContactIdentity.phone("+525512345678"),
                "wamid.HBgNNTIxNTUxMjM0NTY3OBUCABIYFjNFQjBDM0Y0RTk2RjU5MDdEODEzAA==",
                new MessageContent("Buenos días, ¿hacen envíos a Monterrey?"), Instant.parse("2026-03-02T08:59:20Z")));
        assertThat(statuses).isEmpty();
    }

    @Test
    void metaStatusesBecomeStatusUpdatesWithTheDomainStatusInTheOrderTheyArrived() {
        // Given
        var payload = WhatsAppExamples.payload("statuses.json");

        // When
        var statuses = whatsApp.parseStatusUpdates(payload);
        var inbound = whatsApp.parseInbound(payload);

        // Then
        assertThat(statuses).containsExactly(
                new StatusUpdate(ACCOUNT, REPLY_TO_ANA, MessageStatus.SENT),
                new StatusUpdate(ACCOUNT, REPLY_TO_ANA, MessageStatus.READ),
                new StatusUpdate(ACCOUNT, REPLY_TO_ANA, MessageStatus.DELIVERED),
                new StatusUpdate(ACCOUNT, REPLY_TO_JUAN, MessageStatus.FAILED));
        assertThat(inbound).isEmpty();
    }

    @Test
    void webhookWithSeveralEntriesAndChangesReturnsEverythingInPayloadOrder() {
        // Given
        var payload = WhatsAppExamples.payload("multiple-entries.json");

        // When
        var inbound = whatsApp.parseInbound(payload);
        var statuses = whatsApp.parseStatusUpdates(payload);

        // Then
        assertThat(inbound).containsExactly(ANA_ASKS_FOR_FINANCING,
                new InboundMessage("108811223344556", ContactIdentity.phone("+34612345678"), JUAN_MESSAGE_ID,
                        new MessageContent("¿Hacen envíos a Madrid?"), Instant.parse("2026-03-02T09:01:05Z")));
        assertThat(statuses).containsExactly(
                new StatusUpdate("107655329956071", REPLY_TO_ANA, MessageStatus.DELIVERED),
                new StatusUpdate("107655329956071", REPLY_TO_JUAN, MessageStatus.READ));
    }

    @Test
    void unsupportedMessageTypesAreIgnoredWithoutInventingMessages() {
        // Given
        var payload = WhatsAppExamples.payload("unsupported-messages.json");

        // When
        var inbound = whatsApp.parseInbound(payload);
        var statuses = whatsApp.parseStatusUpdates(payload);

        // Then
        assertThat(inbound).containsExactly(ANA_ASKS_FOR_FINANCING);
        assertThat(statuses).isEmpty();
    }

    @Test
    void messagesOfUnsupportedOrMissingTypeAreIgnoredWithoutCheckingTheirFields() {
        // Given
        var payload = new WebhookPayload(withMessage(String.join(", ", ANA_TEXT, """
                {"type": "image", "image": {"mime_type": "image/jpeg", "id": "1003383421387256"}}""", """
                {"from": "5491155550101", "id": "wamid.NOTYPE-0001", "timestamp": "1772442010",
                 "text": {"body": "¿Siguen ahí?"}}""", JUAN_TEXT)));

        // When
        var inbound = whatsApp.parseInbound(payload);
        var statuses = whatsApp.parseStatusUpdates(payload);

        // Then
        assertThat(inbound).containsExactly(ANA_ASKS_FOR_FINANCING,
                new InboundMessage(ACCOUNT, ContactIdentity.phone("+34612345678"), JUAN_MESSAGE_ID,
                        new MessageContent("¿Hacen envíos a Madrid?"), Instant.parse("2026-03-02T09:01:05Z")));
        assertThat(statuses).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("messagesFromSendersWithoutPhone")
    void messageFromSenderWithoutPhoneIsIgnoredAndTheRestOfTheWebhookIsKept(String messageWithoutPhone) {
        // Given
        var payload = new WebhookPayload(webhook(String.join(", ", ANA_TEXT, messageWithoutPhone, JUAN_TEXT), """
                {"id": "%s", "status": "delivered", "timestamp": "1772442010", "recipient_id": "5491155550101"}"""
                .formatted(REPLY_TO_ANA)));

        // When
        var inbound = whatsApp.parseInbound(payload);
        var statuses = whatsApp.parseStatusUpdates(payload);

        // Then
        assertThat(inbound).containsExactly(ANA_ASKS_FOR_FINANCING,
                new InboundMessage(ACCOUNT, ContactIdentity.phone("+34612345678"), JUAN_MESSAGE_ID,
                        new MessageContent("¿Hacen envíos a Madrid?"), Instant.parse("2026-03-02T09:01:05Z")));
        assertThat(statuses).containsExactly(new StatusUpdate(ACCOUNT, REPLY_TO_ANA, MessageStatus.DELIVERED));
    }

    @Test
    void metaStatusesWithoutDomainEquivalentAreIgnored() {
        // Given
        var payload = new WebhookPayload(webhook("", """
                {"id": "%1$s", "status": "deleted", "timestamp": "1772441960", "recipient_id": "5491155550101"},
                {"status": "deleted", "timestamp": "1772441990", "recipient_id": "5491155550101"},
                {"id": "%1$s", "status": "delivered", "timestamp": "1772442010", "recipient_id": "5491155550101"},
                {"id": "%2$s", "status": "warning", "timestamp": "1772442010", "recipient_id": "34612345678"},
                {"id": "%1$s", "status": "READ", "timestamp": "1772442065", "recipient_id": "5491155550101"},
                {"id": "%1$s", "status": "seen", "timestamp": "1772442065", "recipient_id": "5491155550101"}"""
                .formatted(REPLY_TO_ANA, REPLY_TO_JUAN)));

        // When
        var statuses = whatsApp.parseStatusUpdates(payload);
        var inbound = whatsApp.parseInbound(payload);

        // Then
        assertThat(statuses).containsExactly(new StatusUpdate(ACCOUNT, REPLY_TO_ANA, MessageStatus.DELIVERED));
        assertThat(inbound).isEmpty();
    }

    @Test
    void changesOfOtherWebhookFieldsAreIgnored() {
        // Given
        var payload = WhatsAppExamples.payload("template-status-update.json");

        // When
        var inbound = whatsApp.parseInbound(payload);
        var statuses = whatsApp.parseStatusUpdates(payload);

        // Then
        assertThat(inbound).isEmpty();
        assertThat(statuses).isEmpty();
    }

    @Test
    void changesWithoutFieldAreIgnoredWhateverTheirValue() {
        // Given
        var payload = new WebhookPayload("""
                {"object": "whatsapp_business_account",
                 "entry": [{"id": "102290129340398", "changes": [
                   {"value": {"messaging_product": "whatsapp", "messages": [%1$s]}},
                   {"value": {"metadata": "106540352242922", "messages": {"from": "5491155550101"}}},
                   {"value": null},
                   {"field": "messages", "value": {"messaging_product": "whatsapp",
                     "metadata": {"display_phone_number": "15550783881", "phone_number_id": "106540352242922"},
                     "messages": [%1$s]}}
                 ]}]}""".formatted(ANA_TEXT));

        // When
        var inbound = whatsApp.parseInbound(payload);
        var statuses = whatsApp.parseStatusUpdates(payload);

        // Then
        assertThat(inbound).containsExactly(ANA_ASKS_FOR_FINANCING);
        assertThat(statuses).isEmpty();
    }

    @Test
    void messagesChangeWithoutMessagesOrStatusesHasNothingToReport() {
        // Given
        var payload = WhatsAppExamples.payload("errors-only.json");

        // When
        var inbound = whatsApp.parseInbound(payload);
        var statuses = whatsApp.parseStatusUpdates(payload);

        // Then
        assertThat(inbound).isEmpty();
        assertThat(statuses).isEmpty();
    }

    @Test
    void fieldsMetaAddsLaterDoNotBreakParsing() {
        // Given
        var payload = new WebhookPayload("""
                {
                  "object": "whatsapp_business_account",
                  "api_version": "v99.0",
                  "entry": [{
                    "id": "102290129340398",
                    "time": 1772441960,
                    "region": "LATAM",
                    "changes": [{
                      "field": "messages",
                      "event_id": "evt-0001",
                      "value": {
                        "messaging_product": "whatsapp",
                        "business_region": "AR",
                        "metadata": {"display_phone_number": "15550783881", "phone_number_id": "106540352242922",
                                     "verified_name": "Acme Motos"},
                        "messages": [{"from": "5491155550101", "id": "%s", "timestamp": "1772441960",
                                      "type": "text", "priority": "high",
                                      "text": {"body": "Hola, ¿tienen financiación?", "language": "es"}}],
                        "statuses": [{"id": "%s", "status": "delivered", "timestamp": "1772442010",
                                      "recipient_id": "5491155550101", "channel_hint": "mobile"}]
                      }
                    }]
                  }]
                }
                """.formatted(ANA_MESSAGE_ID, REPLY_TO_ANA));

        // When
        var inbound = whatsApp.parseInbound(payload);
        var statuses = whatsApp.parseStatusUpdates(payload);

        // Then
        assertThat(inbound).containsExactly(ANA_ASKS_FOR_FINANCING);
        assertThat(statuses).containsExactly(new StatusUpdate(ACCOUNT, REPLY_TO_ANA, MessageStatus.DELIVERED));
    }

    @ParameterizedTest(name = "{0} ({2})")
    @MethodSource("malformedPayloadsInBothParsings")
    void malformedMetaPayloadIsRejected(String malformation, String body, Parsing parsing) {
        // Given
        var payload = new WebhookPayload(body);

        // When
        var rejection = catchThrowable(() -> parsing.apply(whatsApp, payload));

        // Then
        assertThat(rejection).isInstanceOf(InvalidWebhookPayloadException.class)
                .hasMessage("Invalid WHATSAPP webhook payload")
                .hasNoCause();
    }

    static Stream<Named<String>> messagesFromSendersWithoutPhone() {
        return Stream.of(
                Named.of("BSUID sender", """
                        {"from": "US.13491208655302741918", "id": "wamid.BSUID-0001", "timestamp": "1772442010",
                         "type": "text", "text": {"body": "Hola"}}"""),
                Named.of("sender that is not a possible phone", """
                        {"from": "12", "id": "wamid.SHORT-0001", "timestamp": "1772442010",
                         "type": "text", "text": {"body": "Hola"}}"""),
                Named.of("blank sender", """
                        {"from": "  ", "id": "wamid.BLANK-0001", "timestamp": "1772442010",
                         "type": "text", "text": {"body": "Hola"}}"""),
                Named.of("without sender", """
                        {"id": "wamid.NOFROM-0001", "timestamp": "1772442010",
                         "type": "text", "text": {"body": "Hola"}}"""),
                Named.of("BSUID sender of a text without timestamp nor body", """
                        {"from": "US.13491208655302741918", "id": "wamid.BSUID-0002", "type": "text"}"""));
    }

    static Stream<Arguments> malformedPayloadsInBothParsings() {
        var parsings = List.of(
                Named.<Parsing>of("inbound messages", WhatsAppChannelAdapter::parseInbound),
                Named.<Parsing>of("status updates", WhatsAppChannelAdapter::parseStatusUpdates));
        return malformedPayloads().flatMap(malformed -> parsings.stream()
                .map(parsing -> Arguments.of(malformed.malformation(), malformed.body(), parsing)));
    }

    private static Stream<MalformedPayload> malformedPayloads() {
        return Stream.of(
                new MalformedPayload("not JSON", "esto no es JSON"),
                new MalformedPayload("empty body", ""),
                new MalformedPayload("JSON null", "null"),
                new MalformedPayload("JSON array at the root", "[]"),
                new MalformedPayload("without object", """
                        {"entry": [{"id": "102290129340398", "changes": []}]}"""),
                new MalformedPayload("object of another product", """
                        {"object": "page", "entry": [{"id": "102290129340398", "changes": []}]}"""),
                new MalformedPayload("without entry", """
                        {"object": "whatsapp_business_account"}"""),
                new MalformedPayload("entry that is not a list", """
                        {"object": "whatsapp_business_account", "entry": {}}"""),
                new MalformedPayload("null entry", """
                        {"object": "whatsapp_business_account", "entry": [null]}"""),
                new MalformedPayload("entry without changes", """
                        {"object": "whatsapp_business_account", "entry": [{"id": "102290129340398"}]}"""),
                new MalformedPayload("null change", """
                        {"object": "whatsapp_business_account", "entry": [{"id": "102290129340398",
                         "changes": [null]}]}"""),
                new MalformedPayload("messages change without value", """
                        {"object": "whatsapp_business_account", "entry": [{"id": "102290129340398",
                         "changes": [{"field": "messages"}]}]}"""),
                new MalformedPayload("messages change with null value", webhookWithValue("null")),
                new MalformedPayload("without metadata", webhookWithValue("""
                        {"messaging_product": "whatsapp", "messages": [%s]}""".formatted(ANA_TEXT))),
                new MalformedPayload("without phone number id", webhookWithValue("""
                        {"messaging_product": "whatsapp", "metadata": {"display_phone_number": "15550783881"},
                         "messages": [%s]}""".formatted(ANA_TEXT))),
                new MalformedPayload("blank phone number id", webhookWithValue("""
                        {"messaging_product": "whatsapp",
                         "metadata": {"display_phone_number": "15550783881", "phone_number_id": "  "},
                         "messages": [%s]}""".formatted(ANA_TEXT))),
                new MalformedPayload("NUL character in the phone number id", webhookWithValue("""
                        {"messaging_product": "whatsapp",
                         "metadata": {"display_phone_number": "15550783881",
                                      "phone_number_id": "1065403\\u000052242922"},
                         "messages": [%s]}""".formatted(ANA_TEXT))),
                new MalformedPayload("messages that are not a list", webhookWithValue("""
                        {"messaging_product": "whatsapp",
                         "metadata": {"display_phone_number": "15550783881", "phone_number_id": "106540352242922"},
                         "messages": {}}""")),
                new MalformedPayload("statuses that are not a list", webhookWithValue("""
                        {"messaging_product": "whatsapp",
                         "metadata": {"display_phone_number": "15550783881", "phone_number_id": "106540352242922"},
                         "statuses": {}}""")),
                new MalformedPayload("null message", withMessage("null")),
                new MalformedPayload("text without id", withMessage("""
                        {"from": "5491155550101", "timestamp": "1772441960", "type": "text",
                         "text": {"body": "Hola"}}""")),
                new MalformedPayload("NUL character in a message id", withMessage("""
                        {"from": "5491155550101", "id": "wamid.HBgN\\u0000NTQ5", "timestamp": "1772441960",
                         "type": "text", "text": {"body": "Hola"}}""")),
                new MalformedPayload("text without timestamp", withMessage("""
                        {"from": "5491155550101", "id": "%s", "type": "text",
                         "text": {"body": "Hola"}}""".formatted(ANA_MESSAGE_ID))),
                new MalformedPayload("non-numeric timestamp", withMessage("""
                        {"from": "5491155550101", "id": "%s", "timestamp": "ayer", "type": "text",
                         "text": {"body": "Hola"}}""".formatted(ANA_MESSAGE_ID))),
                new MalformedPayload("timestamp out of the time range", withMessage("""
                        {"from": "5491155550101", "id": "%s", "timestamp": "99999999999999999", "type": "text",
                         "text": {"body": "Hola"}}""".formatted(ANA_MESSAGE_ID))),
                new MalformedPayload("text message without text", withMessage("""
                        {"from": "5491155550101", "id": "%s", "timestamp": "1772441960",
                         "type": "text"}""".formatted(ANA_MESSAGE_ID))),
                new MalformedPayload("text without body", withMessage("""
                        {"from": "5491155550101", "id": "%s", "timestamp": "1772441960", "type": "text",
                         "text": {}}""".formatted(ANA_MESSAGE_ID))),
                new MalformedPayload("empty text body", withMessage("""
                        {"from": "5491155550101", "id": "%s", "timestamp": "1772441960", "type": "text",
                         "text": {"body": ""}}""".formatted(ANA_MESSAGE_ID))),
                new MalformedPayload("NUL character in a body", withMessage("""
                        {"from": "5491155550101", "id": "%s", "timestamp": "1772441960", "type": "text",
                         "text": {"body": "Ho\\u0000la"}}""".formatted(ANA_MESSAGE_ID))),
                new MalformedPayload("null status", withStatus("null")),
                new MalformedPayload("delivered status without id", withStatus("""
                        {"status": "delivered", "timestamp": "1772442010", "recipient_id": "5491155550101"}""")),
                new MalformedPayload("NUL character in a status id", withStatus("""
                        {"id": "wamid.HBgN\\u0000NTQ5", "status": "read", "timestamp": "1772442065",
                         "recipient_id": "5491155550101"}""")),
                new MalformedPayload("status without status", withStatus("""
                        {"id": "%s", "timestamp": "1772442010", "recipient_id": "5491155550101"}"""
                        .formatted(REPLY_TO_ANA))));
    }

    private static String withMessage(String message) {
        return webhook(message, "");
    }

    private static String withStatus(String status) {
        return webhook("", status);
    }

    private static String webhook(String messages, String statuses) {
        return webhookWithValue("""
                {"messaging_product": "whatsapp",
                 "metadata": {"display_phone_number": "15550783881", "phone_number_id": "106540352242922"},
                 "messages": [%s], "statuses": [%s]}""".formatted(messages, statuses));
    }

    private static String webhookWithValue(String value) {
        return """
                {"object": "whatsapp_business_account",
                 "entry": [{"id": "102290129340398", "changes": [{"field": "messages", "value": %s}]}]}"""
                .formatted(value);
    }

    record MalformedPayload(String malformation, String body) {
    }

    interface Parsing extends BiFunction<WhatsAppChannelAdapter, WebhookPayload, List<?>> {
    }
}
