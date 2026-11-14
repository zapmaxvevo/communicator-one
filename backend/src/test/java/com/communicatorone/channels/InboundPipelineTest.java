package com.communicatorone.channels;

import static com.communicatorone.channels.InboundPipelineFixture.TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;

import com.communicatorone.MutableClock;
import com.communicatorone.TestcontainersConfiguration;
import com.communicatorone.channels.InboundPipelineFixture.IncomingText;
import com.communicatorone.channels.InboundPipelineFixture.StatusReport;
import com.communicatorone.channels.InboundPipelineFixture.StoredIdentity;
import com.communicatorone.channels.InboundPipelineFixture.StoredMessage;
import com.communicatorone.channels.InboundPipelineFixture.StoredOpportunity;
import com.communicatorone.messaging.ChannelType;
import com.communicatorone.messaging.MessageStatus;
import com.communicatorone.messaging.Timeline;
import com.communicatorone.opportunities.OpportunityTracker;
import com.communicatorone.organizations.CurrentOrganization;
import com.communicatorone.people.IdentityKind;
import com.communicatorone.people.PersonDirectory;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class InboundPipelineTest {

    private static final Instant NOW = MutableClock.INITIAL_INSTANT;
    private static final Instant SENT_AT = NOW.minusSeconds(40);
    private static final Duration DEFAULT_INACTIVITY_PERIOD = Duration.ofDays(7);
    private static final Duration DEFAULT_REENTRY_WINDOW = Duration.ofDays(3);
    private static final String ANA = "+5491155550101";
    private static final String ANA_AS_TYPED = "+54 9 11 5555-0101";
    private static final String JUAN = "+5491155550202";

    private final InboundPipeline pipeline;
    private final ReceivedMessages receivedMessages;
    private final MutableClock clock;
    private final ExecutorService background = Executors.newFixedThreadPool(2);
    private final ChannelsFixture channelAccounts;
    private final InboundPipelineFixture fixture;

    InboundPipelineTest(InboundPipeline pipeline, ReceivedMessages receivedMessages, MutableClock clock,
            JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate,
            CurrentOrganization currentOrganization, ChannelAccountRepository accountRepository,
            PersonDirectory people, OpportunityTracker opportunities, Timeline timeline) {
        this.pipeline = pipeline;
        this.receivedMessages = receivedMessages;
        this.clock = clock;
        this.channelAccounts = new ChannelsFixture(jdbcTemplate, currentOrganization, accountRepository);
        this.fixture = new InboundPipelineFixture(jdbcTemplate, transactionTemplate, background, currentOrganization,
                people, opportunities, timeline);
    }

    @BeforeEach
    void fixTheClockAndForgetPreviousEvents() {
        clock.setTo(NOW);
        receivedMessages.forget();
    }

    @AfterEach
    void stopBackgroundWorkAndResetTheClock() throws InterruptedException {
        background.shutdownNow();
        assertThat(background.awaitTermination(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        clock.setTo(NOW);
    }

    @Test
    void messageFromUnknownIdentityCreatesPersonWithOpenOpportunityAndRecordsTheMessage() {
        // Given
        var acme = channelAccounts.organization("Acme");
        var account = channelAccounts.sandboxAccount(acme);
        var messageId = fixture.messageId();
        var payload = fixture.messagesTo(account.externalAccountId(),
                new IncomingText(messageId, ANA_AS_TYPED, "Hola, ¿tienen financiación?", SENT_AT));

        // When
        pipeline.receive(ChannelType.SANDBOX, payload);

        // Then
        var opportunity = only(fixture.opportunitiesOf(acme));
        var message = only(fixture.messagesOf(acme));
        var person = opportunity.personId();
        assertThat(fixture.identitiesOf(acme)).containsExactly(new StoredIdentity(person, IdentityKind.PHONE, ANA));
        assertThat(opportunity).isEqualTo(StoredOpportunity.open(opportunity.id(), person, NOW, NOW));
        assertThat(message).isEqualTo(StoredMessage.received(message.id(), person, opportunity.id(), account.id(),
                messageId, "Hola, ¿tienen financiación?", SENT_AT, NOW));
        assertThat(receivedMessages.in(acme)).containsExactly(
                new MessageReceived(acme, message.id(), person, opportunity.id(), account.id(), ChannelType.SANDBOX));
    }

    @Test
    void duplicatedWebhookDoesNotDuplicateMessageNorCountAsActivity() {
        // Given
        var acme = channelAccounts.organization("Acme");
        var account = channelAccounts.sandboxAccount(acme);
        var messageId = fixture.messageId();
        var payload = fixture.messagesTo(account.externalAccountId(),
                new IncomingText(messageId, ANA, "Hola", SENT_AT));
        pipeline.receive(ChannelType.SANDBOX, payload);
        clock.advance(Duration.ofMinutes(5));

        // When
        pipeline.receive(ChannelType.SANDBOX, payload);

        // Then
        var opportunity = only(fixture.opportunitiesOf(acme));
        var message = only(fixture.messagesOf(acme));
        var person = opportunity.personId();
        assertThat(fixture.identitiesOf(acme)).containsExactly(new StoredIdentity(person, IdentityKind.PHONE, ANA));
        assertThat(opportunity).isEqualTo(StoredOpportunity.open(opportunity.id(), person, NOW, NOW));
        assertThat(message).isEqualTo(StoredMessage.received(message.id(), person, opportunity.id(), account.id(),
                messageId, "Hola", SENT_AT, NOW));
        assertThat(receivedMessages.in(acme)).containsExactly(
                new MessageReceived(acme, message.id(), person, opportunity.id(), account.id(), ChannelType.SANDBOX));
    }

    @Test
    void repeatedMessageInsideOneWebhookIsRecordedOnce() {
        // Given
        var acme = channelAccounts.organization("Acme");
        var account = channelAccounts.sandboxAccount(acme);
        var messageId = fixture.messageId();
        var payload = fixture.messagesTo(account.externalAccountId(),
                new IncomingText(messageId, ANA, "Hola", SENT_AT),
                new IncomingText(messageId, ANA, "Hola", SENT_AT));

        // When
        pipeline.receive(ChannelType.SANDBOX, payload);

        // Then
        var opportunity = only(fixture.opportunitiesOf(acme));
        var message = only(fixture.messagesOf(acme));
        var person = opportunity.personId();
        assertThat(fixture.identitiesOf(acme)).containsExactly(new StoredIdentity(person, IdentityKind.PHONE, ANA));
        assertThat(opportunity).isEqualTo(StoredOpportunity.open(opportunity.id(), person, NOW, NOW));
        assertThat(message).isEqualTo(StoredMessage.received(message.id(), person, opportunity.id(), account.id(),
                messageId, "Hola", SENT_AT, NOW));
        assertThat(receivedMessages.in(acme)).containsExactly(
                new MessageReceived(acme, message.id(), person, opportunity.id(), account.id(), ChannelType.SANDBOX));
    }

    @Test
    void knownPersonWritingAgainAddsTheMessageToHerOpenOpportunity() {
        // Given
        var acme = channelAccounts.organization("Acme");
        var account = channelAccounts.sandboxAccount(acme);
        var firstId = fixture.messageId();
        var secondId = fixture.messageId();
        pipeline.receive(ChannelType.SANDBOX, fixture.messagesTo(account.externalAccountId(),
                new IncomingText(firstId, ANA, "Hola, ¿tienen financiación?", SENT_AT)));
        var wroteAgainAt = NOW.plus(Duration.ofHours(2));
        clock.setTo(wroteAgainAt);

        // When
        pipeline.receive(ChannelType.SANDBOX, fixture.messagesTo(account.externalAccountId(),
                new IncomingText(secondId, ANA, "¿Y en cuántas cuotas?", wroteAgainAt.minusSeconds(10))));

        // Then
        var opportunity = only(fixture.opportunitiesOf(acme));
        var person = opportunity.personId();
        var messages = fixture.messagesOf(acme);
        assertThat(fixture.identitiesOf(acme)).containsExactly(new StoredIdentity(person, IdentityKind.PHONE, ANA));
        assertThat(opportunity).isEqualTo(StoredOpportunity.open(opportunity.id(), person, NOW, wroteAgainAt));
        assertThat(messages).hasSize(2);
        assertThat(messages).containsExactly(
                StoredMessage.received(messages.get(0).id(), person, opportunity.id(), account.id(), firstId,
                        "Hola, ¿tienen financiación?", SENT_AT, NOW),
                StoredMessage.received(messages.get(1).id(), person, opportunity.id(), account.id(), secondId,
                        "¿Y en cuántas cuotas?", wroteAgainAt.minusSeconds(10), wroteAgainAt));
        assertThat(receivedMessages.in(acme)).containsExactly(
                new MessageReceived(acme, messages.get(0).id(), person, opportunity.id(), account.id(),
                        ChannelType.SANDBOX),
                new MessageReceived(acme, messages.get(1).id(), person, opportunity.id(), account.id(),
                        ChannelType.SANDBOX));
    }

    @Test
    void personReturningAfterReentryWindowGetsNewOpportunityWithTheMessage() {
        // Given
        var acme = channelAccounts.organization("Acme");
        var account = channelAccounts.sandboxAccount(acme);
        var firstId = fixture.messageId();
        var returningId = fixture.messageId();
        pipeline.receive(ChannelType.SANDBOX, fixture.messagesTo(account.externalAccountId(),
                new IncomingText(firstId, ANA, "Hola, ¿tienen financiación?", SENT_AT)));
        var returnedAt = NOW.plus(DEFAULT_INACTIVITY_PERIOD).plus(DEFAULT_REENTRY_WINDOW);
        clock.setTo(returnedAt);

        // When
        pipeline.receive(ChannelType.SANDBOX, fixture.messagesTo(account.externalAccountId(),
                new IncomingText(returningId, ANA, "Hola de nuevo", returnedAt.minusSeconds(10))));

        // Then
        var opportunities = fixture.opportunitiesOf(acme);
        var messages = fixture.messagesOf(acme);
        assertThat(opportunities).hasSize(2);
        assertThat(messages).hasSize(2);
        var previous = opportunities.get(0);
        var current = opportunities.get(1);
        var person = previous.personId();
        assertThat(fixture.identitiesOf(acme)).containsExactly(new StoredIdentity(person, IdentityKind.PHONE, ANA));
        assertThat(opportunities).containsExactly(
                StoredOpportunity.open(previous.id(), person, NOW, NOW)
                        .closedWithoutResponseAt(NOW.plus(DEFAULT_INACTIVITY_PERIOD)),
                StoredOpportunity.open(current.id(), person, returnedAt, returnedAt));
        assertThat(messages).containsExactly(
                StoredMessage.received(messages.get(0).id(), person, previous.id(), account.id(), firstId,
                        "Hola, ¿tienen financiación?", SENT_AT, NOW),
                StoredMessage.received(messages.get(1).id(), person, current.id(), account.id(), returningId,
                        "Hola de nuevo", returnedAt.minusSeconds(10), returnedAt));
        assertThat(receivedMessages.in(acme)).containsExactly(
                new MessageReceived(acme, messages.get(0).id(), person, previous.id(), account.id(),
                        ChannelType.SANDBOX),
                new MessageReceived(acme, messages.get(1).id(), person, current.id(), account.id(),
                        ChannelType.SANDBOX));
    }

    @Test
    void webhookWithMessagesFromTwoPeopleRecordsEachOnItsOwnTimeline() {
        // Given
        var acme = channelAccounts.organization("Acme");
        var account = channelAccounts.sandboxAccount(acme);
        var fromAna = fixture.messageId();
        var fromJuan = fixture.messageId();
        var payload = fixture.messagesTo(account.externalAccountId(),
                new IncomingText(fromAna, ANA, "Hola, ¿tienen financiación?", SENT_AT),
                new IncomingText(fromJuan, JUAN, "¿Hacen envíos al interior?", SENT_AT.plusSeconds(5)));

        // When
        pipeline.receive(ChannelType.SANDBOX, payload);

        // Then
        var identities = fixture.identitiesOf(acme);
        var ana = personWithPhone(identities, ANA);
        var juan = personWithPhone(identities, JUAN);
        var anaOpportunity = opportunityOf(fixture.opportunitiesOf(acme), ana);
        var juanOpportunity = opportunityOf(fixture.opportunitiesOf(acme), juan);
        var messages = fixture.messagesOf(acme);
        assertThat(ana).isNotEqualTo(juan);
        assertThat(identities).containsExactlyInAnyOrder(new StoredIdentity(ana, IdentityKind.PHONE, ANA),
                new StoredIdentity(juan, IdentityKind.PHONE, JUAN));
        assertThat(fixture.opportunitiesOf(acme)).containsExactlyInAnyOrder(
                StoredOpportunity.open(anaOpportunity.id(), ana, NOW, NOW),
                StoredOpportunity.open(juanOpportunity.id(), juan, NOW, NOW));
        assertThat(messages).hasSize(2);
        assertThat(messages).containsExactly(
                StoredMessage.received(messages.get(0).id(), ana, anaOpportunity.id(), account.id(), fromAna,
                        "Hola, ¿tienen financiación?", SENT_AT, NOW),
                StoredMessage.received(messages.get(1).id(), juan, juanOpportunity.id(), account.id(), fromJuan,
                        "¿Hacen envíos al interior?", SENT_AT.plusSeconds(5), NOW));
        assertThat(receivedMessages.in(acme)).containsExactly(
                new MessageReceived(acme, messages.get(0).id(), ana, anaOpportunity.id(), account.id(),
                        ChannelType.SANDBOX),
                new MessageReceived(acme, messages.get(1).id(), juan, juanOpportunity.id(), account.id(),
                        ChannelType.SANDBOX));
    }

    @Test
    void messageIsRecordedInTheOrganizationOfTheAccountThatReceivedIt() {
        // Given
        var acme = channelAccounts.organization("Acme");
        var globex = channelAccounts.organization("Globex");
        var acmeAccount = channelAccounts.sandboxAccount(acme);
        var globexAccount = channelAccounts.sandboxAccount(globex);
        var toAcme = fixture.messageId();
        var toGlobex = fixture.messageId();

        // When
        pipeline.receive(ChannelType.SANDBOX, fixture.messagesTo(acmeAccount.externalAccountId(),
                new IncomingText(toAcme, ANA, "Hola Acme", SENT_AT)));
        pipeline.receive(ChannelType.SANDBOX, fixture.messagesTo(globexAccount.externalAccountId(),
                new IncomingText(toGlobex, ANA, "Hola Globex", SENT_AT)));

        // Then
        var acmeOpportunity = only(fixture.opportunitiesOf(acme));
        var globexOpportunity = only(fixture.opportunitiesOf(globex));
        var acmeMessage = only(fixture.messagesOf(acme));
        var globexMessage = only(fixture.messagesOf(globex));
        var acmePerson = acmeOpportunity.personId();
        var globexPerson = globexOpportunity.personId();
        assertThat(acmePerson).isNotEqualTo(globexPerson);
        assertThat(fixture.identitiesOf(acme))
                .containsExactly(new StoredIdentity(acmePerson, IdentityKind.PHONE, ANA));
        assertThat(fixture.identitiesOf(globex))
                .containsExactly(new StoredIdentity(globexPerson, IdentityKind.PHONE, ANA));
        assertThat(acmeOpportunity).isEqualTo(StoredOpportunity.open(acmeOpportunity.id(), acmePerson, NOW, NOW));
        assertThat(globexOpportunity)
                .isEqualTo(StoredOpportunity.open(globexOpportunity.id(), globexPerson, NOW, NOW));
        assertThat(acmeMessage).isEqualTo(StoredMessage.received(acmeMessage.id(), acmePerson, acmeOpportunity.id(),
                acmeAccount.id(), toAcme, "Hola Acme", SENT_AT, NOW));
        assertThat(globexMessage).isEqualTo(StoredMessage.received(globexMessage.id(), globexPerson,
                globexOpportunity.id(), globexAccount.id(), toGlobex, "Hola Globex", SENT_AT, NOW));
        assertThat(receivedMessages.in(acme)).containsExactly(new MessageReceived(acme, acmeMessage.id(),
                acmePerson, acmeOpportunity.id(), acmeAccount.id(), ChannelType.SANDBOX));
        assertThat(receivedMessages.in(globex)).containsExactly(new MessageReceived(globex, globexMessage.id(),
                globexPerson, globexOpportunity.id(), globexAccount.id(), ChannelType.SANDBOX));
    }

    @Test
    void samePersonWritingToTwoAccountsOfTheOrganizationKeepsOneOpportunity() {
        // Given
        var acme = channelAccounts.organization("Acme");
        var salesAccount = channelAccounts.sandboxAccount(acme);
        var supportAccount = channelAccounts.sandboxAccount(acme);
        var toSales = fixture.messageId();
        var toSupport = fixture.messageId();
        pipeline.receive(ChannelType.SANDBOX, fixture.messagesTo(salesAccount.externalAccountId(),
                new IncomingText(toSales, ANA, "Hola, ¿tienen financiación?", SENT_AT)));
        var wroteAgainAt = NOW.plus(Duration.ofHours(1));
        clock.setTo(wroteAgainAt);

        // When
        pipeline.receive(ChannelType.SANDBOX, fixture.messagesTo(supportAccount.externalAccountId(),
                new IncomingText(toSupport, ANA, "Escribo también por acá", wroteAgainAt.minusSeconds(10))));

        // Then
        var opportunity = only(fixture.opportunitiesOf(acme));
        var person = opportunity.personId();
        var messages = fixture.messagesOf(acme);
        assertThat(fixture.identitiesOf(acme)).containsExactly(new StoredIdentity(person, IdentityKind.PHONE, ANA));
        assertThat(opportunity).isEqualTo(StoredOpportunity.open(opportunity.id(), person, NOW, wroteAgainAt));
        assertThat(messages).hasSize(2);
        assertThat(messages).containsExactly(
                StoredMessage.received(messages.get(0).id(), person, opportunity.id(), salesAccount.id(), toSales,
                        "Hola, ¿tienen financiación?", SENT_AT, NOW),
                StoredMessage.received(messages.get(1).id(), person, opportunity.id(), supportAccount.id(),
                        toSupport, "Escribo también por acá", wroteAgainAt.minusSeconds(10), wroteAgainAt));
        assertThat(receivedMessages.in(acme)).containsExactly(
                new MessageReceived(acme, messages.get(0).id(), person, opportunity.id(), salesAccount.id(),
                        ChannelType.SANDBOX),
                new MessageReceived(acme, messages.get(1).id(), person, opportunity.id(), supportAccount.id(),
                        ChannelType.SANDBOX));
    }

    @Test
    void messageForUnknownChannelAccountIsDiscardedWithoutTouchingAnyOrganization() {
        // Given
        var acme = channelAccounts.organization("Acme");
        channelAccounts.sandboxAccount(acme);
        var unknownAccount = channelAccounts.externalAccountId("sandbox");
        var phone = fixture.unusedPhone();
        var messageId = fixture.messageId();
        var payload = fixture.messagesTo(unknownAccount, new IncomingText(messageId, phone, "Hola", SENT_AT));

        // When
        var receiving = assertThatCode(() -> pipeline.receive(ChannelType.SANDBOX, payload));

        // Then
        receiving.doesNotThrowAnyException();
        assertThat(fixture.identitiesInAnyOrganizationWithValue(phone)).isZero();
        assertThat(fixture.messagesInAnyOrganizationWithExternalId(messageId)).isZero();
        assertThat(fixture.identitiesOf(acme)).isEmpty();
        assertThat(fixture.opportunitiesOf(acme)).isEmpty();
        assertThat(receivedMessages.all()).isEmpty();
    }

    @Test
    void invalidWebhookPayloadIsRejectedWithoutRecordingAnything() {
        // Given
        var acme = channelAccounts.organization("Acme");
        var account = channelAccounts.sandboxAccount(acme);
        var payload = fixture.messagesTo(account.externalAccountId(),
                new IncomingText(fixture.messageId(), ANA, "Hola", SENT_AT),
                new IncomingText(fixture.messageId(), "5491155550202", "¿Hacen envíos?", SENT_AT));

        // When
        var rejection = catchThrowable(() -> pipeline.receive(ChannelType.SANDBOX, payload));

        // Then
        assertThat(rejection).isInstanceOf(InvalidWebhookPayloadException.class);
        assertThat(fixture.identitiesOf(acme)).isEmpty();
        assertThat(fixture.opportunitiesOf(acme)).isEmpty();
        assertThat(fixture.messagesOf(acme)).isEmpty();
        assertThat(receivedMessages.all()).isEmpty();
    }

    @Test
    void copyOfTheWebhookRecordedConcurrentlyElsewherePublishesNoEvent() throws Exception {
        // Given
        var acme = channelAccounts.organization("Acme");
        var account = channelAccounts.sandboxAccount(acme);
        var conversation = fixture.conversationWith(acme, ANA);
        var messageId = fixture.messageId();
        var rival = fixture.rivalStartsRecordingMessage(account, conversation, messageId,
                "Hola, ¿tienen financiación?", SENT_AT, NOW);
        var payload = fixture.messagesTo(account.externalAccountId(),
                new IncomingText(messageId, ANA, "Hola, ¿tienen financiación?", SENT_AT));

        // When
        var receiving = CompletableFuture.runAsync(() -> pipeline.receive(ChannelType.SANDBOX, payload),
                background);
        await().atMost(TIMEOUT)
                .failFast(() -> assertThat(receiving).as("webhook waiting for the rival copy").isNotDone())
                .until(fixture::anotherSessionIsWaitingToWriteMessages);
        rival.commit();
        receiving.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

        // Then
        assertThat(fixture.identitiesOf(acme))
                .containsExactly(new StoredIdentity(conversation.personId(), IdentityKind.PHONE, ANA));
        assertThat(fixture.opportunitiesOf(acme)).singleElement()
                .extracting(StoredOpportunity::id).isEqualTo(conversation.opportunityId());
        assertThat(fixture.messagesOf(acme)).containsExactly(StoredMessage.received(rival.messageId(),
                conversation.personId(), conversation.opportunityId(), account.id(), messageId,
                "Hola, ¿tienen financiación?", SENT_AT, NOW));
        assertThat(receivedMessages.in(acme)).isEmpty();
    }

    @Test
    void deliveryReportArrivingAfterTheReadReportDoesNotMoveTheStatusBack() {
        // Given
        var acme = channelAccounts.organization("Acme");
        var account = channelAccounts.sandboxAccount(acme);
        var conversation = fixture.conversationWith(acme, ANA);
        var reply = fixture.operatorReplied(account, conversation, "Sí, hasta 12 cuotas");
        pipeline.receive(ChannelType.SANDBOX, fixture.statusesTo(account.externalAccountId(),
                new StatusReport(reply.externalId(), MessageStatus.READ)));

        // When
        pipeline.receive(ChannelType.SANDBOX, fixture.statusesTo(account.externalAccountId(),
                new StatusReport(reply.externalId(), MessageStatus.DELIVERED)));

        // Then
        assertThat(fixture.messagesOf(acme)).containsExactly(reply.withStatus(MessageStatus.READ));
        assertThat(receivedMessages.in(acme)).isEmpty();
    }

    @Test
    void statusForUnknownMessageIsIgnoredWithoutFailingTheWebhook() {
        // Given
        var acme = channelAccounts.organization("Acme");
        var account = channelAccounts.sandboxAccount(acme);
        var conversation = fixture.conversationWith(acme, ANA);
        var reply = fixture.operatorReplied(account, conversation, "Sí, hasta 12 cuotas");
        var payload = fixture.statusesTo(account.externalAccountId(),
                new StatusReport("sandbox-out-" + UUID.randomUUID(), MessageStatus.READ),
                new StatusReport(reply.externalId(), MessageStatus.DELIVERED));

        // When
        var receiving = assertThatCode(() -> pipeline.receive(ChannelType.SANDBOX, payload));

        // Then
        receiving.doesNotThrowAnyException();
        assertThat(fixture.messagesOf(acme)).containsExactly(reply.withStatus(MessageStatus.DELIVERED));
        assertThat(receivedMessages.in(acme)).isEmpty();
    }

    @Test
    void statusForUnknownChannelAccountIsDiscarded() {
        // Given
        var acme = channelAccounts.organization("Acme");
        var account = channelAccounts.sandboxAccount(acme);
        var conversation = fixture.conversationWith(acme, ANA);
        var reply = fixture.operatorReplied(account, conversation, "Sí, hasta 12 cuotas");
        var payload = fixture.statusesTo(channelAccounts.externalAccountId("sandbox"),
                new StatusReport(reply.externalId(), MessageStatus.READ));

        // When
        var receiving = assertThatCode(() -> pipeline.receive(ChannelType.SANDBOX, payload));

        // Then
        receiving.doesNotThrowAnyException();
        assertThat(fixture.messagesOf(acme)).containsExactly(reply);
        assertThat(receivedMessages.all()).isEmpty();
    }

    private static <T> T only(List<T> elements) {
        assertThat(elements).hasSize(1);
        return elements.getFirst();
    }

    private static UUID personWithPhone(List<StoredIdentity> identities, String phone) {
        return only(identities.stream().filter(identity -> identity.value().equals(phone)).toList()).personId();
    }

    private static StoredOpportunity opportunityOf(List<StoredOpportunity> opportunities, UUID personId) {
        return only(opportunities.stream().filter(opportunity -> opportunity.personId().equals(personId)).toList());
    }
}
