package com.communicatorone.opportunities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.communicatorone.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OpportunitySettingsTest {

    @ParameterizedTest
    @CsvSource({"0, 3", "-1, 3", "7, -1"})
    void settingsWithoutInactivityPeriodOrWithNegativeReentryWindowAreRejected(int inactivityDaysToClose,
            int reentryWindowDays) {
        // When
        var rejection = catchThrowable(() -> OpportunitySettings.of(inactivityDaysToClose, reentryWindowDays));

        // Then
        assertThat(rejection).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oneDayOfInactivityWithoutReentryWindowIsTheShortestAcceptedSetting() {
        // Given
        var lastActivityAt = MutableClock.INITIAL_INSTANT;

        // When
        var settings = OpportunitySettings.of(1, 0);

        // Then
        var closedAt = lastActivityAt.plus(Duration.ofDays(1));
        assertThat(settings.inactivityEndsAt(lastActivityAt)).isEqualTo(closedAt);
        assertThat(settings.allowsReentryAt(closedAt, closedAt)).isFalse();
    }
}
