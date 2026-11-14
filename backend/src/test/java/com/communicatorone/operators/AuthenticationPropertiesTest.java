package com.communicatorone.operators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class AuthenticationPropertiesTest {

    private static final String SECRET_OF_32_BYTES = "0123456789abcdef0123456789abcdef";
    private static final Duration EIGHT_HOURS = Duration.ofHours(8);

    @Test
    void secretOfExactly32BytesWithAPositiveLifetimeIsAccepted() {
        // When
        var properties = new AuthenticationProperties(SECRET_OF_32_BYTES, EIGHT_HOURS);

        // Then
        assertThat(properties.tokenLifetime()).isEqualTo(EIGHT_HOURS);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"                                ", "0123456789abcdef0123456789abcde"})
    void missingBlankOrShorterThan32BytesSecretIsRejected(String secret) {
        // When
        var rejection = catchThrowable(() -> new AuthenticationProperties(secret, EIGHT_HOURS));

        // Then
        assertThat(rejection).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("jwt-secret");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"PT0S", "PT-1S"})
    void missingZeroOrNegativeTokenLifetimeIsRejected(Duration tokenLifetime) {
        // When
        var rejection = catchThrowable(() -> new AuthenticationProperties(SECRET_OF_32_BYTES, tokenLifetime));

        // Then
        assertThat(rejection).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("token-lifetime");
    }
}
