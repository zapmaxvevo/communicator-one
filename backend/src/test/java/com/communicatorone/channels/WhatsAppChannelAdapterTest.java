package com.communicatorone.channels;

import static com.communicatorone.channels.WhatsAppExamples.PHONE_NUMBER_ID;
import static com.communicatorone.channels.WhatsAppExamples.TEXT_MESSAGE_ID;
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

    private static final String JUAN_MESSAGE_ID = "wamid.HBgLMzQ2MTIzNDU2NzgVAgASGBYzRUIwQjJFM0Q4N0U1OEY2QzcwMgA=";
    private static final String REPLY_TO_ANA = "wamid.HBgNNTQ5MTE1NTU1MDEwMRUCABEYEjQyQTdGOEQ5RTBGMUEyQjNDNAA=";
    private static final String REPLY_TO_JUAN = "wamid.HBgLMzQ2MTIzNDU2NzgVAgARGBI1QjhFOUYwQTFCMkMzRDRFNUYA";

    private static final String METADATA = """
            "metadata": {"display_phone_number": "15550783881", "phone_number_id": "%s"}"""
            .formatted(PHONE_NUMBER_ID);

    private static final InboundMessage ANA_ASKS_FOR_FINANCING = anaAsksForFinancing("Ana Pérez");
    private static final InboundMessage UNNAMED_ANA_ASKS_FOR_FINANCING = anaAsksForFinancing(null);
    private static final InboundMessage UNNAMED_JUAN_ASKS_ABOUT_SHIPPING = juanAsksAboutShipping(null);

    private static final String ANA_TEXT = """
            {"from": "5491155550101", "id": "%s", "timestamp": "1772441960", "type": "text",
             "text": {"body": "Hola, ¿tienen financiación?"}}""".formatted(TEXT_MESSAGE_ID);

    private static final String JUAN_TEXT = """
            {"from": "34612345678", "id": "%s", "timestamp": "1772442065", "type": "text",
             "text": {"body": "¿Hacen envíos a Madrid?"}}""".formatted(JUAN_MESSAGE_ID);

    private static final String ANA_CONTACT = """
            {"profile": {"name": "Ana Pérez"}, "wa_id": "5491155550101"}""";

    private static final String JUAN_CONTACT = """
            {"profile": {"name": "Juan García"}, "wa_id": "34612345678"}""";

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
        assertThat(inbound).containsExactly(new InboundMessage(PHONE_NUMBER_ID, ContactIdentity.phone("+525512345678"),
                "wamid.HBgNNTIxNTUxMjM0NTY3OBUCABIYFjNFQjBDM0Y0RTk2RjU5MDdEODEzAA==",
                new MessageContent("Buenos días, ¿hacen envíos a Monterrey?"), Instant.parse("2026-03-02T08:59:20Z"),
                "Lucía Hernández"));
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
                new StatusUpdate(PHONE_NUMBER_ID, REPLY_TO_ANA, MessageStatus.SENT),
                new StatusUpdate(PHONE_NUMBER_ID, REPLY_TO_ANA, MessageStatus.READ),
                new StatusUpdate(PHONE_NUMBER_ID, REPLY_TO_ANA, MessageStatus.DELIVERED),
                new StatusUpdate(PHONE_NUMBER_ID, REPLY_TO_JUAN, MessageStatus.FAILED));
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
                        new MessageContent("¿Hacen envíos a Madrid?"), Instant.parse("2026-03-02T09:01:05Z"),
                        "Juan García"));
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
        assertThat(inbound).containsExactly(UNNAMED_ANA_ASKS_FOR_FINANCING, UNNAMED_JUAN_ASKS_ABOUT_SHIPPING);
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
        assertThat(inbound).containsExactly(UNNAMED_ANA_ASKS_FOR_FINANCING, UNNAMED_JUAN_ASKS_ABOUT_SHIPPING);
        assertThat(statuses).containsExactly(new StatusUpdate(PHONE_NUMBER_ID, REPLY_TO_ANA, MessageStatus.DELIVERED));
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
        assertThat(statuses).containsExactly(new StatusUpdate(PHONE_NUMBER_ID, REPLY_TO_ANA, MessageStatus.DELIVERED));
        assertThat(inbound).isEmpty();
    }

    @Test
    void eachSenderGetsTheProfileNameOfTheContactWithHerWaId() {
        // Given
        var payload = new WebhookPayload(withContacts(
                "[%s, %s]".formatted(JUAN_CONTACT, ANA_CONTACT), String.join(", ", ANA_TEXT, JUAN_TEXT)));

        // When
        var inbound = whatsApp.parseInbound(payload);
        var statuses = whatsApp.parseStatusUpdates(payload);

        // Then
        assertThat(inbound).containsExactly(ANA_ASKS_FOR_FINANCING, juanAsksAboutShipping("Juan García"));
        assertThat(statuses).isEmpty();
    }

    @Test
    void firstOfTwoContactsWithHerWaIdNamesTheSender() {
        // Given
        var payload = new WebhookPayload(withContacts("""
                [{"profile": {"name": "Ana P."}, "wa_id": "5491155550101"},
                 {"profile": {"name": "Ana Pérez"}, "wa_id": "5491155550101"}]""", ANA_TEXT));

        // When
        var inbound = whatsApp.parseInbound(payload);
        var statuses = whatsApp.parseStatusUpdates(payload);

        // Then
        assertThat(inbound).containsExactly(anaAsksForFinancing("Ana P."));
        assertThat(statuses).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("webhooksWithoutTheSenderProfileName")
    void messageWithoutMatchingContactHasNoSenderName(String body) {
        // Given
        var payload = new WebhookPayload(body);

        // When
        var inbound = whatsApp.parseInbound(payload);
        var statuses = whatsApp.parseStatusUpdates(payload);

        // Then
        assertThat(inbound).containsExactly(UNNAMED_ANA_ASKS_FOR_FINANCING);
        assertThat(statuses).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("contactsWithUnusualProfileNames")
    void profileNameIsPassedAsItComesWithoutRejectingTheWebhook(String contact, String senderName) {
        // Given
        var payload = new WebhookPayload(withContacts("[%s]".formatted(contact), ANA_TEXT));

        // When
        var inbound = whatsApp.parseInbound(payload);
        var statuses = whatsApp.parseStatusUpdates(payload);

        // Then
        assertThat(inbound).containsExactly(anaAsksForFinancing(senderName));
        assertThat(statuses).isEmpty();
    }

    @Test
    void inboundMessageDoesNotRevealTheSenderNorWhatSheWroteWhenPrinted() {
        // Given
        var payload = WhatsAppExamples.payload("text-message.json");

        // When
        var printed = assertThat(whatsApp.parseInbound(payload)).singleElement().actual().toString();

        // Then
        assertThat(printed)
                .doesNotContain("Ana Pérez")
                .doesNotContain("5491155550101")
                .doesNotContain(TEXT_MESSAGE_ID)
                .doesNotContain("financiación");
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
                   {"field": "messages", "value": {"messaging_product": "whatsapp", %2$s, "messages": [%1$s]}}
                 ]}]}""".formatted(ANA_TEXT, METADATA));

        // When
        var inbound = whatsApp.parseInbound(payload);
        var statuses = whatsApp.parseStatusUpdates(payload);

        // Then
        assertThat(inbound).containsExactly(UNNAMED_ANA_ASKS_FOR_FINANCING);
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
                        "metadata": {"display_phone_number": "15550783881", "phone_number_id": "%s",
                                     "verified_name": "Acme Motos"},
                        "contacts": [{"profile": {"name": "Ana Pérez", "username": "@anaperez"},
                                      "wa_id": "5491155550101", "user_id": "AR.13491208655302741918",
                                      "identity_key_hash": "DF2lS5v2W6x="}],
                        "messages": [{"from": "5491155550101", "id": "%s", "timestamp": "1772441960",
                                      "type": "text", "priority": "high",
                                      "text": {"body": "Hola, ¿tienen financiación?", "language": "es"}}],
                        "statuses": [{"id": "%s", "status": "delivered", "timestamp": "1772442010",
                                      "recipient_id": "5491155550101", "channel_hint": "mobile"}]
                      }
                    }]
                  }]
                }
                """.formatted(PHONE_NUMBER_ID, TEXT_MESSAGE_ID, REPLY_TO_ANA));

        // When
        var inbound = whatsApp.parseInbound(payload);
        var statuses = whatsApp.parseStatusUpdates(payload);

        // Then
        assertThat(inbound).containsExactly(ANA_ASKS_FOR_FINANCING);
        assertThat(statuses).containsExactly(new StatusUpdate(PHONE_NUMBER_ID, REPLY_TO_ANA, MessageStatus.DELIVERED));
    }

    @ParameterizedTest(name = "{0} ({1})")
    @MethodSource("malformedPayloadsInBothParsings")
    void malformedMetaPayloadIsRejected(String body, Parsing parsing) {
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
                        {"from": "US.13491208655302741918", "id": "wamid.BSUID-0002", "type": "text"}"""),
                Named.of("BSUID sender whose numeric part looks like a phone", """
                        {"from": "US.15551234567", "id": "wamid.BSUID-0003", "timestamp": "1772442010",
                         "type": "text", "text": {"body": "Hola"}}"""),
                Named.of("sender already written with a plus sign", """
                        {"from": "+5491155550101", "id": "wamid.PLUS-0001", "timestamp": "1772442010",
                         "type": "text", "text": {"body": "Hola"}}"""),
                Named.of("sender written with hyphens", """
                        {"from": "1-555-555-0101", "id": "wamid.HYPHENS-0001", "timestamp": "1772442010",
                         "type": "text", "text": {"body": "Hola"}}"""),
                Named.of("sender written with spaces", """
                        {"from": "54 9 11 5555 0101", "id": "wamid.SPACES-0001", "timestamp": "1772442010",
                         "type": "text", "text": {"body": "Hola"}}"""),
                Named.of("sender with keypad letters", """
                        {"from": "1800FLOWERS", "id": "wamid.LETTERS-0001", "timestamp": "1772442010",
                         "type": "text", "text": {"body": "Hola"}}"""),
                Named.of("sender with non-ASCII digits", """
                        {"from": "٥٤٩١١٥٥٥٥٠١٠١", "id": "wamid.ARABIC-0001", "timestamp": "1772442010",
                         "type": "text", "text": {"body": "Hola"}}"""));
    }

    static Stream<Named<String>> webhooksWithoutTheSenderProfileName() {
        return Stream.of(
                Named.of("without contacts", withMessage(ANA_TEXT)),
                Named.of("null contacts", withContacts("null")),
                Named.of("empty contacts", withContacts("[]")),
                Named.of("only the contact of another wa_id", withContacts("[%s]".formatted(JUAN_CONTACT))),
                Named.of("contact identified only by user id", withContacts("""
                        [{"profile": {"name": "Ana Pérez"}, "user_id": "AR.13491208655302741918"}]""")),
                Named.of("contact whose wa_id is the phone written with a plus sign", withContacts("""
                        [{"profile": {"name": "Ana Pérez"}, "wa_id": "+5491155550101"}]""")),
                Named.of("contact without profile", withContacts("""
                        [{"wa_id": "5491155550101"}]""")),
                Named.of("null profile", withContacts("""
                        [{"profile": null, "wa_id": "5491155550101"}]""")),
                Named.of("profile without name", withContacts("""
                        [{"profile": {}, "wa_id": "5491155550101"}]""")),
                Named.of("null name", withContacts("""
                        [{"profile": {"name": null}, "wa_id": "5491155550101"}]""")));
    }

    static Stream<Arguments> contactsWithUnusualProfileNames() {
        return Stream.of(
                Arguments.of(Named.of("NUL character in the name", """
                        {"profile": {"name": "Ana\\u0000Pérez"}, "wa_id": "5491155550101"}"""), "Ana\u0000Pérez"),
                Arguments.of(Named.of("line breaks in the name", """
                        {"profile": {"name": "Ana\\nPérez\\r\\n"}, "wa_id": "5491155550101"}"""), "Ana\nPérez\r\n"),
                Arguments.of(Named.of("blank name", """
                        {"profile": {"name": "   "}, "wa_id": "5491155550101"}"""), "   "),
                Arguments.of(Named.of("empty name", """
                        {"profile": {"name": ""}, "wa_id": "5491155550101"}"""), ""),
                Arguments.of(Named.of("numeric name", """
                        {"profile": {"name": 123}, "wa_id": "5491155550101"}"""), "123"),
                Arguments.of(Named.of("numeric wa_id", """
                        {"profile": {"name": "Ana Pérez"}, "wa_id": 5491155550101}"""), "Ana Pérez"));
    }

    static Stream<Arguments> malformedPayloadsInBothParsings() {
        var parsings = List.of(
                Named.<Parsing>of("inbound messages", WhatsAppChannelAdapter::parseInbound),
                Named.<Parsing>of("status updates", WhatsAppChannelAdapter::parseStatusUpdates));
        return malformedPayloads().flatMap(malformed -> parsings.stream()
                .map(parsing -> Arguments.of(malformed, parsing)));
    }

    private static Stream<Named<String>> malformedPayloads() {
        return Stream.of(
                Named.of("not JSON", "esto no es JSON"),
                Named.of("empty body", ""),
                Named.of("JSON null", "null"),
                Named.of("JSON array at the root", "[]"),
                Named.of("without object", """
                        {"entry": [{"id": "102290129340398", "changes": []}]}"""),
                Named.of("object of another product", """
                        {"object": "page", "entry": [{"id": "102290129340398", "changes": []}]}"""),
                Named.of("without entry", """
                        {"object": "whatsapp_business_account"}"""),
                Named.of("entry that is not a list", """
                        {"object": "whatsapp_business_account", "entry": {}}"""),
                Named.of("null entry", """
                        {"object": "whatsapp_business_account", "entry": [null]}"""),
                Named.of("entry without changes", """
                        {"object": "whatsapp_business_account", "entry": [{"id": "102290129340398"}]}"""),
                Named.of("null change", """
                        {"object": "whatsapp_business_account", "entry": [{"id": "102290129340398",
                         "changes": [null]}]}"""),
                Named.of("messages change without value", """
                        {"object": "whatsapp_business_account", "entry": [{"id": "102290129340398",
                         "changes": [{"field": "messages"}]}]}"""),
                Named.of("messages change with null value", webhookWithValue("null")),
                Named.of("without metadata", webhookWithValue("""
                        {"messaging_product": "whatsapp", "messages": [%s]}""".formatted(ANA_TEXT))),
                Named.of("without phone number id", webhookWithValue("""
                        {"messaging_product": "whatsapp", "metadata": {"display_phone_number": "15550783881"},
                         "messages": [%s]}""".formatted(ANA_TEXT))),
                Named.of("blank phone number id", webhookWithValue("""
                        {"messaging_product": "whatsapp",
                         "metadata": {"display_phone_number": "15550783881", "phone_number_id": "  "},
                         "messages": [%s]}""".formatted(ANA_TEXT))),
                Named.of("NUL character in the phone number id", webhookWithValue("""
                        {"messaging_product": "whatsapp",
                         "metadata": {"display_phone_number": "15550783881",
                                      "phone_number_id": "1065403\\u000052242922"},
                         "messages": [%s]}""".formatted(ANA_TEXT))),
                Named.of("messages that are not a list", webhookWithValue("""
                        {"messaging_product": "whatsapp", %s, "messages": {}}""".formatted(METADATA))),
                Named.of("statuses that are not a list", webhookWithValue("""
                        {"messaging_product": "whatsapp", %s, "statuses": {}}""".formatted(METADATA))),
                Named.of("null message", withMessage("null")),
                Named.of("text without id", withMessage("""
                        {"from": "5491155550101", "timestamp": "1772441960", "type": "text",
                         "text": {"body": "Hola"}}""")),
                Named.of("NUL character in a message id", withMessage("""
                        {"from": "5491155550101", "id": "wamid.HBgN\\u0000NTQ5", "timestamp": "1772441960",
                         "type": "text", "text": {"body": "Hola"}}""")),
                Named.of("text without timestamp", withMessage("""
                        {"from": "5491155550101", "id": "%s", "type": "text",
                         "text": {"body": "Hola"}}""".formatted(TEXT_MESSAGE_ID))),
                Named.of("non-numeric timestamp", withMessage("""
                        {"from": "5491155550101", "id": "%s", "timestamp": "ayer", "type": "text",
                         "text": {"body": "Hola"}}""".formatted(TEXT_MESSAGE_ID))),
                Named.of("timestamp out of the time range", withMessage("""
                        {"from": "5491155550101", "id": "%s", "timestamp": "99999999999999999", "type": "text",
                         "text": {"body": "Hola"}}""".formatted(TEXT_MESSAGE_ID))),
                Named.of("text message without text", withMessage("""
                        {"from": "5491155550101", "id": "%s", "timestamp": "1772441960",
                         "type": "text"}""".formatted(TEXT_MESSAGE_ID))),
                Named.of("text without body", withMessage("""
                        {"from": "5491155550101", "id": "%s", "timestamp": "1772441960", "type": "text",
                         "text": {}}""".formatted(TEXT_MESSAGE_ID))),
                Named.of("empty text body", withMessage("""
                        {"from": "5491155550101", "id": "%s", "timestamp": "1772441960", "type": "text",
                         "text": {"body": ""}}""".formatted(TEXT_MESSAGE_ID))),
                Named.of("NUL character in a body", withMessage("""
                        {"from": "5491155550101", "id": "%s", "timestamp": "1772441960", "type": "text",
                         "text": {"body": "Ho\\u0000la"}}""".formatted(TEXT_MESSAGE_ID))),
                Named.of("null status", withStatus("null")),
                Named.of("delivered status without id", withStatus("""
                        {"status": "delivered", "timestamp": "1772442010", "recipient_id": "5491155550101"}""")),
                Named.of("NUL character in a status id", withStatus("""
                        {"id": "wamid.HBgN\\u0000NTQ5", "status": "read", "timestamp": "1772442065",
                         "recipient_id": "5491155550101"}""")),
                Named.of("status without status", withStatus("""
                        {"id": "%s", "timestamp": "1772442010", "recipient_id": "5491155550101"}"""
                        .formatted(REPLY_TO_ANA))),
                Named.of("contacts that are not a list", withContacts("{}")),
                Named.of("null contact", withContacts("[null]")),
                Named.of("contact that is not an object", withContacts("[\"Ana Pérez\"]")),
                Named.of("profile that is not an object", withContacts("""
                        [{"profile": "Ana", "wa_id": "5491155550101"}]""")),
                Named.of("name that is an object", withContacts("""
                        [{"profile": {"name": {}}, "wa_id": "5491155550101"}]""")),
                Named.of("wa_id that is a list", withContacts("""
                        [{"profile": {"name": "Ana Pérez"}, "wa_id": ["5491155550101"]}]""")),
                Named.of("contacts that are not a list in a change with only statuses", webhookWithValue("""
                        {"messaging_product": "whatsapp", %s, "contacts": {},
                         "statuses": [{"id": "%s", "status": "delivered", "timestamp": "1772442010",
                                       "recipient_id": "5491155550101"}]}""".formatted(METADATA, REPLY_TO_ANA))));
    }

    private static InboundMessage anaAsksForFinancing(String senderName) {
        return new InboundMessage(PHONE_NUMBER_ID, ContactIdentity.phone("+5491155550101"), TEXT_MESSAGE_ID,
                new MessageContent("Hola, ¿tienen financiación?"), Instant.parse("2026-03-02T08:59:20Z"), senderName);
    }

    private static InboundMessage juanAsksAboutShipping(String senderName) {
        return new InboundMessage(PHONE_NUMBER_ID, ContactIdentity.phone("+34612345678"), JUAN_MESSAGE_ID,
                new MessageContent("¿Hacen envíos a Madrid?"), Instant.parse("2026-03-02T09:01:05Z"), senderName);
    }

    private static String withMessage(String message) {
        return webhook(message, "");
    }

    private static String withContacts(String contacts) {
        return withContacts(contacts, ANA_TEXT);
    }

    private static String withContacts(String contacts, String messages) {
        return webhookWithValue("""
                {"messaging_product": "whatsapp", %s, "contacts": %s, "messages": [%s]}"""
                .formatted(METADATA, contacts, messages));
    }

    private static String withStatus(String status) {
        return webhook("", status);
    }

    private static String webhook(String messages, String statuses) {
        return webhookWithValue("""
                {"messaging_product": "whatsapp", %s,
                 "messages": [%s], "statuses": [%s]}""".formatted(METADATA, messages, statuses));
    }

    private static String webhookWithValue(String value) {
        return """
                {"object": "whatsapp_business_account",
                 "entry": [{"id": "102290129340398", "changes": [{"field": "messages", "value": %s}]}]}"""
                .formatted(value);
    }

    interface Parsing extends BiFunction<WhatsAppChannelAdapter, WebhookPayload, List<?>> {
    }
}
