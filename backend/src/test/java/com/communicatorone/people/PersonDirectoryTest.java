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
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
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
    void unknownIdentityResolvedWithANameCreatesAPersonWithThatName() {
        // Given
        var acme = fixture.organization("Acme");

        // When
        var personId = resolveIn(acme, ContactIdentity.phone("+54 9 11 5555-0101"), "Ana Pérez");

        // Then
        assertOnlyPerson(acme, personId, "Ana Pérez", IdentityKind.PHONE, "+5491155550101");
    }

    @Test
    void unknownIdentityResolvedWithoutNameCreatesAnUnnamedPerson() {
        // Given
        var acme = fixture.organization("Acme");

        // When
        var personId = resolveIn(acme, ContactIdentity.phone("+54 9 11 5555-0101"), null);

        // Then
        assertOnlyPerson(acme, personId, null, IdentityKind.PHONE, "+5491155550101");
    }

    @Test
    void knownPersonKeepsHerNameWhenResolvedWithAnotherName() {
        // Given
        var acme = fixture.organization("Acme");
        var knownPerson = resolveIn(acme, ContactIdentity.phone("+54 9 11 5555-0101"), "Ana Pérez");

        // When
        var resolvedPerson = resolveIn(acme, ContactIdentity.phone("+5491155550101"), "Ana María Pérez");

        // Then
        assertThat(resolvedPerson).isEqualTo(knownPerson);
        assertOnlyPerson(acme, knownPerson, "Ana Pérez", IdentityKind.PHONE, "+5491155550101");
    }

    @Test
    void knownPersonKeepsHerNameWhenResolvedWithoutName() {
        // Given
        var acme = fixture.organization("Acme");
        var knownPerson = resolveIn(acme, ContactIdentity.phone("+54 9 11 5555-0101"), "Ana Pérez");

        // When
        var resolvedPerson = resolveIn(acme, ContactIdentity.phone("+5491155550101"), null);

        // Then
        assertThat(resolvedPerson).isEqualTo(knownPerson);
        assertOnlyPerson(acme, knownPerson, "Ana Pérez", IdentityKind.PHONE, "+5491155550101");
    }

    @Test
    void knownUnnamedPersonStaysUnnamedWhenResolvedWithAName() {
        // Given
        var acme = fixture.organization("Acme");
        var knownPerson = resolveIn(acme, ContactIdentity.phone("+54 9 11 5555-0101"));

        // When
        var resolvedPerson = resolveIn(acme, ContactIdentity.phone("+5491155550101"), "Ana Pérez");

        // Then
        assertThat(resolvedPerson).isEqualTo(knownPerson);
        assertOnlyPerson(acme, knownPerson, null, IdentityKind.PHONE, "+5491155550101");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("namesToClean")
    void newPersonNameIsTrimmedAndCleanedOfLineBreaksAndControlCharacters(String rawName, String storedName) {
        // Given
        var acme = fixture.organization("Acme");

        // When
        var personId = resolveIn(acme, ContactIdentity.phone("+54 9 11 5555-0101"), rawName);

        // Then
        assertOnlyPerson(acme, personId, storedName, IdentityKind.PHONE, "+5491155550101");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\n\t", "\u0000", "\u2028 \r\n", "\u00A0", "\u2007\u202F\u00A0"})
    void blankNameLeavesTheNewPersonUnnamed(String blankName) {
        // Given
        var acme = fixture.organization("Acme");

        // When
        var personId = resolveIn(acme, ContactIdentity.phone("+54 9 11 5555-0101"), blankName);

        // Then
        assertOnlyPerson(acme, personId, null, IdentityKind.PHONE, "+5491155550101");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("overlongNames")
    void overlongNameIsCutAtTwoHundredCharactersWithoutSplittingAnEmoji(String overlongName, String storedName) {
        // Given
        var acme = fixture.organization("Acme");

        // When
        var personId = resolveIn(acme, ContactIdentity.phone("+54 9 11 5555-0101"), overlongName);

        // Then
        assertOnlyPerson(acme, personId, storedName, IdentityKind.PHONE, "+5491155550101");
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
                new StoredPerson(telegramPerson, NOW, null),
                new StoredPerson(messengerPerson, NOW, null));
        assertThat(fixture.identitiesOf(acme)).containsExactlyInAnyOrder(
                new StoredIdentity(telegramPerson, IdentityKind.TELEGRAM_USER, "12345"),
                new StoredIdentity(messengerPerson, IdentityKind.MESSENGER_USER, "12345"));
    }

    @Test
    void sameIdentityInAnotherOrganizationResolvesToADifferentPerson() {
        // Given
        var acme = fixture.organization("Acme");
        var globex = fixture.organization("Globex");
        var acmePerson = resolveIn(acme, ContactIdentity.phone("+54 9 11 5555-0101"), "Ana Pérez");

        // When
        var globexPerson = resolveIn(globex, ContactIdentity.phone("+5491155550101"), "Anita");
        var acmePersonAgain = resolveIn(acme, ContactIdentity.phone("+5491155550101"));

        // Then
        assertThat(globexPerson).isNotEqualTo(acmePerson);
        assertThat(acmePersonAgain).isEqualTo(acmePerson);
        assertOnlyPerson(acme, acmePerson, "Ana Pérez", IdentityKind.PHONE, "+5491155550101");
        assertOnlyPerson(globex, globexPerson, "Anita", IdentityKind.PHONE, "+5491155550101");
    }

    @Test
    void concurrentResolutionOfTheSameNewIdentityCreatesASinglePerson(CapturedOutput output) throws Exception {
        // Given
        var acme = fixture.organization("Acme");
        var rival = fixture.rivalStartsCreatingPerson(acme, IdentityKind.PHONE, "+5491155550101");

        // When
        var resolution = CompletableFuture.supplyAsync(
                () -> resolveIn(acme, ContactIdentity.phone("+54 9 11 5555-0101"), "Ana Pérez"), background);
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
                .doesNotContain("5491155550101")
                .doesNotContain("Ana Pérez");
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

    @Test
    void identityCannotBelongToAPersonOfAnotherOrganization() {
        // Given
        var acme = fixture.organization("Acme");
        var globex = fixture.organization("Globex");
        var globexPerson = resolveIn(globex, ContactIdentity.phone("+54 9 11 5555-0101"));

        // When
        var rejection = catchThrowable(
                () -> fixture.insertIdentity(acme, globexPerson, IdentityKind.EMAIL, "ana.perez@acme.test"));

        // Then
        assertThat(rejection).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(fixture.identitiesOf(acme)).isEmpty();
        assertOnlyPerson(globex, globexPerson, IdentityKind.PHONE, "+5491155550101");
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

    static Stream<Arguments> namesToClean() {
        return Stream.of(
                Arguments.of(Named.of("surrounding spaces", "  Ana Pérez  "), "Ana Pérez"),
                Arguments.of(Named.of("line break", "Ana\nPérez"), "Ana Pérez"),
                Arguments.of(Named.of("Windows line break", "Ana\r\nPérez"), "Ana Pérez"),
                Arguments.of(Named.of("Unicode line separator", "Ana\u2028Pérez"), "Ana Pérez"),
                Arguments.of(Named.of("tab and trailing line break", "Ana\tPérez\n"), "Ana Pérez"),
                Arguments.of(Named.of("NUL character", "Ana\u0000Pérez"), "Ana Pérez"),
                Arguments.of(Named.of("lone surrogate", "Ana\uD83DPérez"), "Ana Pérez"),
                Arguments.of(Named.of("no-break space", "Ana\u00A0Pérez"), "Ana Pérez"),
                Arguments.of(Named.of("run of spaces", "Ana   Pérez"), "Ana Pérez"),
                Arguments.of(Named.of("already clean", "Ana Pérez"), "Ana Pérez"),
                Arguments.of(Named.of("emoji joined with zero-width joiners", "Ana 👩\u200D👩\u200D👧 Pérez"),
                        "Ana 👩\u200D👩\u200D👧 Pérez"));
    }

    static Stream<Arguments> overlongNames() {
        return Stream.of(
                Arguments.of(Named.of("emoji at the limit", "a".repeat(199) + "😀bbb"), "a".repeat(199) + "😀"),
                Arguments.of(Named.of("plain letters", "a".repeat(300)), "a".repeat(200)));
    }

    private UUID resolveIn(UUID organizationId, ContactIdentity identity) {
        return currentOrganization.callAs(organizationId, () -> directory.resolveOrCreate(identity));
    }

    private UUID resolveIn(UUID organizationId, ContactIdentity identity, String displayName) {
        return currentOrganization.callAs(organizationId, () -> directory.resolveOrCreate(identity, displayName));
    }

    private void assertOnlyPerson(UUID organizationId, UUID personId, IdentityKind kind, String value) {
        assertOnlyPerson(organizationId, personId, null, kind, value);
    }

    private void assertOnlyPerson(UUID organizationId, UUID personId, String displayName, IdentityKind kind,
            String value) {
        assertThat(fixture.peopleOf(organizationId)).containsExactly(new StoredPerson(personId, NOW, displayName));
        assertThat(fixture.identitiesOf(organizationId)).containsExactly(new StoredIdentity(personId, kind, value));
    }
}
