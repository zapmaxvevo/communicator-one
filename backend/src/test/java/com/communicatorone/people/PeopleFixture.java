package com.communicatorone.people;

import static org.assertj.core.api.Assertions.assertThat;

import com.communicatorone.MutableClock;
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

class PeopleFixture {

    static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ExecutorService background;

    PeopleFixture(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate, ExecutorService background) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.background = background;
    }

    UUID organization(String name) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO organizations (id, name) VALUES (?, ?)", id, name);
        return id;
    }

    List<StoredPerson> peopleOf(UUID organizationId) {
        return jdbcTemplate.query("SELECT id, created_at FROM people WHERE organization_id = ?",
                (row, number) -> new StoredPerson(row.getObject("id", UUID.class),
                        row.getObject("created_at", OffsetDateTime.class).toInstant()),
                organizationId);
    }

    List<StoredIdentity> identitiesOf(UUID organizationId) {
        return jdbcTemplate.query("SELECT person_id, kind, value FROM person_identities WHERE organization_id = ?",
                (row, number) -> new StoredIdentity(row.getObject("person_id", UUID.class),
                        IdentityKind.valueOf(row.getString("kind")), row.getString("value")),
                organizationId);
    }

    int identitiesInAnyOrganizationWithValue(String value) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM person_identities WHERE value = ?", Integer.class,
                value);
    }

    RivalCreation rivalStartsCreatingPerson(UUID organizationId, IdentityKind kind, String normalizedValue)
            throws InterruptedException {
        var personId = UUID.randomUUID();
        var inserted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var transaction = background.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("INSERT INTO people (id, organization_id, created_at) VALUES (?, ?, ?)",
                    personId, organizationId, OffsetDateTime.ofInstant(MutableClock.INITIAL_INSTANT, ZoneOffset.UTC));
            jdbcTemplate.update(
                    "INSERT INTO person_identities (id, organization_id, person_id, kind, value) VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID(), organizationId, personId, kind.name(), normalizedValue);
            inserted.countDown();
            awaitRelease(release);
        }));
        assertThat(inserted.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).as("rival inserted its person").isTrue();
        return new RivalCreation(personId, release, transaction);
    }

    boolean anotherSessionIsWaitingToWriteIdentities() {
        return jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM pg_stat_activity
                    WHERE datname = current_database()
                      AND pid <> pg_backend_pid()
                      AND wait_event_type = 'Lock'
                      AND query ILIKE '%person_identities%')
                """, Boolean.class);
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(TIMEOUT.multipliedBy(3).toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("The rival creation was never released");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The rival creation was interrupted", interrupted);
        }
    }

    record StoredPerson(UUID id, Instant createdAt) {
    }

    record StoredIdentity(UUID personId, IdentityKind kind, String value) {
    }

    record RivalCreation(UUID personId, CountDownLatch release, Future<?> transaction) {

        void commit() throws Exception {
            release.countDown();
            transaction.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }
    }
}
