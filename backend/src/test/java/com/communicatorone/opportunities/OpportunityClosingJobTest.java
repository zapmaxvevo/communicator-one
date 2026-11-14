package com.communicatorone.opportunities;

import static com.communicatorone.opportunities.OpportunitiesFixture.StoredOpportunity.open;
import static com.communicatorone.opportunities.OpportunitiesFixture.TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;

import com.communicatorone.MutableClock;
import com.communicatorone.TestcontainersConfiguration;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.TestConstructor;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@ExtendWith(OutputCaptureExtension.class)
class OpportunityClosingJobTest {

    private static final Instant NOW = MutableClock.INITIAL_INSTANT;
    private static final Duration DEFAULT_INACTIVITY_PERIOD = Duration.ofDays(7);

    private final OpportunityClosingJob job;
    private final OpportunityTracker tracker;
    private final CurrentOrganization currentOrganization;
    private final ObjectProvider<ScheduledTaskHolder> scheduledTaskHolders;
    private final MutableClock clock;
    private final ExecutorService background = Executors.newFixedThreadPool(2);
    private final OpportunitiesFixture fixture;

    OpportunityClosingJobTest(OpportunityClosingJob job, OpportunityTracker tracker,
            CurrentOrganization currentOrganization, PersonDirectory directory, JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate, ObjectProvider<ScheduledTaskHolder> scheduledTaskHolders,
            MutableClock clock) {
        this.job = job;
        this.tracker = tracker;
        this.currentOrganization = currentOrganization;
        this.scheduledTaskHolders = scheduledTaskHolders;
        this.clock = clock;
        this.fixture = new OpportunitiesFixture(jdbcTemplate, transactionTemplate, background, currentOrganization,
                directory);
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
    void jobClosesInactiveOpportunitiesDatedAtTheEndOfTheirInactivity() {
        // Given
        var acme = fixture.organization("Acme");
        var person = fixture.person(acme);
        var opportunityId = recordIn(acme, person, "whatsapp");
        clock.setTo(NOW.plus(DEFAULT_INACTIVITY_PERIOD).plus(Duration.ofHours(3)));

        // When
        job.closeInactiveOpportunitiesOfEveryOrganization();

        // Then
        assertThat(fixture.opportunitiesOf(acme)).containsExactly(
                open(opportunityId, person, "whatsapp", NOW, NOW)
                        .closedWithoutResponseAt(NOW.plus(DEFAULT_INACTIVITY_PERIOD)));
    }

    @Test
    void opportunityReachingTheEndOfItsInactivityIsClosedByTheJob() {
        // Given
        var acme = fixture.organization("Acme");
        var person = fixture.person(acme);
        var opportunityId = recordIn(acme, person, "whatsapp");
        clock.setTo(NOW.plus(DEFAULT_INACTIVITY_PERIOD));

        // When
        job.closeInactiveOpportunitiesOfEveryOrganization();

        // Then
        assertThat(fixture.opportunitiesOf(acme)).containsExactly(
                open(opportunityId, person, "whatsapp", NOW, NOW)
                        .closedWithoutResponseAt(NOW.plus(DEFAULT_INACTIVITY_PERIOD)));
    }

    @Test
    void opportunityOneSecondBeforeTheEndOfItsInactivityIsLeftOpenByTheJob() {
        // Given
        var acme = fixture.organization("Acme");
        var person = fixture.person(acme);
        var opportunityId = recordIn(acme, person, "whatsapp");
        clock.setTo(NOW.plus(DEFAULT_INACTIVITY_PERIOD).minusSeconds(1));

        // When
        job.closeInactiveOpportunitiesOfEveryOrganization();

        // Then
        assertThat(fixture.opportunitiesOf(acme)).containsExactly(open(opportunityId, person, "whatsapp", NOW, NOW));
    }

    @Test
    void jobAppliesTheInactivityPeriodOfEachOrganization() {
        // Given
        var acme = fixture.organization("Acme");
        fixture.settings(acme, 1, 0);
        var globex = fixture.organization("Globex");
        var acmePerson = fixture.person(acme);
        var globexPerson = fixture.person(globex);
        var acmeOpportunity = recordIn(acme, acmePerson, "whatsapp");
        var globexOpportunity = recordIn(globex, globexPerson, "whatsapp");
        clock.setTo(NOW.plus(Duration.ofDays(2)));

        // When
        job.closeInactiveOpportunitiesOfEveryOrganization();

        // Then
        assertThat(fixture.opportunitiesOf(acme)).containsExactly(
                open(acmeOpportunity, acmePerson, "whatsapp", NOW, NOW)
                        .closedWithoutResponseAt(NOW.plus(Duration.ofDays(1))));
        assertThat(fixture.opportunitiesOf(globex)).containsExactly(
                open(globexOpportunity, globexPerson, "whatsapp", NOW, NOW));
    }

    @Test
    void runningTheJobAgainLeavesClosedOpportunitiesAsTheyWere() {
        // Given
        var acme = fixture.organization("Acme");
        var person = fixture.person(acme);
        var opportunityId = recordIn(acme, person, "whatsapp");
        clock.setTo(NOW.plus(Duration.ofDays(8)));
        job.closeInactiveOpportunitiesOfEveryOrganization();
        clock.setTo(NOW.plus(Duration.ofDays(20)));

        // When
        job.closeInactiveOpportunitiesOfEveryOrganization();

        // Then
        assertThat(fixture.opportunitiesOf(acme)).containsExactly(
                open(opportunityId, person, "whatsapp", NOW, NOW)
                        .closedWithoutResponseAt(NOW.plus(DEFAULT_INACTIVITY_PERIOD)));
    }

    @Test
    void reentryWindowAfterALateJobRunIsMeasuredFromTheEndOfTheInactivity() {
        // Given
        var acme = fixture.organization("Acme");
        var person = fixture.person(acme);
        var previousOpportunity = recordIn(acme, person, "whatsapp");
        clock.setTo(NOW.plus(Duration.ofDays(9)));
        job.closeInactiveOpportunitiesOfEveryOrganization();
        var returnedAt = NOW.plus(Duration.ofDays(10));
        clock.setTo(returnedAt);

        // When
        var opportunityId = recordIn(acme, person, "instagram");

        // Then
        assertThat(opportunityId).isNotEqualTo(previousOpportunity);
        assertThat(fixture.opportunitiesOf(acme)).containsExactlyInAnyOrder(
                open(previousOpportunity, person, "whatsapp", NOW, NOW)
                        .closedWithoutResponseAt(NOW.plus(DEFAULT_INACTIVITY_PERIOD)),
                open(opportunityId, person, "instagram", returnedAt, returnedAt));
    }

    @Test
    void activityInProgressWhileTheJobRunsKeepsTheOpportunityOpen(CapturedOutput output) throws Exception {
        // Given
        var acme = fixture.organization("Acme");
        var person = fixture.person(acme);
        var opportunityId = recordIn(acme, person, "whatsapp");
        var activityAt = NOW.plus(Duration.ofDays(8));
        clock.setTo(activityAt);
        var rival = fixture.rivalStartsRecordingActivity(acme, opportunityId, activityAt);

        // When
        var closing = CompletableFuture.runAsync(job::closeInactiveOpportunitiesOfEveryOrganization, background);
        await().atMost(TIMEOUT)
                .failFast(() -> assertThat(closing).as("job waiting for the activity in progress").isNotDone())
                .until(fixture::anotherSessionIsWaitingToLockTheLatestOpportunity);
        rival.commit();
        closing.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

        // Then
        assertThat(fixture.opportunitiesOf(acme)).containsExactly(
                open(opportunityId, person, "whatsapp", NOW, activityAt));
        assertThat(output.getAll()).doesNotContain(
                "Inactive opportunities of organization " + acme + " could not be closed");
    }

    @Test
    void failureClosingTheOpportunitiesOfOneOrganizationDoesNotStopTheOthers(CapturedOutput output)
            throws Exception {
        // Given
        var inClosingOrder = fixture.twoOrganizationsInClosingOrderWithAnOpenOpportunity("whatsapp", NOW);
        var firstInLine = inClosingOrder.first();
        var nextInLine = inClosingOrder.next();
        clock.setTo(NOW.plus(DEFAULT_INACTIVITY_PERIOD).plus(Duration.ofHours(3)));

        // When
        Throwable failure;
        try (var _ = fixture.databaseRejectsClosingOpportunitiesOf(firstInLine.id())) {
            failure = catchThrowable(job::closeInactiveOpportunitiesOfEveryOrganization);
        }

        // Then
        assertThat(failure).isNull();
        assertThat(fixture.opportunitiesOf(firstInLine.id())).containsExactly(
                open(firstInLine.opportunityId(), firstInLine.personId(), "whatsapp", NOW, NOW));
        assertThat(fixture.opportunitiesOf(nextInLine.id())).containsExactly(
                open(nextInLine.opportunityId(), nextInLine.personId(), "whatsapp", NOW, NOW)
                        .closedWithoutResponseAt(NOW.plus(DEFAULT_INACTIVITY_PERIOD)));
        assertThat(output.getAll().lines()).anySatisfy(
                line -> assertThat(line).contains("WARN", firstInLine.id().toString()));
        assertThat(output.getAll()).contains("Closing the opportunities of organization " + firstInLine.id()
                + " is rejected");
    }

    @Test
    void organizationWhoseInactiveOpportunityStaysLockedIsSkippedWithoutHoldingUpTheJob(CapturedOutput output)
            throws Exception {
        // Given
        var inClosingOrder = fixture.twoOrganizationsInClosingOrderWithAnOpenOpportunity("whatsapp", NOW);
        var lockedInLine = inClosingOrder.first();
        var nextInLine = inClosingOrder.next();
        var activityAt = NOW.plus(DEFAULT_INACTIVITY_PERIOD).plus(Duration.ofHours(3));
        clock.setTo(activityAt);
        var rival = fixture.rivalStartsRecordingActivity(lockedInLine.id(), lockedInLine.opportunityId(),
                activityAt);

        // When
        var closing = CompletableFuture.runAsync(job::closeInactiveOpportunitiesOfEveryOrganization, background);
        assertThat(closing).as("job finishing while the rival holds the lock").succeedsWithin(TIMEOUT);
        rival.commit();

        // Then
        assertThat(fixture.opportunitiesOf(lockedInLine.id())).containsExactly(
                open(lockedInLine.opportunityId(), lockedInLine.personId(), "whatsapp", NOW, activityAt));
        assertThat(fixture.opportunitiesOf(nextInLine.id())).containsExactly(
                open(nextInLine.opportunityId(), nextInLine.personId(), "whatsapp", NOW, NOW)
                        .closedWithoutResponseAt(NOW.plus(DEFAULT_INACTIVITY_PERIOD)));
        assertThat(output.getAll().lines()).anySatisfy(line -> assertThat(line).contains("WARN",
                "Inactive opportunities of organization " + lockedInLine.id() + " could not be closed"));
    }

    @Test
    void closingJobDoesNotRunOnItsOwnScheduleDuringTests() {
        // When
        var scheduledTasks = scheduledTaskHolders.stream()
                .flatMap(holder -> holder.getScheduledTasks().stream())
                .toList();

        // Then
        assertThat(scheduledTaskHolders.stream()).as("scheduling enabled").isNotEmpty();
        assertThat(scheduledTasks).noneMatch(OpportunityClosingJobTest::runsTheClosingJob);
    }

    private static boolean runsTheClosingJob(ScheduledTask task) {
        return task.toString().startsWith(OpportunityClosingJob.class.getName() + ".");
    }

    private UUID recordIn(UUID organizationId, UUID personId, String source) {
        return currentOrganization.callAs(organizationId, () -> tracker.recordActivity(personId, source));
    }
}
