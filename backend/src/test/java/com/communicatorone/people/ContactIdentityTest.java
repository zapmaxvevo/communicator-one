package com.communicatorone.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class ContactIdentityTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("phonesWrittenInDifferentFormats")
    void phoneWrittenInDifferentFormatsIsNormalizedToTheSameE164Identity(String country, String e164,
            List<String> writings) {
        // When
        var identities = writings.stream().map(ContactIdentity::phone).toList();

        // Then
        assertThat(identities)
                .extracting(ContactIdentity::kind, ContactIdentity::value)
                .containsOnly(tuple(IdentityKind.PHONE, e164));
    }

    @ParameterizedTest
    @ValueSource(strings = {"11 5555-0101", "011 5555-0101", "5491155550101"})
    void phoneWithoutInternationalPrefixIsRejected(String phone) {
        // When
        var rejection = catchThrowable(() -> ContactIdentity.phone(phone));

        // Then
        assertRejectedWithoutRevealing(rejection, phone);
    }

    @ParameterizedTest
    @ValueSource(strings = {"+54 12", "hola"})
    void impossiblePhoneIsRejected(String phone) {
        // When
        var rejection = catchThrowable(() -> ContactIdentity.phone(phone));

        // Then
        assertRejectedWithoutRevealing(rejection, phone);
    }

    @ParameterizedTest
    @ValueSource(strings = {"+1 202-555-0143 x1", "+54 9 11 5555-0101 ext. 2"})
    void phoneWithExtensionIsRejected(String phone) {
        // When
        var rejection = catchThrowable(() -> ContactIdentity.phone(phone));

        // Then
        assertRejectedWithoutRevealing(rejection, phone);
    }

    @Test
    void emailIsNormalizedToLowercaseWithoutSurroundingSpaces() {
        // When
        var identity = ContactIdentity.email("  Ana.Perez+Ventas@Acme.TEST ");

        // Then
        assertThat(identity)
                .extracting(ContactIdentity::kind, ContactIdentity::value)
                .containsExactly(IdentityKind.EMAIL, "ana.perez+ventas@acme.test");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ana.perez.acme.test", "@", "ana@", "@acme.test", "a@b@c", "ana perez@acme.test"})
    void malformedEmailIsRejected(String email) {
        // When
        var rejection = catchThrowable(() -> ContactIdentity.email(email));

        // Then
        assertRejectedWithoutRevealing(rejection, email);
    }

    @ParameterizedTest
    @EnumSource(value = IdentityKind.class, names = {"INSTAGRAM_USER", "MESSENGER_USER", "TELEGRAM_USER"})
    void channelUserIdKeepsItsCaseWithoutSurroundingSpaces(IdentityKind kind) {
        // When
        var identity = new ContactIdentity(kind, "  AbC-123_xY ");

        // Then
        assertThat(identity)
                .extracting(ContactIdentity::kind, ContactIdentity::value)
                .containsExactly(kind, "AbC-123_xY");
    }

    @Test
    void identityWithoutKindIsRejected() {
        // When
        var rejection = catchThrowable(() -> new ContactIdentity(null, "+54 9 11 5555-0101"));

        // Then
        assertThat(rejection).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest(name = "{0} «{1}»")
    @MethodSource("identitiesWithoutValue")
    void identityWithoutValueIsRejected(IdentityKind kind, String value) {
        // When
        var rejection = catchThrowable(() -> new ContactIdentity(kind, value));

        // Then
        assertThat(rejection).isInstanceOf(InvalidContactIdentityException.class);
    }

    @Test
    void identityDoesNotRevealItsValueWhenPrinted() {
        // Given
        var phone = ContactIdentity.phone("+54 9 11 5555-0101");
        var email = ContactIdentity.email("Ana.Perez@Acme.test");

        // When
        var printedPhone = phone.toString();
        var printedEmail = email.toString();

        // Then
        assertThat(printedPhone).contains("PHONE").doesNotContain("5555", "0101", "5491155550101");
        assertThat(printedEmail).contains("EMAIL").doesNotContainIgnoringCase("ana", "perez", "acme");
    }

    static Stream<Arguments> phonesWrittenInDifferentFormats() {
        return Stream.of(
                Arguments.of("Argentina", "+5491155550101",
                        List.of("+54 9 11 5555-0101", " +5491155550101 ", "+54 (9 11) 5555 0101")),
                Arguments.of("United States", "+12025550143",
                        List.of("+1 (202) 555-0143", "+1 202-555-0143", " +12025550143")),
                Arguments.of("Spain", "+34612345678",
                        List.of("+34 612 34 56 78", "+34612345678", "+34 612-345-678 ")));
    }

    static Stream<Arguments> identitiesWithoutValue() {
        return Arrays.stream(IdentityKind.values())
                .flatMap(kind -> Stream.of(
                        Arguments.of(kind, null),
                        Arguments.of(kind, ""),
                        Arguments.of(kind, "   ")));
    }

    private static void assertRejectedWithoutRevealing(Throwable rejection, String rejectedValue) {
        assertThat(rejection).isInstanceOf(InvalidContactIdentityException.class);
        assertThat(rejection.getMessage()).doesNotContain(rejectedValue, digitsOf(rejectedValue));
    }

    private static String digitsOf(String value) {
        var digits = value.replaceAll("\\D", "");
        return digits.isEmpty() ? value : digits;
    }
}
