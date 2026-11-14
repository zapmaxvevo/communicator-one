package com.communicatorone.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.communicatorone.MutableClock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class MessageTest {

    private static final Instant NOW = MutableClock.INITIAL_INSTANT;
    private static final Instant SENT_BY_THE_CUSTOMER_AT = NOW.minusSeconds(40);
    private static final MessagingFixture.Conversation CONVERSATION = new MessagingFixture.Conversation(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    @Test
    void deliveryAndReadReportsAdvanceASentMessage() {
        // Given
        var message = sentByAnOperator();

        // When
        var delivered = message.advanceTo(MessageStatus.DELIVERED);
        var read = message.advanceTo(MessageStatus.READ);

        // Then
        assertThat(delivered).isTrue();
        assertThat(read).isTrue();
        assertThat(message.status()).isEqualTo(MessageStatus.READ);
    }

    @Test
    void deliveryReportArrivingAfterTheReadReportDoesNotMoveTheStatusBack() {
        // Given
        var message = sentByAnOperator();
        message.advanceTo(MessageStatus.READ);

        // When
        var changed = message.advanceTo(MessageStatus.DELIVERED);

        // Then
        assertThat(changed).isFalse();
        assertThat(message.status()).isEqualTo(MessageStatus.READ);
    }

    @Test
    void repeatedStatusReportChangesNothing() {
        // Given
        var message = sentByAnOperator();
        message.advanceTo(MessageStatus.DELIVERED);

        // When
        var changed = message.advanceTo(MessageStatus.DELIVERED);

        // Then
        assertThat(changed).isFalse();
        assertThat(message.status()).isEqualTo(MessageStatus.DELIVERED);
    }

    @Test
    void failureReportedAfterDeliveryIsIgnored() {
        // Given
        var message = sentByAnOperator();
        message.advanceTo(MessageStatus.DELIVERED);

        // When
        var changed = message.advanceTo(MessageStatus.FAILED);

        // Then
        assertThat(changed).isFalse();
        assertThat(message.status()).isEqualTo(MessageStatus.DELIVERED);
    }

    @Test
    void deliveryReportedAfterAFailureWins() {
        // Given
        var message = sentByAnOperator();
        message.advanceTo(MessageStatus.FAILED);

        // When
        var changed = message.advanceTo(MessageStatus.DELIVERED);

        // Then
        assertThat(changed).isTrue();
        assertThat(message.status()).isEqualTo(MessageStatus.DELIVERED);
    }

    @ParameterizedTest
    @EnumSource(value = MessageStatus.class, names = "DELETED", mode = EnumSource.Mode.EXCLUDE)
    void deletedMessageStaysDeletedWhateverIsReportedLater(MessageStatus reportedLater) {
        // Given
        var message = sentByAnOperator();
        message.advanceTo(MessageStatus.DELETED);

        // When
        var changed = message.advanceTo(reportedLater);

        // Then
        assertThat(changed).isFalse();
        assertThat(message.status()).isEqualTo(MessageStatus.DELETED);
    }

    @Test
    void receivedMessageArrivesDeliveredAndOnlyMovesForwardFromThere() {
        // Given
        var message = Message.received(CONVERSATION.received("wamid.1", "Hola", SENT_BY_THE_CUSTOMER_AT), NOW);
        var arrivedAs = message.status();

        // When
        var failed = message.advanceTo(MessageStatus.FAILED);
        var read = message.advanceTo(MessageStatus.READ);

        // Then
        assertThat(arrivedAs).isEqualTo(MessageStatus.DELIVERED);
        assertThat(failed).isFalse();
        assertThat(read).isTrue();
        assertThat(message.status()).isEqualTo(MessageStatus.READ);
    }

    @Test
    void messageCannotBeSentAsTheCustomer() {
        // Given
        var asTheCustomer = CONVERSATION.sent(SenderType.CUSTOMER, "wamid.1", "Sí, hasta 12 cuotas");

        // When
        var rejection = catchThrowable(() -> Message.sent(asTheCustomer, NOW));

        // Then
        assertThat(rejection).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A sent message comes from the organization, not the customer");
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {"null, Hola", "'', Hola", "'   ', Hola", "wamid.1, null",
            "wamid.1, ''"})
    void receivedMessageWithoutExternalIdOrTextIsRejected(String externalId, String text) {
        // When
        var rejection = catchThrowable(() -> Message.received(
                CONVERSATION.received(externalId, text, SENT_BY_THE_CUSTOMER_AT), NOW));

        // Then
        assertThat(rejection).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void messageTextIsNotRevealedWhenItsContentIsLogged() {
        // Given
        var content = new MessageContent("Mi DNI es 30111222");

        // When
        var logged = String.valueOf(content);

        // Then
        assertThat(logged).doesNotContain("30111222");
    }

    private static Message sentByAnOperator() {
        return Message.sent(CONVERSATION.sent(SenderType.OPERATOR, "wamid.1", "Sí, hasta 12 cuotas"), NOW);
    }
}
