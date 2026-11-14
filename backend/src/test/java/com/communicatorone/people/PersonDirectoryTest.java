package com.communicatorone.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static com.communicatorone.people.PeopleFixture.TIMEOUT;
import static org.awaitility.Awaitility.await;

import com.communicatorone.MutableClock;
import com.communicatorone.TestcontainersConfiguration;
import com.communicatorone.organizations.CurrentOrganization;
import com.communicatorone.people.PeopleFixture.StoredIdentity;
import com.communicatorone.people.PeopleFixture.StoredPerson;
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
class PersonDirectoryTest {

    private static final Instant NOW = MutableClock.INITIAL_INSTANT;

    private final PersonDirectory directory;
    private final CurrentOrganization currentOrganization;
    private final TransactionTemplate transactionTemplate;
    private final MutableClock clock;
    private final ExecutorService background = Executors.newFixedThreadPool(2);
    private final PeopleFixture fixture;

    PersonDirectoryTest(PersonDirectory directory, CurrentOrganization currentOrganization,
            JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate, MutableClock clock) {
        this.directory = directory;
        this.currentOrganization = currentOrganization;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.fixture = new PeopleFixture(jdbcTemplate, transactionTemplate, background);
    }

    @BeforeEach
    void fixTheClock() {
        clock.setTo(NOW);
    }

    @AfterEach
    void stopBackgroundWork() throws InterruptedException {
        background.shutdownNow();
        assertThat(background.awaitTermination(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void unknownIdentityCreatesANewPerson() {
        // Given
        var acme = fixture.organization("Acme");

        // When
        var personId = resolveIn(acme, ContactIdentity.phone("+54 9 11 5555-0101"));

        // Then
        assertOnlyPerson(acme, personId, IdentityKind.PHONE, "+5491155550101");
    }

    @Test
    void knownIdentityWrittenDifferentlyResolvesToTheSamePerson() {
        // Given
        var acme = fixture.organization("Acme");
        var knownPerson = resolveIn(acme, ContactIdentity.phone("+54 9 11 5555-0101"));

        // When
        var resolvedPerson = resolveIn(acme, ContactIdentity.phone(" +54 (9 11) 5555 0101 "));

        // Then
        assertThat(resolvedPerson).isEqualTo(knownPerson);
        assertOnlyPerson(acme, knownPerson, IdentityKind.PHONE, "+5491155550101");
    }

    @Test
    void knownEmailResolvesToTheSamePersonRegardlessOfCase() {
        // Given
        var acme = fixture.organization("Acme");
        var knownPerson = resolveIn(acme, ContactIdentity.email("Ana.Perez@Acme.test"));

        // When
        var resolvedPerson = resolveIn(acme, ContactIdentity.email(" ana.perez@ACME.TEST "));

        // Then
        assertThat(resolvedPerson).isEqualTo(knownPerson);
        assertOnlyPerson(acme, knownPerson, IdentityKind.EMAIL, "ana.perez@acme.test");
    }

    @Test
    void sameExternalIdOnDifferentChannelsBelongsToDifferentPeople() {
        // Given
        var acme = fixture.organization("Acme");
        var telegramPerson = resolveIn(acme, new ContactIdentity(IdentityKind.TELEGRAM_USER, "12345"));

        // When
        var messengerPerson = resolveIn(acme, new ContactIdentity(IdentityKind.MESSENGER_USER, "12345"));

        // Then
        assertThat(messengerPerson).isNotEqualTo(telegramPerson);
        assertThat(fixture.peopleOf(acme)).containsExactlyInAnyOrder(
                new StoredPerson(telegramPerson, NOW),
                new StoredPerson(messengerPerson, NOW));
        assertThat(fixture.identitiesOf(acme)).containsExactlyInAnyOrder(
                new StoredIdentity(telegramPerson, IdentityKind.TELEGRAM_USER, "12345"),
                new StoredIdentity(messengerPerson, IdentityKind.MESSENGER_USER, "12345"));
    }

    @Test
    void sameIdentityInAnotherOrganizationResolvesToADifferentPerson() {
        // Given
        var acme = fixture.organization("Acme");
        var globex = fixture.organization("Globex");
        var acmePerson = resolveIn(acme, ContactIdentity.phone("+54 9 11 5555-0101"));

        // When
        var globexPerson = resolveIn(globex, ContactIdentity.phone("+5491155550101"));
        var acmePersonAgain = resolveIn(acme, ContactIdentity.phone("+5491155550101"));

        // Then
        assertThat(globexPerson).isNotEqualTo(acmePerson);
        assertThat(acmePersonAgain).isEqualTo(acmePerson);
        assertOnlyPerson(acme, acmePerson, IdentityKind.PHONE, "+5491155550101");
        assertOnlyPerson(globex, globexPerson, IdentityKind.PHONE, "+5491155550101");
    }

    @Test
    void concurrentResolutionOfTheSameNewIdentityCreatesASinglePerson(CapturedOutput output) throws Exception {
        // Given
        var acme = fixture.organization("Acme");
        var rival = fixture.rivalStartsCreatingPerson(acme, IdentityKind.PHONE, "+5491155550101");

        // When
        var resolution = CompletableFuture.supplyAsync(
                () -> resolveIn(acme, ContactIdentity.phone("+54 9 11 5555-0101")), background);
        await().atMost(TIMEOUT)
                .failFast(() -> assertThat(resolution).as("resolution waiting for the rival").isNotDone())
                .until(fixture::anotherSessionIsWaitingToWriteIdentities);
        rival.commit();
        var resolvedPerson = resolution.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

        // Then
        assertThat(resolvedPerson).isEqualTo(rival.personId());
        assertOnlyPerson(acme, rival.personId(), IdentityKind.PHONE, "+5491155550101");
        assertThat(output.getAll())
                .contains("person_identities_organization_id_kind_value_key")
                .doesNotContain("5491155550101");
    }

    @Test
    void identityCannotBeResolvedWithoutOrganization() {
        // Given
        var email = "nobody-" + UUID.randomUUID() + "@acme.test";

        // When
        var rejection = catchThrowable(() -> directory.resolveOrCreate(ContactIdentity.email(email)));

        // Then
        assertThat(rejection).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(fixture.identitiesInAnyOrganizationWithValue(email)).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = Propagation.class, names = {"REQUIRED", "SUPPORTS"})
    void identityCannotBeResolvedInsideAnOpenTransactionalScope(Propagation propagation) {
        // Given
        var acme = fixture.organization("Acme");
        var scope = new TransactionTemplate(transactionTemplate.getTransactionManager());
        scope.setPropagationBehavior(propagation.value());

        // When
        var rejection = catchThrowable(() -> currentOrganization.runAs(acme,
                () -> scope.executeWithoutResult(
                        status -> directory.resolveOrCreate(ContactIdentity.phone("+54 9 11 5555-0101")))));

        // Then
        assertThat(rejection).isInstanceOf(IllegalStateException.class);
        assertThat(fixture.peopleOf(acme)).isEmpty();
        assertThat(fixture.identitiesOf(acme)).isEmpty();
    }

    private UUID resolveIn(UUID organizationId, ContactIdentity identity) {
        return currentOrganization.callAs(organizationId, () -> directory.resolveOrCreate(identity));
    }

    private void assertOnlyPerson(UUID organizationId, UUID personId, IdentityKind kind, String value) {
        assertThat(fixture.peopleOf(organizationId)).containsExactly(new StoredPerson(personId, NOW));
        assertThat(fixture.identitiesOf(organizationId)).containsExactly(new StoredIdentity(personId, kind, value));
    }
}
