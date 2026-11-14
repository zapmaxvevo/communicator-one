package com.communicatorone.channels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.communicatorone.TestcontainersConfiguration;
import com.communicatorone.channels.ChannelsFixture.RegisteredAccount;
import com.communicatorone.messaging.ChannelType;
import com.communicatorone.organizations.CurrentOrganization;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class ChannelAccountDirectoryTest {

    private static final String MISSING_TYPE = "A channel account is resolved by its channel type";
    private static final String MISSING_EXTERNAL_ID = "A channel account needs the id its provider gave it";

    private final ChannelAccountDirectory directory;
    private final ChannelAccountRepository accounts;
    private final CurrentOrganization currentOrganization;
    private final ChannelsFixture fixture;

    ChannelAccountDirectoryTest(ChannelAccountDirectory directory, ChannelAccountRepository accounts,
            CurrentOrganization currentOrganization, JdbcTemplate jdbcTemplate) {
        this.directory = directory;
        this.accounts = accounts;
        this.currentOrganization = currentOrganization;
        this.fixture = new ChannelsFixture(jdbcTemplate, currentOrganization, accounts);
    }

    @Test
    void eachAccountResolvesToItsOwnOrganizationWithoutAnActiveOrganization() {
        // Given
        var acme = fixture.organization("Acme");
        var globex = fixture.organization("Globex");
        var acmeExternalId = fixture.externalAccountId("100200300");
        var globexExternalId = fixture.externalAccountId("400500600");
        var acmeAccount = fixture.account(acme, ChannelType.WHATSAPP, acmeExternalId, true);
        var globexAccount = fixture.account(globex, ChannelType.WHATSAPP, globexExternalId, false);

        // When
        var resolvedForAcme = resolve(ChannelType.WHATSAPP, acmeExternalId);
        var resolvedForGlobex = resolve(ChannelType.WHATSAPP, globexExternalId);

        // Then
        assertThat(resolvedForAcme)
                .contains(new RegisteredAccount(acmeAccount.id(), acme, ChannelType.WHATSAPP, true));
        assertThat(resolvedForGlobex)
                .contains(new RegisteredAccount(globexAccount.id(), globex, ChannelType.WHATSAPP, false));
    }

    @Test
    void unknownExternalAccountIdResolvesNothing() {
        // Given
        var acme = fixture.organization("Acme");
        fixture.account(acme, ChannelType.WHATSAPP, fixture.externalAccountId("100200300"), true);
        var unknownExternalId = fixture.externalAccountId("999888777");

        // When
        var resolved = resolve(ChannelType.WHATSAPP, unknownExternalId);

        // Then
        assertThat(resolved).isEmpty();
    }

    @Test
    void sameExternalAccountIdOnAnotherChannelIsAnotherAccount() {
        // Given
        var acme = fixture.organization("Acme");
        var globex = fixture.organization("Globex");
        var externalId = fixture.externalAccountId("777");
        var acmeWhatsapp = fixture.account(acme, ChannelType.WHATSAPP, externalId, true);
        var globexTelegram = fixture.account(globex, ChannelType.TELEGRAM, externalId, true);

        // When
        var resolvedOnWhatsapp = resolve(ChannelType.WHATSAPP, externalId);
        var resolvedOnTelegram = resolve(ChannelType.TELEGRAM, externalId);

        // Then
        assertThat(resolvedOnWhatsapp)
                .contains(new RegisteredAccount(acmeWhatsapp.id(), acme, ChannelType.WHATSAPP, true));
        assertThat(resolvedOnTelegram)
                .contains(new RegisteredAccount(globexTelegram.id(), globex, ChannelType.TELEGRAM, true));
    }

    @Test
    void externalAccountIdCannotBelongToTwoOrganizations() {
        // Given
        var acme = fixture.organization("Acme");
        var globex = fixture.organization("Globex");
        var externalId = fixture.externalAccountId("555");
        var acmeAccount = fixture.account(acme, ChannelType.WHATSAPP, externalId, true);

        // When
        var rejection = catchThrowable(() -> fixture.account(globex, ChannelType.WHATSAPP, externalId, true));

        // Then
        assertThat(rejection).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("channel_accounts_channel_type_external_account_id_key");
        assertThat(fixture.accountsOf(globex)).isZero();
        assertThat(resolve(ChannelType.WHATSAPP, externalId)).contains(acmeAccount);
    }

    @Test
    void channelAccountOfAnotherOrganizationIsNotVisible() {
        // Given
        var acme = fixture.organization("Acme");
        var globex = fixture.organization("Globex");
        var externalId = fixture.externalAccountId("100200300");
        var acmeAccount = fixture.account(acme, ChannelType.WHATSAPP, externalId, true);

        // When
        var byIdFromGlobex = currentOrganization.callAs(globex,
                () -> accounts.findById(acmeAccount.id()).map(RegisteredAccount::of));
        var byExternalIdFromGlobex = currentOrganization.callAs(globex,
                () -> accounts.findByChannelTypeAndExternalAccountId(ChannelType.WHATSAPP, externalId)
                        .map(RegisteredAccount::of));
        var byIdFromAcme = currentOrganization.callAs(acme,
                () -> accounts.findById(acmeAccount.id()).map(RegisteredAccount::of));
        var byExternalIdFromAcme = currentOrganization.callAs(acme,
                () -> accounts.findByChannelTypeAndExternalAccountId(ChannelType.WHATSAPP, externalId)
                        .map(RegisteredAccount::of));

        // Then
        assertThat(byIdFromGlobex).isEmpty();
        assertThat(byExternalIdFromGlobex).isEmpty();
        assertThat(byIdFromAcme).contains(acmeAccount);
        assertThat(byExternalIdFromAcme).contains(acmeAccount);
    }

    @Test
    void accountRegisteredWithSurroundingSpacesResolvesByItsTrimmedId() {
        // Given
        var acme = fixture.organization("Acme");
        var externalId = fixture.externalAccountId("123456");
        var acmeAccount = fixture.account(acme, ChannelType.WHATSAPP, "  " + externalId + " ", true);

        // When
        var resolvedByTrimmedId = resolve(ChannelType.WHATSAPP, externalId);
        var resolvedByPaddedId = resolve(ChannelType.WHATSAPP, " " + externalId + "  ");

        // Then
        assertThat(resolvedByTrimmedId).contains(acmeAccount);
        assertThat(resolvedByPaddedId).contains(acmeAccount);
    }

    @Test
    void resolutionIgnoresAnotherActiveOrganization() {
        // Given
        var acme = fixture.organization("Acme");
        var globex = fixture.organization("Globex");
        var externalId = fixture.externalAccountId("100200300");
        var acmeAccount = fixture.account(acme, ChannelType.WHATSAPP, externalId, true);

        // When
        var resolved = currentOrganization.callAs(globex, () -> resolve(ChannelType.WHATSAPP, externalId));

        // Then
        assertThat(resolved).contains(new RegisteredAccount(acmeAccount.id(), acme, ChannelType.WHATSAPP, true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedLookups")
    void resolvingWithoutChannelTypeOrExternalAccountIdIsRejected(String missing, ChannelType channelType,
            String externalAccountId, String expectedMessage) {
        // When
        var rejection = catchThrowable(() -> directory.resolve(channelType, externalAccountId));

        // Then
        assertThat(rejection).isInstanceOf(IllegalArgumentException.class).hasMessage(expectedMessage);
    }

    static Stream<Arguments> malformedLookups() {
        return Stream.of(
                Arguments.of("null channel type", null, "123", MISSING_TYPE),
                Arguments.of("null external id", ChannelType.WHATSAPP, null, MISSING_EXTERNAL_ID),
                Arguments.of("blank external id", ChannelType.WHATSAPP, "  ", MISSING_EXTERNAL_ID));
    }

    private Optional<RegisteredAccount> resolve(ChannelType channelType, String externalAccountId) {
        return directory.resolve(channelType, externalAccountId).map(RegisteredAccount::of);
    }
}
