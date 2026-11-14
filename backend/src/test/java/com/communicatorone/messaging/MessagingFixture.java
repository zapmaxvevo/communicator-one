package com.communicatorone.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.communicatorone.organizations.CurrentOrganization;
import com.communicatorone.opportunities.OpportunityTracker;
import com.communicatorone.people.ContactIdentity;
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
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class MessagingFixture {

    static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ExecutorService background;
    private final CurrentOrganization currentOrganization;
    private final PersonDirectory directory;
    private final OpportunityTracker tracker;

    MessagingFixture(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate, ExecutorService background,
            CurrentOrganization currentOrganization, PersonDirectory directory, OpportunityTracker tracker) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.background = background;
        this.currentOrganization = currentOrganization;
        this.directory = directory;
        this.tracker = tracker;
    }

    UUID organization(String name) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO organizations (id, name) VALUES (?, ?)", id, name);
        return id;
    }

    UUID person(UUID organizationId) {
        var email = "person-" + UUID.randomUUID() + "@communicator-one.test";
        return currentOrganization.callAs(organizationId,
                () -> directory.resolveOrCreate(ContactIdentity.email(email)));
    }

    UUID opportunity(UUID organizationId, UUID personId) {
        return currentOrganization.callAs(organizationId, () -> tracker.recordActivity(personId, "whatsapp"));
    }

    UUID channelAccount(UUID organizationId) {
        return UUID.randomUUID();
    }

    Conversation conversation(UUID organizationId) {
        var personId = person(organizationId);
        return new Conversation(organizationId, personId, opportunity(organizationId, personId),
                channelAccount(organizationId));
    }

    String externalId() {
        return "wamid." + UUID.randomUUID();
    }

    List<StoredMessage> messagesOf(UUID organizationId) {
        return jdbcTemplate.query("""
                SELECT id, person_id, opportunity_id, channel_account_id, channel, sender_type, external_id, text,
                    status, occurred_at, recorded_at
                FROM messages WHERE organization_id = ?
                """, (row, number) -> storedMessage(row), organizationId);
    }

    int messagesInAnyOrganizationWithExternalId(String externalId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM messages WHERE external_id = ?", Integer.class,
                externalId);
    }

    RivalWrite rivalStartsRecordingMessage(Conversation conversation, String externalId, String text,
            Instant occurredAt, Instant recordedAt) throws InterruptedException {
        var messageId = UUID.randomUUID();
        return rivalHoldsUncommitted(messageId, () -> jdbcTemplate.update("""
                INSERT INTO messages (id, organization_id, person_id, opportunity_id, channel_account_id, channel,
                    sender_type, external_id, text, status, occurred_at, recorded_at)
                VALUES (?, ?, ?, ?, ?, 'WHATSAPP', 'CUSTOMER', ?, ?, 'DELIVERED', ?, ?)
                """, messageId, conversation.organizationId(), conversation.personId(),
                conversation.opportunityId(), conversation.channelAccountId(), externalId, text, utc(occurredAt),
                utc(recordedAt)));
    }

    RivalWrite rivalStartsChangingStatus(UUID organizationId, UUID messageId, MessageStatus status)
            throws InterruptedException {
        return rivalHoldsUncommitted(messageId, () -> jdbcTemplate.update(
                "UPDATE messages SET status = ? WHERE id = ? AND organization_id = ?", status.name(), messageId,
                organizationId));
    }

    boolean anotherSessionIsWaitingToWriteMessages() {
        return anotherSessionIsWaitingOn("%insert into messages%");
    }

    boolean anotherSessionIsWaitingToLockTheMessage() {
        return anotherSessionIsWaitingOn("%from messages%for no key update%");
    }

    private boolean anotherSessionIsWaitingOn(String queryPattern) {
        return jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM pg_stat_activity
                    WHERE datname = current_database()
                      AND pid <> pg_backend_pid()
                      AND wait_event_type = 'Lock'
                      AND query ILIKE ?)
                """, Boolean.class, queryPattern);
    }

    private RivalWrite rivalHoldsUncommitted(UUID messageId, Runnable writes) throws InterruptedException {
        var written = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var transaction = background.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            writes.run();
            written.countDown();
            awaitRelease(release);
        }));
        assertThat(written.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).as("rival wrote its message").isTrue();
        return new RivalWrite(messageId, release, transaction);
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static StoredMessage storedMessage(ResultSet row) throws SQLException {
        return new StoredMessage(row.getObject("id", UUID.class), row.getObject("person_id", UUID.class),
                row.getObject("opportunity_id", UUID.class), row.getObject("channel_account_id", UUID.class),
                ChannelType.valueOf(row.getString("channel")), SenderType.valueOf(row.getString("sender_type")),
                row.getString("external_id"), row.getString("text"), MessageStatus.valueOf(row.getString("status")),
                row.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                row.getObject("recorded_at", OffsetDateTime.class).toInstant());
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

    record Conversation(UUID organizationId, UUID personId, UUID opportunityId, UUID channelAccountId) {

        Conversation onChannelAccount(UUID otherChannelAccountId) {
            return new Conversation(organizationId, personId, opportunityId, otherChannelAccountId);
        }

        Conversation withOpportunity(UUID otherOpportunityId) {
            return new Conversation(organizationId, personId, otherOpportunityId, channelAccountId);
        }

        Conversation withPerson(UUID otherPersonId) {
            return new Conversation(organizationId, otherPersonId, opportunityId, channelAccountId);
        }

        ReceivedMessage received(String externalId, String text, Instant occurredAt) {
            return new ReceivedMessage(personId, opportunityId, channelAccountId, ChannelType.WHATSAPP, externalId,
                    new MessageContent(text), occurredAt);
        }

        SentMessage sent(SenderType senderType, String externalId, String text) {
            return new SentMessage(personId, opportunityId, channelAccountId, ChannelType.WHATSAPP, senderType,
                    externalId, new MessageContent(text));
        }

        StoredMessage storedReceived(UUID id, String externalId, String text, Instant occurredAt,
                Instant recordedAt) {
            return new StoredMessage(id, personId, opportunityId, channelAccountId, ChannelType.WHATSAPP,
                    SenderType.CUSTOMER, externalId, text, MessageStatus.DELIVERED, occurredAt, recordedAt);
        }

        StoredMessage storedSent(UUID id, SenderType senderType, String externalId, String text,
                Instant recordedAt) {
            return new StoredMessage(id, personId, opportunityId, channelAccountId, ChannelType.WHATSAPP, senderType,
                    externalId, text, MessageStatus.SENT, recordedAt, recordedAt);
        }
    }

    record StoredMessage(UUID id, UUID personId, UUID opportunityId, UUID channelAccountId, ChannelType channel,
            SenderType senderType, String externalId, String text, MessageStatus status, Instant occurredAt,
            Instant recordedAt) {

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
