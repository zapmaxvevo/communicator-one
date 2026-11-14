package com.communicatorone.messaging;

import static com.communicatorone.messaging.MessagingFixture.TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;

import com.communicatorone.MutableClock;
import com.communicatorone.TestcontainersConfiguration;
import com.communicatorone.opportunities.OpportunityTracker;
import com.communicatorone.organizations.CurrentOrganization;
import com.communicatorone.people.PersonDirectory;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@ExtendWith(OutputCaptureExtension.class)
class TimelineTest {

    private static final Instant NOW = MutableClock.INITIAL_INSTANT;
    private static final Instant SENT_BY_THE_CUSTOMER_AT = NOW.minusSeconds(40);

    private final Timeline timeline;
    private final CurrentOrganization currentOrganization;
    private final TransactionTemplate transactionTemplate;
    private final MutableClock clock;
    private final ExecutorService background = Executors.newFixedThreadPool(2);
    private final MessagingFixture fixture;

    TimelineTest(Timeline timeline, CurrentOrganization currentOrganization, PersonDirectory directory,
            OpportunityTracker tracker, JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate,
            MutableClock clock) {
        this.timeline = timeline;
        this.currentOrganization = currentOrganization;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.fixture = new MessagingFixture(jdbcTemplate, transactionTemplate, background, currentOrganization,
                directory, tracker);
    }

    @BeforeEach
    void fixTheClock() {
        clock.setTo(NOW);
    }

    @AfterEach
    void stopBackgroundWorkAndResetTheClock() throws InterruptedException {
        background.shutdownNow();
        assertThat(background.awaitTermination(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        clock.setTo(NOW);
    }

    @Test
    void receivedMessageIsRecordedInThePersonTimeline() {
        // Given
        var acme = fixture.organization("Acme");
        var conversation = fixture.conversation(acme);
        var externalId = fixture.externalId();

        // When
        var recorded = receiveIn(acme,
                conversation.received(externalId, "Hola, ¿tienen financiación?", SENT_BY_THE_CUSTOMER_AT));

        // Then
        assertThat(recorded.duplicate()).isFalse();
        assertThat(fixture.messagesOf(acme)).containsExactly(conversation.storedReceived(recorded.id(), externalId,
                "Hola, ¿tienen financiación?", SENT_BY_THE_CUSTOMER_AT, NOW));
    }

    @Test
    void duplicatedWebhookDoesNotDuplicateMessage() {
        // Given
        var acme = fixture.organization("Acme");
        var conversation = fixture.conversation(acme);
        var externalId = fixture.externalId();
        var first = receiveIn(acme, conversation.received(externalId, "Hola", SENT_BY_THE_CUSTOMER_AT));
        clock.advance(Duration.ofMinutes(2));

        // When
        var resent = receiveIn(acme, conversation.received(externalId, "Hola", SENT_BY_THE_CUSTOMER_AT));

        // Then
        assertThat(resent).isEqualTo(new RecordedMessage(first.id(), true));
        assertThat(fixture.messagesOf(acme)).containsExactly(
                conversation.storedReceived(first.id(), externalId, "Hola", SENT_BY_THE_CUSTOMER_AT, NOW));
    }

    @Test
    void sameExternalIdOnAnotherChannelAccountIsADifferentMessage() {
        // Given
        var acme = fixture.organization("Acme");
        var onFirstAccount = fixture.conversation(acme);
        var onSecondAccount = onFirstAccount.onChannelAccount(fixture.channelAccount(acme));
        var externalId = fixture.externalId();
        var first = receiveIn(acme, onFirstAccount.received(externalId, "Hola", SENT_BY_THE_CUSTOMER_AT));

        // When
        var second = receiveIn(acme, onSecondAccount.received(externalId, "Buenas", SENT_BY_THE_CUSTOMER_AT));

        // Then
        assertThat(second.duplicate()).isFalse();
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(fixture.messagesOf(acme)).containsExactlyInAnyOrder(
                onFirstAccount.storedReceived(first.id(), externalId, "Hola", SENT_BY_THE_CUSTOMER_AT, NOW),
                onSecondAccount.storedReceived(second.id(), externalId, "Buenas", SENT_BY_THE_CUSTOMER_AT, NOW));
    }

    @Test
    void concurrentDuplicatedWebhooksRecordASingleMessage(CapturedOutput output) throws Exception {
        // Given
        var acme = fixture.organization("Acme");
        var conversation = fixture.conversation(acme);
        var externalId = fixture.externalId();
        var rival = fixture.rivalStartsRecordingMessage(conversation, externalId, "Hola, ¿tienen financiación?",
                SENT_BY_THE_CUSTOMER_AT, NOW);

        // When
        var recording = CompletableFuture.supplyAsync(() -> receiveIn(acme,
                conversation.received(externalId, "Hola, ¿tienen financiación?", SENT_BY_THE_CUSTOMER_AT)),
                background);
        await().atMost(TIMEOUT)
                .failFast(() -> assertThat(recording).as("recording waiting for the rival").isNotDone())
                .until(fixture::anotherSessionIsWaitingToWriteMessages);
        rival.commit();
        var recorded = recording.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

        // Then
        assertThat(recorded).isEqualTo(new RecordedMessage(rival.messageId(), true));
        assertThat(fixture.messagesOf(acme)).containsExactly(conversation.storedReceived(rival.messageId(),
                externalId, "Hola, ¿tienen financiación?", SENT_BY_THE_CUSTOMER_AT, NOW));
        assertThat(output.getAll())
                .contains("messages_organization_id_channel_account_id_external_id_key")
                .doesNotContain("tienen financiación");
    }

    @ParameterizedTest
    @EnumSource(value = SenderType.class, names = {"OPERATOR", "BOT", "SYSTEM"})
    void sentMessageIsRecordedAsSent(SenderType senderType) {
        // Given
        var acme = fixture.organization("Acme");
        var conversation = fixture.conversation(acme);
        var externalId = fixture.externalId();

        // When
        var recorded = sendIn(acme, conversation.sent(senderType, externalId, "Sí, hasta 12 cuotas"));

        // Then
        assertThat(recorded.duplicate()).isFalse();
        assertThat(fixture.messagesOf(acme)).containsExactly(
                conversation.storedSent(recorded.id(), senderType, externalId, "Sí, hasta 12 cuotas", NOW));
    }

    @Test
    void sentMessageAcknowledgedTwiceIsRecordedOnce() {
        // Given
        var acme = fixture.organization("Acme");
        var conversation = fixture.conversation(acme);
        var externalId = fixture.externalId();
        var first = sendIn(acme, conversation.sent(SenderType.OPERATOR, externalId, "Sí, hasta 12 cuotas"));
        clock.advance(Duration.ofSeconds(5));

        // When
        var again = sendIn(acme, conversation.sent(SenderType.OPERATOR, externalId, "Sí, hasta 12 cuotas"));

        // Then
        assertThat(again).isEqualTo(new RecordedMessage(first.id(), true));
        assertThat(fixture.messagesOf(acme)).containsExactly(
                conversation.storedSent(first.id(), SenderType.OPERATOR, externalId, "Sí, hasta 12 cuotas", NOW));
    }

    @Test
    void deliveryReportArrivingAfterTheReadReportDoesNotMoveTheStatusBack() {
        // Given
        var acme = fixture.organization("Acme");
        var conversation = fixture.conversation(acme);
        var externalId = fixture.externalId();
        var sent = sendIn(acme, conversation.sent(SenderType.OPERATOR, externalId, "Sí, hasta 12 cuotas"));

        // When
        var readReport = applyStatusIn(acme, conversation.channelAccountId(), externalId, MessageStatus.READ);
        var lateDeliveryReport = applyStatusIn(acme, conversation.channelAccountId(), externalId,
                MessageStatus.DELIVERED);

        // Then
        assertThat(readReport).isEqualTo(StatusUpdateOutcome.ADVANCED);
        assertThat(lateDeliveryReport).isEqualTo(StatusUpdateOutcome.UNCHANGED);
        assertThat(fixture.messagesOf(acme)).containsExactly(
                conversation.storedSent(sent.id(), SenderType.OPERATOR, externalId, "Sí, hasta 12 cuotas", NOW)
                        .withStatus(MessageStatus.READ));
    }

    @Test
    void statusReportForAnUnknownMessageIsIgnored() {
        // Given
        var acme = fixture.organization("Acme");
        var conversation = fixture.conversation(acme);
        var externalId = fixture.externalId();
        var sent = sendIn(acme, conversation.sent(SenderType.OPERATOR, externalId, "Sí, hasta 12 cuotas"));

        // When
        var outcome = applyStatusIn(acme, conversation.channelAccountId(), fixture.externalId(),
                MessageStatus.DELIVERED);

        // Then
        assertThat(outcome).isEqualTo(StatusUpdateOutcome.UNKNOWN_MESSAGE);
        assertThat(fixture.messagesOf(acme)).containsExactly(
                conversation.storedSent(sent.id(), SenderType.OPERATOR, externalId, "Sí, hasta 12 cuotas", NOW));
    }

    @Test
    void statusReportForTheSameExternalIdOnAnotherChannelAccountIsIgnored() {
        // Given
        var acme = fixture.organization("Acme");
        var conversation = fixture.conversation(acme);
        var externalId = fixture.externalId();
        var sent = sendIn(acme, conversation.sent(SenderType.OPERATOR, externalId, "Sí, hasta 12 cuotas"));

        // When
        var outcome = applyStatusIn(acme, fixture.channelAccount(acme), externalId, MessageStatus.READ);

        // Then
        assertThat(outcome).isEqualTo(StatusUpdateOutcome.UNKNOWN_MESSAGE);
        assertThat(fixture.messagesOf(acme)).containsExactly(
                conversation.storedSent(sent.id(), SenderType.OPERATOR, externalId, "Sí, hasta 12 cuotas", NOW));
    }

    @Test
    void statusReportCannotChangeAMessageOfAnotherOrganization() {
        // Given
        var acme = fixture.organization("Acme");
        var globex = fixture.organization("Globex");
        var acmeConversation = fixture.conversation(acme);
        var externalId = fixture.externalId();
        var acmeMessage = sendIn(acme,
                acmeConversation.sent(SenderType.OPERATOR, externalId, "Sí, hasta 12 cuotas"));

        // When
        var outcome = applyStatusIn(globex, acmeConversation.channelAccountId(), externalId, MessageStatus.READ);

        // Then
        assertThat(outcome).isEqualTo(StatusUpdateOutcome.UNKNOWN_MESSAGE);
        assertThat(fixture.messagesOf(acme)).containsExactly(acmeConversation.storedSent(acmeMessage.id(),
                SenderType.OPERATOR, externalId, "Sí, hasta 12 cuotas", NOW));
        assertThat(fixture.messagesOf(globex)).isEmpty();
    }

    @Test
    void deliveryReportArrivingWhileAReadReportIsBeingAppliedDoesNotMoveTheStatusBack() throws Exception {
        // Given
        var acme = fixture.organization("Acme");
        var conversation = fixture.conversation(acme);
        var externalId = fixture.externalId();
        var sent = sendIn(acme, conversation.sent(SenderType.OPERATOR, externalId, "Sí, hasta 12 cuotas"));
        var rivalReadReport = fixture.rivalStartsChangingStatus(acme, sent.id(), MessageStatus.READ);

        // When
        var deliveryReport = CompletableFuture.supplyAsync(() -> applyStatusIn(acme,
                conversation.channelAccountId(), externalId, MessageStatus.DELIVERED), background);
        await().atMost(TIMEOUT)
                .failFast(() -> assertThat(deliveryReport).as("delivery report waiting for the rival").isNotDone())
                .until(fixture::anotherSessionIsWaitingToLockTheMessage);
        rivalReadReport.commit();
        var outcome = deliveryReport.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

        // Then
        assertThat(outcome).isEqualTo(StatusUpdateOutcome.UNCHANGED);
        assertThat(fixture.messagesOf(acme)).containsExactly(
                conversation.storedSent(sent.id(), SenderType.OPERATOR, externalId, "Sí, hasta 12 cuotas", NOW)
                        .withStatus(MessageStatus.READ));
    }

    @Test
    void alreadyRecordedMessageIsRecognized() {
        // Given
        var acme = fixture.organization("Acme");
        var conversation = fixture.conversation(acme);
        var externalId = fixture.externalId();
        receiveIn(acme, conversation.received(externalId, "Hola", SENT_BY_THE_CUSTOMER_AT));

        // When
        var recognized = isRecordedIn(acme, conversation.channelAccountId(), externalId);

        // Then
        assertThat(recognized).isTrue();
    }

    @Test
    void messageNotYetRecordedIsNotRecognized() {
        // Given
        var acme = fixture.organization("Acme");
        var conversation = fixture.conversation(acme);
        receiveIn(acme, conversation.received(fixture.externalId(), "Hola", SENT_BY_THE_CUSTOMER_AT));

        // When
        var recognized = isRecordedIn(acme, conversation.channelAccountId(), fixture.externalId());

        // Then
        assertThat(recognized).isFalse();
    }

    @Test
    void messageRecordedOnAnotherChannelAccountIsNotRecognized() {
        // Given
        var acme = fixture.organization("Acme");
        var conversation = fixture.conversation(acme);
        var externalId = fixture.externalId();
        receiveIn(acme, conversation.received(externalId, "Hola", SENT_BY_THE_CUSTOMER_AT));

        // When
        var recognized = isRecordedIn(acme, fixture.channelAccount(acme), externalId);

        // Then
        assertThat(recognized).isFalse();
    }

    @Test
    void messageRecordedInAnotherOrganizationIsNotRecognized() {
        // Given
        var acme = fixture.organization("Acme");
        var globex = fixture.organization("Globex");
        var acmeConversation = fixture.conversation(acme);
        var externalId = fixture.externalId();
        receiveIn(acme, acmeConversation.received(externalId, "Hola", SENT_BY_THE_CUSTOMER_AT));

        // When
        var recognized = isRecordedIn(globex, acmeConversation.channelAccountId(), externalId);

        // Then
        assertThat(recognized).isFalse();
    }

    @Test
    void messageOfAPersonOfAnotherOrganizationIsRejected() {
        // Given
        var acme = fixture.organization("Acme");
        var globex = fixture.organization("Globex");
        var acmeConversation = fixture.conversation(acme);
        var globexPerson = fixture.person(globex);
        var externalId = fixture.externalId();

        // When
        var rejection = catchThrowable(() -> receiveIn(acme,
                acmeConversation.withPerson(globexPerson).received(externalId, "Hola", SENT_BY_THE_CUSTOMER_AT)));

        // Then
        assertThat(rejection).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(fixture.messagesOf(acme)).isEmpty();
        assertThat(fixture.messagesOf(globex)).isEmpty();
        assertThat(fixture.messagesInAnyOrganizationWithExternalId(externalId)).isZero();
    }

    @Test
    void messageCannotPointToAnOpportunityOfAnotherOrganization() {
        // Given
        var acme = fixture.organization("Acme");
        var globex = fixture.organization("Globex");
        var acmeConversation = fixture.conversation(acme);
        var globexOpportunity = fixture.conversation(globex).opportunityId();
        var externalId = fixture.externalId();

        // When
        var rejection = catchThrowable(() -> receiveIn(acme, acmeConversation.withOpportunity(globexOpportunity)
                .received(externalId, "Hola", SENT_BY_THE_CUSTOMER_AT)));

        // Then
        assertThat(rejection).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(fixture.messagesOf(acme)).isEmpty();
        assertThat(fixture.messagesOf(globex)).isEmpty();
        assertThat(fixture.messagesInAnyOrganizationWithExternalId(externalId)).isZero();
    }

    @Test
    void messageCannotPointToAnOpportunityOfAnotherPerson(CapturedOutput output) {
        // Given
        var acme = fixture.organization("Acme");
        var conversation = fixture.conversation(acme);
        var opportunityOfAnotherPerson = fixture.conversation(acme).opportunityId();
        var externalId = fixture.externalId();

        // When
        var rejection = catchThrowable(() -> receiveIn(acme, conversation.withOpportunity(opportunityOfAnotherPerson)
                .received(externalId, "Hola", SENT_BY_THE_CUSTOMER_AT)));

        // Then
        assertThat(rejection).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(fixture.messagesOf(acme)).isEmpty();
        assertThat(output.getAll()).contains("messages_opportunity_id_person_id_organization_id_fkey");
    }

    @Test
    void messageCannotPointToAChannelAccountOfAnotherOrganization(CapturedOutput output) {
        // Given
        var acme = fixture.organization("Acme");
        var globex = fixture.organization("Globex");
        var acmeConversation = fixture.conversation(acme);
        var globexChannelAccount = fixture.channelAccount(globex);
        var externalId = fixture.externalId();

        // When
        var rejection = catchThrowable(() -> receiveIn(acme, acmeConversation.onChannelAccount(globexChannelAccount)
                .received(externalId, "Hola", SENT_BY_THE_CUSTOMER_AT)));

        // Then
        assertThat(rejection).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(fixture.messagesOf(acme)).isEmpty();
        assertThat(fixture.messagesOf(globex)).isEmpty();
        assertThat(fixture.messagesInAnyOrganizationWithExternalId(externalId)).isZero();
        assertThat(output.getAll()).contains("messages_channel_account_id_organization_id_channel_fkey");
    }

    @Test
    void messageCannotPointToAnUnknownChannelAccount(CapturedOutput output) {
        // Given
        var acme = fixture.organization("Acme");
        var conversation = fixture.conversation(acme);
        var externalId = fixture.externalId();

        // When
        var rejection = catchThrowable(() -> receiveIn(acme, conversation.onChannelAccount(UUID.randomUUID())
                .received(externalId, "Hola", SENT_BY_THE_CUSTOMER_AT)));

        // Then
        assertThat(rejection).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(fixture.messagesOf(acme)).isEmpty();
        assertThat(fixture.messagesInAnyOrganizationWithExternalId(externalId)).isZero();
        assertThat(output.getAll()).contains("messages_channel_account_id_organization_id_channel_fkey");
    }

    @Test
    void messageOnAChannelOtherThanItsAccountTypeIsRejected(CapturedOutput output) {
        // Given
        var acme = fixture.organization("Acme");
        var telegramAccount = fixture.channelAccount(acme, ChannelType.TELEGRAM);
        var conversation = fixture.conversation(acme).onChannelAccount(telegramAccount);
        var externalId = fixture.externalId();

        // When
        var rejection = catchThrowable(() -> receiveIn(acme,
                conversation.received(externalId, "Hola", SENT_BY_THE_CUSTOMER_AT)));

        // Then
        assertThat(rejection).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(fixture.messagesOf(acme)).isEmpty();
        assertThat(fixture.messagesInAnyOrganizationWithExternalId(externalId)).isZero();
        assertThat(output.getAll()).contains("messages_channel_account_id_organization_id_channel_fkey");
    }

    @Test
    void messageCannotBeRecordedWithoutOrganization() {
        // Given
        var acme = fixture.organization("Acme");
        var conversation = fixture.conversation(acme);
        var externalId = fixture.externalId();

        // When
        var rejection = catchThrowable(() -> timeline.recordReceived(
                conversation.received(externalId, "Hola", SENT_BY_THE_CUSTOMER_AT)));

        // Then
        assertThat(rejection).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(fixture.messagesInAnyOrganizationWithExternalId(externalId)).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = Propagation.class, names = {"REQUIRED", "SUPPORTS"})
    void messageCannotBeRecordedInsideAnOpenTransactionalScope(Propagation propagation) {
        // Given
        var acme = fixture.organization("Acme");
        var conversation = fixture.conversation(acme);
        var externalId = fixture.externalId();
        var scope = new TransactionTemplate(transactionTemplate.getTransactionManager());
        scope.setPropagationBehavior(propagation.value());

        // When
        var rejection = catchThrowable(() -> currentOrganization.runAs(acme,
                () -> scope.executeWithoutResult(status -> timeline.recordReceived(
                        conversation.received(externalId, "Hola", SENT_BY_THE_CUSTOMER_AT)))));

        // Then
        assertThat(rejection).isInstanceOf(IllegalStateException.class);
        assertThat(fixture.messagesOf(acme)).isEmpty();
    }

    @Test
    void receivedMessageWithoutExternalIdLeavesNothingInTheTimeline() {
        // Given
        var acme = fixture.organization("Acme");
        var conversation = fixture.conversation(acme);

        // When
        var rejection = catchThrowable(() -> receiveIn(acme,
                conversation.received("   ", "Hola", SENT_BY_THE_CUSTOMER_AT)));

        // Then
        assertThat(rejection).isInstanceOf(IllegalArgumentException.class);
        assertThat(fixture.messagesOf(acme)).isEmpty();
    }

    private RecordedMessage receiveIn(UUID organizationId, ReceivedMessage received) {
        return currentOrganization.callAs(organizationId, () -> timeline.recordReceived(received));
    }

    private RecordedMessage sendIn(UUID organizationId, SentMessage sent) {
        return currentOrganization.callAs(organizationId, () -> timeline.recordSent(sent));
    }

    private StatusUpdateOutcome applyStatusIn(UUID organizationId, UUID channelAccountId, String externalId,
            MessageStatus status) {
        return currentOrganization.callAs(organizationId,
                () -> timeline.applyStatus(channelAccountId, externalId, status));
    }

    private boolean isRecordedIn(UUID organizationId, UUID channelAccountId, String externalId) {
        return currentOrganization.callAs(organizationId, () -> timeline.isRecorded(channelAccountId, externalId));
    }
}
