package com.communicatorone.operators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

class OperatorTest {

    private static final String PASSWORD = "correct horse battery staple";

    private final PasswordEncoder passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    @Test
    void operatorEmailIsNormalizedToLowercaseWithoutSurroundingSpaces() {
        // When
        var operator = Operator.register("  Ana.Perez@Acme.TEST ", "Ana Pérez", OperatorRole.OPERATOR, PASSWORD,
                passwordEncoder);

        // Then
        assertThat(operator.email()).isEqualTo("ana.perez@acme.test");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("incompleteRegistrations")
    void operatorWithoutEmailNameRoleOrPasswordIsRejected(String missing, String email, String name,
            OperatorRole role, String password) {
        // When
        var rejection = catchThrowable(() -> Operator.register(email, name, role, password, passwordEncoder));

        // Then
        assertThat(rejection).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registeredOperatorAcceptsTheirPasswordAndRejectsAnyOther() {
        // Given
        var operator = Operator.register("ana@acme.test", "Ana", OperatorRole.OPERATOR, PASSWORD, passwordEncoder);

        // When
        var acceptsOwnPassword = operator.acceptsPassword(PASSWORD, passwordEncoder);
        var acceptsAnotherPassword = operator.acceptsPassword("Correct horse battery staple", passwordEncoder);

        // Then
        assertThat(acceptsOwnPassword).isTrue();
        assertThat(acceptsAnotherPassword).isFalse();
    }

    @Test
    void newOperatorStartsAway() {
        // When
        var operator = Operator.register("ana@acme.test", "Ana", OperatorRole.SUPERVISOR, PASSWORD, passwordEncoder);

        // Then
        assertThat(operator.availability()).isEqualTo(Availability.AWAY);
    }

    static Stream<Arguments> incompleteRegistrations() {
        return Stream.of(
                Arguments.of("null email", null, "Ana", OperatorRole.OPERATOR, PASSWORD),
                Arguments.of("empty email", "", "Ana", OperatorRole.OPERATOR, PASSWORD),
                Arguments.of("blank email", "   ", "Ana", OperatorRole.OPERATOR, PASSWORD),
                Arguments.of("null name", "ana@acme.test", null, OperatorRole.OPERATOR, PASSWORD),
                Arguments.of("empty name", "ana@acme.test", "", OperatorRole.OPERATOR, PASSWORD),
                Arguments.of("blank name", "ana@acme.test", "   ", OperatorRole.OPERATOR, PASSWORD),
                Arguments.of("null role", "ana@acme.test", "Ana", null, PASSWORD),
                Arguments.of("null password", "ana@acme.test", "Ana", OperatorRole.OPERATOR, null),
                Arguments.of("empty password", "ana@acme.test", "Ana", OperatorRole.OPERATOR, ""),
                Arguments.of("blank password", "ana@acme.test", "Ana", OperatorRole.OPERATOR, "   "));
    }
}
