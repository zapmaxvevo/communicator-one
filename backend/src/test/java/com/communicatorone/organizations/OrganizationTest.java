package com.communicatorone.organizations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class OrganizationTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void organizationWithoutNameIsRejected(String missingName) {
        // When
        var rejection = catchThrowable(() -> Organization.named(missingName));

        // Then
        assertThat(rejection).isInstanceOf(IllegalArgumentException.class);
    }
}
