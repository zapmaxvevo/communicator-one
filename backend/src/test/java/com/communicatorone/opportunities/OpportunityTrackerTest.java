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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
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
class OpportunityTrackerTest {

    private static final Instant NOW = MutableClock.INITIAL_INSTANT;
    private static final Duration DEFAULT_INACTIVITY_PERIOD = Duration.ofDays(7);
    private static final Duration DEFAULT_REENTRY_WINDOW = Duration.ofDays(3);

    private final OpportunityTracker tracker;
    private final CurrentOrganization currentOrganization;
    private final TransactionTemplate transactionTemplate;
    private final MutableClock clock;
    private final ExecutorService background = Executors.newFixedThreadPool(2);
    private final OpportunitiesFixture fixture;

    OpportunityTrackerTest(OpportunityTracker tracker, CurrentOrganization currentOrganization,
            PersonDirectory directory, JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate,
            MutableClock clock) {
        this.tracker = tracker;
        this.currentOrganization = currentOrganization;
        this.transactionTemplate = transactionTemplate;
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
    void firstActivityOfAPersonOpensAnOpportunity() {
        // Given
        var acme = fixture.organization("Acme");
        var person = fixture.person(acme);

        // When
        var opportunityId = recordIn(acme, person, "whatsapp");

        // Then
        assertThat(fixture.opportunitiesOf(acme)).containsExactly(open(opportunityId, person, "whatsapp", NOW, NOW));
    }

    @Test
    void activityOfAPersonWithAnOpenOpportunityContinuesIt() {
        // Given
        var acme = fixture.organization("Acme");
        var person = fixture.person(acme);
        var openOpportunity = recordIn(acme, person, "whatsapp");
        clock.advance(Duration.ofDays(2));

        // When
        var opportunityId = recordIn(acme, person, "instagram");

        // Then
        assertThat(opportunityId).isEqualTo(openOpportunity);
        assertThat(fixture.opportunitiesOf(acme)).containsExactly(
                open(openOpportunity, person, "whatsapp", NOW, NOW.plus(Duration.ofDays(2))));
    }

    @Test
    void personReturningWithinTheReentryWindowGetsTheSameOpportunityBack() {
        // Given
        var acme = fixture.organization("Acme");
        var person = fixture.person(acme);
        var previousOpportunity = recordIn(acme, person, "whatsapp");
        var returnedAt = NOW.plus(DEFAULT_INACTIVITY_PERIOD).plus(DEFAULT_REENTRY_WINDOW).minusSeconds(1);
        clock.setTo(returnedAt);

        // When
        var opportunityId = recordIn(acme, person, "instagram");

        // Then
        assertThat(opportunityId).isEqualTo(previousOpportunity);
        assertThat(fixture.opportunitiesOf(acme)).containsExactly(
                open(previousOpportunity, person, "whatsapp", NOW, returnedAt));
    }

    @Test
    void personReturningAfterTheReentryWindowGetsANewOpportunity() {
        // Given
        var acme = fixture.organization("Acme");
        var person = fixture.person(acme);
        var previousOpportunity = recordIn(acme, person, "whatsapp");
        var returnedAt = NOW.plus(DEFAULT_INACTIVITY_PERIOD).plus(DEFAULT_REENTRY_WINDOW);
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
    void eachOrganizationAppliesItsOwnOpportunitySettings() {
        // Given
        var acme = fixture.organization("Acme");
        fixture.settings(acme, 1, 0);
        var globex = fixture.organization("Globex");
        var acmePerson = fixture.person(acme);
        var globexPerson = fixture.person(globex);
        var acmePreviousOpportunity = recordIn(acme, acmePerson, "whatsapp");
        var globexPreviousOpportunity = recordIn(globex, globexPerson, "whatsapp");
        var twoDaysLater = NOW.plus(Duration.ofDays(2));
        clock.setTo(twoDaysLater);

        // When
        var acmeOpportunity = recordIn(acme, acmePerson, "whatsapp");
        var globexOpportunity = recordIn(globex, globexPerson, "whatsapp");

        // Then
        assertThat(acmeOpportunity).isNotEqualTo(acmePreviousOpportunity);
        assertThat(globexOpportunity).isEqualTo(globexPreviousOpportunity);
        assertThat(fixture.opportunitiesOf(acme)).containsExactlyInAnyOrder(
                open(acmePreviousOpportunity, acmePerson, "whatsapp", NOW, NOW)
                        .closedWithoutResponseAt(NOW.plus(Duration.ofDays(1))),
                open(acmeOpportunity, acmePerson, "whatsapp", twoDaysLater, twoDaysLater));
        assertThat(fixture.opportunitiesOf(globex)).containsExactly(
                open(globexPreviousOpportunity, globexPerson, "whatsapp", NOW, twoDaysLater));
    }

    @Test
    void personOfAnotherOrganizationCannotRecordActivity(CapturedOutput output) {
        // Given
        var acme = fixture.organization("Acme");
        var globex = fixture.organization("Globex");
        var acmePerson = fixture.person(acme);

        // When
        var rejection = catchThrowable(() -> recordIn(globex, acmePerson, "whatsapp"));

        // Then
        assertThat(rejection).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(fixture.opportunitiesOf(globex)).isEmpty();
        assertThat(fixture.opportunitiesInAnyOrganizationOf(acmePerson)).isZero();
        assertThat(output.getAll()).contains("opportunities_person_id_organization_id_fkey");
    }

    @Test
    void openOpportunityOfAPersonIsNotContinuedFromAnotherOrganization() {
        // Given
        var acme = fixture.organization("Acme");
        var globex = fixture.organization("Globex");
        var acmePerson = fixture.person(acme);
        var acmeOpportunity = recordIn(acme, acmePerson, "whatsapp");
        clock.advance(Duration.ofHours(1));

        // When
        var rejection = catchThrowable(() -> recordIn(globex, acmePerson, "whatsapp"));

        // Then
        assertThat(rejection).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(fixture.opportunitiesOf(globex)).isEmpty();
        assertThat(fixture.opportunitiesOf(acme)).containsExactly(
                open(acmeOpportunity, acmePerson, "whatsapp", NOW, NOW));
    }

    @Test
    void activityCannotBeRecordedWithoutOrganization() {
        // Given
        var acme = fixture.organization("Acme");
        var person = fixture.person(acme);

        // When
        var rejection = catchThrowable(() -> tracker.recordActivity(person, "whatsapp"));

        // Then
        assertThat(rejection).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(fixture.opportunitiesInAnyOrganizationOf(person)).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = Propagation.class, names = {"REQUIRED", "SUPPORTS"})
    void activityCannotBeRecordedInsideAnOpenTransactionalScope(Propagation propagation) {
        // Given
        var acme = fixture.organization("Acme");
        var person = fixture.person(acme);
        var scope = new TransactionTemplate(transactionTemplate.getTransactionManager());
        scope.setPropagationBehavior(propagation.value());

        // When
        var rejection = catchThrowable(() -> currentOrganization.runAs(acme,
                () -> scope.executeWithoutResult(status -> tracker.recordActivity(person, "whatsapp"))));

        // Then
        assertThat(rejection).isInstanceOf(IllegalStateException.class);
        assertThat(fixture.opportunitiesOf(acme)).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void activityWithoutSourceIsRejected(String source) {
        // Given
        var acme = fixture.organization("Acme");
        var person = fixture.person(acme);

        // When
        var rejection = catchThrowable(() -> recordIn(acme, person, source));

        // Then
        assertThat(rejection).isInstanceOf(IllegalArgumentException.class);
        assertThat(fixture.opportunitiesOf(acme)).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void activityWithoutSourceIsRejectedEvenWhileThePersonHasAnOpenOpportunity(String source) {
        // Given
        var acme = fixture.organization("Acme");
        var person = fixture.person(acme);
        var openOpportunity = recordIn(acme, person, "whatsapp");
        clock.advance(Duration.ofHours(1));

        // When
        var rejection = catchThrowable(() -> recordIn(acme, person, source));

        // Then
        assertThat(rejection).isInstanceOf(IllegalArgumentException.class);
        assertThat(fixture.opportunitiesOf(acme)).containsExactly(
                open(openOpportunity, person, "whatsapp", NOW, NOW));
    }

    @Test
    void concurrentFirstActivitiesOfAPersonOpenASingleOpportunity(CapturedOutput output) throws Exception {
        // Given
        var acme = fixture.organization("Acme");
        var person = fixture.person(acme);
        var rival = fixture.rivalStartsOpeningOpportunity(acme, person, "instagram");

        // When
        var activity = CompletableFuture.supplyAsync(() -> recordIn(acme, person, "whatsapp"), background);
        await().atMost(TIMEOUT)
                .failFast(() -> assertThat(activity).as("activity waiting for the rival").isNotDone())
                .until(fixture::anotherSessionIsWaitingToWriteOpportunities);
        rival.commit();
        var opportunityId = activity.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

        // Then
        assertThat(opportunityId).isEqualTo(rival.opportunityId());
        assertThat(fixture.opportunitiesOf(acme)).containsExactly(
                open(rival.opportunityId(), person, "instagram", NOW, NOW));
        assertThat(output.getAll()).contains("opportunities_person_id_open_key");
    }

    @Test
    void opportunityOwnerMustBeAnOperatorOfTheSameOrganization() {
        // Given
        var acme = fixture.organization("Acme");
        var globex = fixture.organization("Globex");
        var person = fixture.person(acme);
        var opportunityId = recordIn(acme, person, "whatsapp");
        var globexOperator = fixture.operator(globex);

        // When
        var rejection = catchThrowable(() -> fixture.assignOwner(acme, opportunityId, globexOperator));

        // Then
        assertThat(rejection).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(fixture.opportunitiesOf(acme)).containsExactly(open(opportunityId, person, "whatsapp", NOW, NOW));
    }

    private UUID recordIn(UUID organizationId, UUID personId, String source) {
        return currentOrganization.callAs(organizationId, () -> tracker.recordActivity(personId, source));
    }
}
