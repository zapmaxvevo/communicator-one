package com.communicatorone.channels;

import static org.assertj.core.api.Assertions.assertThat;

import com.communicatorone.channels.ChannelsFixture.SandboxAccount;
import com.communicatorone.messaging.ChannelType;
import com.communicatorone.messaging.MessageContent;
import com.communicatorone.messaging.MessageStatus;
import com.communicatorone.messaging.SenderType;
import com.communicatorone.messaging.SentMessage;
import com.communicatorone.messaging.Timeline;
import com.communicatorone.opportunities.OpportunityTracker;
import com.communicatorone.organizations.CurrentOrganization;
import com.communicatorone.people.ContactIdentity;
import com.communicatorone.people.IdentityKind;
import com.communicatorone.people.PersonDirectory;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class InboundPipelineFixture {

    static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ExecutorService background;
    private final CurrentOrganization currentOrganization;
    private final PersonDirectory people;
    private final OpportunityTracker opportunities;
    private final Timeline timeline;

    InboundPipelineFixture(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate,
            ExecutorService background, CurrentOrganization currentOrganization, PersonDirectory people,
            OpportunityTracker opportunities, Timeline timeline) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.background = background;
        this.currentOrganization = currentOrganization;
        this.people = people;
        this.opportunities = opportunities;
        this.timeline = timeline;
    }

    String messageId() {
        return "sandbox-in-" + UUID.randomUUID();
    }

    String unusedPhone() {
        return "+549116%07d".formatted(ThreadLocalRandom.current().nextInt(10_000_000));
    }

    WebhookPayload messagesTo(String externalAccountId, IncomingText... messages) {
        return webhook(externalAccountId, List.of(messages), List.of());
    }

    WebhookPayload statusesTo(String externalAccountId, StatusReport... statuses) {
        return webhook(externalAccountId, List.of(), List.of(statuses));
    }

    WebhookPayload whatsAppTextTo(String phoneNumberId) {
        var example = WhatsAppExamples.payload("text-message.json").body();
        assertThat(example).contains(WhatsAppExamples.PHONE_NUMBER_ID);
        return new WebhookPayload(example.replace(WhatsAppExamples.PHONE_NUMBER_ID, phoneNumberId));
    }

    Conversation conversationWith(UUID organizationId, String phone) {
        return currentOrganization.callAs(organizationId, () -> {
            var personId = people.resolveOrCreate(ContactIdentity.phone(phone));
            var opportunityId = opportunities.recordActivity(personId, ChannelType.SANDBOX.name());
            return new Conversation(personId, opportunityId);
        });
    }

    StoredMessage operatorReplied(SandboxAccount account, Conversation conversation, String text) {
        var externalId = "sandbox-out-" + UUID.randomUUID();
        var recorded = currentOrganization.callAs(account.organizationId(), () -> timeline.recordSent(
                new SentMessage(conversation.personId(), conversation.opportunityId(), account.id(),
                        ChannelType.SANDBOX, SenderType.OPERATOR, externalId, new MessageContent(text))));
        return messagesOf(account.organizationId()).stream()
                .filter(message -> message.id().equals(recorded.id()))
                .findFirst()
                .orElseThrow();
    }

    List<StoredIdentity> identitiesOf(UUID organizationId) {
        return jdbcTemplate.query("SELECT person_id, kind, value FROM person_identities WHERE organization_id = ?",
                (row, number) -> new StoredIdentity(row.getObject("person_id", UUID.class),
                        IdentityKind.valueOf(row.getString("kind")), row.getString("value")),
                organizationId);
    }

    List<StoredOpportunity> opportunitiesOf(UUID organizationId) {
        return jdbcTemplate.query("""
                SELECT id, person_id, source, status, result, opened_at, last_activity_at, closed_at
                FROM opportunities WHERE organization_id = ?
                ORDER BY opened_at
                """, (row, number) -> storedOpportunity(row), organizationId);
    }

    List<StoredMessage> messagesOf(UUID organizationId) {
        return jdbcTemplate.query("""
                SELECT id, person_id, opportunity_id, channel_account_id, channel, sender_type, external_id, text,
                    status, occurred_at, recorded_at
                FROM messages WHERE organization_id = ?
                ORDER BY occurred_at, recorded_at
                """, (row, number) -> storedMessage(row), organizationId);
    }

    int identitiesInAnyOrganizationWithValue(String value) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM person_identities WHERE value = ?", Integer.class,
                value);
    }

    int messagesInAnyOrganizationWithExternalId(String externalId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM messages WHERE external_id = ?", Integer.class,
                externalId);
    }

    RivalWrite rivalStartsRecordingMessage(SandboxAccount account, Conversation conversation, String externalId,
            String text, Instant occurredAt, Instant recordedAt) throws InterruptedException {
        var messageId = UUID.randomUUID();
        var written = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var transaction = background.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("""
                    INSERT INTO messages (id, organization_id, person_id, opportunity_id, channel_account_id,
                        channel, sender_type, external_id, text, status, occurred_at, recorded_at)
                    VALUES (?, ?, ?, ?, ?, 'SANDBOX', 'CUSTOMER', ?, ?, 'DELIVERED', ?, ?)
                    """, messageId, account.organizationId(), conversation.personId(),
                    conversation.opportunityId(), account.id(), externalId, text, utc(occurredAt), utc(recordedAt));
            written.countDown();
            awaitRelease(release);
        }));
        assertThat(written.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).as("rival wrote its message").isTrue();
        return new RivalWrite(messageId, release, transaction);
    }

    boolean anotherSessionIsWaitingToWriteMessages() {
        return jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM pg_stat_activity
                    WHERE datname = current_database()
                      AND pid <> pg_backend_pid()
                      AND wait_event_type = 'Lock'
                      AND query ILIKE '%insert into messages%')
                """, Boolean.class);
    }

    private static WebhookPayload webhook(String externalAccountId, List<IncomingText> messages,
            List<StatusReport> statuses) {
        return new WebhookPayload("""
                {
                  "account": "%s",
                  "messages": [%s],
                  "statuses": [%s]
                }
                """.formatted(externalAccountId,
                messages.stream().map(IncomingText::json).collect(Collectors.joining(",\n")),
                statuses.stream().map(StatusReport::json).collect(Collectors.joining(",\n"))));
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static StoredOpportunity storedOpportunity(ResultSet row) throws SQLException {
        return new StoredOpportunity(row.getObject("id", UUID.class), row.getObject("person_id", UUID.class),
                row.getString("source"), row.getString("status"), row.getString("result"),
                instantOf(row, "opened_at"), instantOf(row, "last_activity_at"), instantOf(row, "closed_at"));
    }

    private static StoredMessage storedMessage(ResultSet row) throws SQLException {
        return new StoredMessage(row.getObject("id", UUID.class), row.getObject("person_id", UUID.class),
                row.getObject("opportunity_id", UUID.class), row.getObject("channel_account_id", UUID.class),
                ChannelType.valueOf(row.getString("channel")), SenderType.valueOf(row.getString("sender_type")),
                row.getString("external_id"), row.getString("text"), MessageStatus.valueOf(row.getString("status")),
                instantOf(row, "occurred_at"), instantOf(row, "recorded_at"));
    }

    private static Instant instantOf(ResultSet row, String column) throws SQLException {
        var value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(TIMEOUT.multipliedBy(3).toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("The rival write was never released");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The rival write was interrupted", interrupted);
        }
    }

    record IncomingText(String id, String phone, String text, Instant sentAt) {

        String json() {
            return """
                    {"id": "%s", "from": {"kind": "PHONE", "value": "%s"}, "text": "%s", "sentAt": "%s"}"""
                    .formatted(id, phone, text, sentAt);
        }
    }

    record StatusReport(String messageId, MessageStatus status) {

        String json() {
            return """
                    {"messageId": "%s", "status": "%s"}""".formatted(messageId, status);
        }
    }

    record Conversation(UUID personId, UUID opportunityId) {
    }

    record StoredIdentity(UUID personId, IdentityKind kind, String value) {
    }

    record StoredOpportunity(UUID id, UUID personId, String source, String status, String result,
            Instant openedAt, Instant lastActivityAt, Instant closedAt) {

        static StoredOpportunity open(UUID id, UUID personId, Instant openedAt, Instant lastActivityAt) {
            return open(id, personId, ChannelType.SANDBOX, openedAt, lastActivityAt);
        }

        static StoredOpportunity open(UUID id, UUID personId, ChannelType source, Instant openedAt,
                Instant lastActivityAt) {
            return new StoredOpportunity(id, personId, source.name(), "OPEN", null, openedAt, lastActivityAt, null);
        }

        StoredOpportunity closedWithoutResponseAt(Instant closedAt) {
            return new StoredOpportunity(id, personId, source, "CLOSED", "NO_RESPONSE", openedAt, lastActivityAt,
                    closedAt);
        }
    }

    record StoredMessage(UUID id, UUID personId, UUID opportunityId, UUID channelAccountId, ChannelType channel,
            SenderType senderType, String externalId, String text, MessageStatus status, Instant occurredAt,
            Instant recordedAt) {

        static StoredMessage received(UUID id, UUID personId, UUID opportunityId, UUID channelAccountId,
                String externalId, String text, Instant occurredAt, Instant recordedAt) {
            return received(id, personId, opportunityId, channelAccountId, ChannelType.SANDBOX, externalId, text,
                    occurredAt, recordedAt);
        }

        static StoredMessage received(UUID id, UUID personId, UUID opportunityId, UUID channelAccountId,
                ChannelType channel, String externalId, String text, Instant occurredAt, Instant recordedAt) {
            return new StoredMessage(id, personId, opportunityId, channelAccountId, channel, SenderType.CUSTOMER,
                    externalId, text, MessageStatus.DELIVERED, occurredAt, recordedAt);
        }

        MessageReceived event(UUID organizationId) {
            return new MessageReceived(organizationId, id, personId, opportunityId, channelAccountId, channel);
        }

        StoredMessage withStatus(MessageStatus newStatus) {
            return new StoredMessage(id, personId, opportunityId, channelAccountId, channel, senderType, externalId,
                    text, newStatus, occurredAt, recordedAt);
        }
    }

    record RivalWrite(UUID messageId, CountDownLatch release, Future<?> transaction) {

        void commit() throws Exception {
            release.countDown();
            transaction.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }
    }
}
