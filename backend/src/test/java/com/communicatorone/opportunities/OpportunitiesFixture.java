package com.communicatorone.opportunities;

import static org.assertj.core.api.Assertions.assertThat;

import com.communicatorone.MutableClock;
import com.communicatorone.organizations.CurrentOrganization;
import com.communicatorone.people.ContactIdentity;
import com.communicatorone.people.PersonDirectory;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class OpportunitiesFixture {

    static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ExecutorService background;
    private final CurrentOrganization currentOrganization;
    private final PersonDirectory directory;

    OpportunitiesFixture(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate,
            ExecutorService background, CurrentOrganization currentOrganization, PersonDirectory directory) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.background = background;
        this.currentOrganization = currentOrganization;
        this.directory = directory;
    }

    UUID organization(String name) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO organizations (id, name) VALUES (?, ?)", id, name);
        return id;
    }

    OrganizationsInClosingOrder twoOrganizationsInClosingOrderWithAnOpenOpportunity(String source,
            Instant openedAt) {
        var inClosingOrder = Stream.of(organization("Acme"), organization("Globex"))
                .sorted(Comparator.comparing(UUID::toString))
                .map(organizationId -> withAnOpenOpportunity(organizationId, source, openedAt))
                .toList();
        return new OrganizationsInClosingOrder(inClosingOrder.getFirst(), inClosingOrder.getLast());
    }

    void settings(UUID organizationId, int inactivityDaysToClose, int reentryWindowDays) {
        jdbcTemplate.update("""
                INSERT INTO opportunity_settings (id, organization_id, inactivity_days_to_close, reentry_window_days)
                VALUES (?, ?, ?, ?)
                """, UUID.randomUUID(), organizationId, inactivityDaysToClose, reentryWindowDays);
    }

    UUID person(UUID organizationId) {
        var email = "person-" + UUID.randomUUID() + "@communicator-one.test";
        return currentOrganization.callAs(organizationId,
                () -> directory.resolveOrCreate(ContactIdentity.email(email)));
    }

    UUID operator(UUID organizationId) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO operators (id, organization_id, email, name, password_hash, role, availability)
                VALUES (?, ?, ?, ?, ?, 'OPERATOR', 'ONLINE')
                """, id, organizationId, "operator-" + id + "@communicator-one.test", "Operator", "not-a-real-hash");
        return id;
    }

    void assignOwner(UUID organizationId, UUID opportunityId, UUID operatorId) {
        jdbcTemplate.update("UPDATE opportunities SET owner_id = ? WHERE id = ? AND organization_id = ?",
                operatorId, opportunityId, organizationId);
    }

    List<StoredOpportunity> opportunitiesOf(UUID organizationId) {
        return jdbcTemplate.query("""
                SELECT id, person_id, owner_id, source, status, result, opened_at, last_activity_at, closed_at
                FROM opportunities WHERE organization_id = ?
                """, (row, number) -> storedOpportunity(row), organizationId);
    }

    int opportunitiesInAnyOrganizationOf(UUID personId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM opportunities WHERE person_id = ?", Integer.class,
                personId);
    }

    void closeWithoutResponse(UUID organizationId, UUID opportunityId, Instant closedAt) {
        jdbcTemplate.update("""
                UPDATE opportunities SET status = 'CLOSED', result = 'NO_RESPONSE', closed_at = ?
                WHERE id = ? AND organization_id = ?
                """, utc(closedAt), opportunityId, organizationId);
    }

    RivalOpening rivalStartsOpeningOpportunity(UUID organizationId, UUID personId, String source)
            throws InterruptedException {
        var opportunityId = UUID.randomUUID();
        return rivalHoldsUncommitted(opportunityId,
                () -> insertOpen(opportunityId, organizationId, personId, source, MutableClock.INITIAL_INSTANT));
    }

    RivalOpening rivalStartsReplacingOpportunity(UUID organizationId, UUID personId, UUID previousOpportunityId,
            Instant previousClosedAt, String source, Instant openedAt) throws InterruptedException {
        var opportunityId = UUID.randomUUID();
        return rivalHoldsUncommitted(opportunityId, () -> {
            closeWithoutResponse(organizationId, previousOpportunityId, previousClosedAt);
            insertOpen(opportunityId, organizationId, personId, source, openedAt);
        });
    }

    RivalOpening rivalStartsRecordingActivity(UUID organizationId, UUID opportunityId, Instant at)
            throws InterruptedException {
        return rivalHoldsUncommitted(opportunityId, () -> jdbcTemplate.update(
                "UPDATE opportunities SET last_activity_at = ? WHERE id = ? AND organization_id = ?", utc(at),
                opportunityId, organizationId));
    }

    AutoCloseable databaseRejectsClosingOpportunitiesOf(UUID organizationId) {
        jdbcTemplate.execute("""
                CREATE FUNCTION reject_closing_opportunities() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'Closing the opportunities of organization % is rejected', NEW.organization_id;
                END;
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER reject_closing_opportunities BEFORE UPDATE ON opportunities FOR EACH ROW
                WHEN (NEW.organization_id = '%s' AND NEW.status = 'CLOSED')
                EXECUTE FUNCTION reject_closing_opportunities()
                """.formatted(organizationId));
        return () -> {
            jdbcTemplate.execute("DROP TRIGGER reject_closing_opportunities ON opportunities");
            jdbcTemplate.execute("DROP FUNCTION reject_closing_opportunities()");
        };
    }

    boolean anotherSessionIsWaitingToWriteOpportunities() {
        return anotherSessionIsWaitingOn("%insert into opportunities%");
    }

    boolean anotherSessionIsWaitingToLockTheLatestOpportunity() {
        return anotherSessionIsWaitingOn("%from opportunities%for no key update%");
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

    private RivalOpening rivalHoldsUncommitted(UUID opportunityId, Runnable writes) throws InterruptedException {
        var written = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var transaction = background.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            writes.run();
            written.countDown();
            awaitRelease(release);
        }));
        assertThat(written.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).as("rival wrote its opportunity")
                .isTrue();
        return new RivalOpening(opportunityId, release, transaction);
    }

    private OrganizationWithOpportunity withAnOpenOpportunity(UUID organizationId, String source,
            Instant openedAt) {
        var personId = person(organizationId);
        var opportunityId = UUID.randomUUID();
        insertOpen(opportunityId, organizationId, personId, source, openedAt);
        return new OrganizationWithOpportunity(organizationId, personId, opportunityId);
    }

    private void insertOpen(UUID opportunityId, UUID organizationId, UUID personId, String source,
            Instant openedAt) {
        jdbcTemplate.update("""
                INSERT INTO opportunities (id, organization_id, person_id, source, status, opened_at,
                    last_activity_at)
                VALUES (?, ?, ?, ?, 'OPEN', ?, ?)
                """, opportunityId, organizationId, personId, source, utc(openedAt), utc(openedAt));
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static StoredOpportunity storedOpportunity(ResultSet row) throws SQLException {
        var result = row.getString("result");
        return new StoredOpportunity(row.getObject("id", UUID.class), row.getObject("person_id", UUID.class),
                row.getObject("owner_id", UUID.class), row.getString("source"),
                OpportunityStatus.valueOf(row.getString("status")),
                result == null ? null : OpportunityResult.valueOf(result),
                instantOf(row, "opened_at"), instantOf(row, "last_activity_at"), instantOf(row, "closed_at"));
    }

    private static Instant instantOf(ResultSet row, String column) throws SQLException {
        var value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(TIMEOUT.multipliedBy(3).toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("The rival opening was never released");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The rival opening was interrupted", interrupted);
        }
    }

    record StoredOpportunity(UUID id, UUID personId, UUID ownerId, String source, OpportunityStatus status,
            OpportunityResult result, Instant openedAt, Instant lastActivityAt, Instant closedAt) {

        static StoredOpportunity open(UUID id, UUID personId, String source, Instant openedAt,
                Instant lastActivityAt) {
            return new StoredOpportunity(id, personId, null, source, OpportunityStatus.OPEN, null, openedAt,
                    lastActivityAt, null);
        }

        StoredOpportunity closedWithoutResponseAt(Instant closedAt) {
            return new StoredOpportunity(id, personId, ownerId, source, OpportunityStatus.CLOSED,
                    OpportunityResult.NO_RESPONSE, openedAt, lastActivityAt, closedAt);
        }
    }

    record OrganizationWithOpportunity(UUID id, UUID personId, UUID opportunityId) {
    }

    record OrganizationsInClosingOrder(OrganizationWithOpportunity first, OrganizationWithOpportunity next) {
    }

    record RivalOpening(UUID opportunityId, CountDownLatch release, Future<?> transaction) {

        void commit() throws Exception {
            release.countDown();
            transaction.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }
    }
}
