package com.communicatorone.opportunities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.communicatorone.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class OpportunityTest {

    private static final Instant OPENED_AT = MutableClock.INITIAL_INSTANT;
    private static final Duration INACTIVITY_PERIOD = Duration.ofDays(7);
    private static final Duration REENTRY_WINDOW = Duration.ofDays(3);
    private static final Duration ONE_SECOND = Duration.ofSeconds(1);

    @Test
    void activityWithinTheInactivityPeriodContinuesTheOpenOpportunity() {
        // Given
        var settings = OpportunitySettings.of(7, 3);
        var opportunity = Opportunity.openFor(UUID.randomUUID(), "whatsapp", OPENED_AT);
        var justBeforeBecomingInactive = OPENED_AT.plus(INACTIVITY_PERIOD).minus(ONE_SECOND);

        // When
        var stillCurrent = opportunity.recordActivityAt(justBeforeBecomingInactive, settings);

        // Then
        assertThat(stillCurrent).isTrue();
        assertOpen(opportunity, "whatsapp", OPENED_AT, justBeforeBecomingInactive);
    }

    @Test
    void inactivityIsCountedFromTheLastActivityNotFromTheOpening() {
        // Given
        var settings = OpportunitySettings.of(7, 3);
        var opportunity = Opportunity.openFor(UUID.randomUUID(), "whatsapp", OPENED_AT);
        opportunity.recordActivityAt(OPENED_AT.plus(Duration.ofDays(5)), settings);
        var elevenDaysAfterOpening = OPENED_AT.plus(Duration.ofDays(11));

        // When
        var stillCurrent = opportunity.recordActivityAt(elevenDaysAfterOpening, settings);

        // Then
        assertThat(stillCurrent).isTrue();
        assertOpen(opportunity, "whatsapp", OPENED_AT, elevenDaysAfterOpening);
    }

    @Test
    void personReturningJustBeforeTheReentryWindowEndsGetsTheSameOpportunityBack() {
        // Given
        var settings = OpportunitySettings.of(7, 3);
        var opportunity = Opportunity.openFor(UUID.randomUUID(), "whatsapp", OPENED_AT);
        var justBeforeTheReentryWindowEnds = OPENED_AT.plus(INACTIVITY_PERIOD).plus(REENTRY_WINDOW).minus(ONE_SECOND);

        // When
        var stillCurrent = opportunity.recordActivityAt(justBeforeTheReentryWindowEnds, settings);

        // Then
        assertThat(stillCurrent).isTrue();
        assertOpen(opportunity, "whatsapp", OPENED_AT, justBeforeTheReentryWindowEnds);
    }

    @Test
    void personReturningWhenTheReentryWindowEndsNeedsANewOpportunity() {
        // Given
        var settings = OpportunitySettings.of(7, 3);
        var opportunity = Opportunity.openFor(UUID.randomUUID(), "whatsapp", OPENED_AT);
        var whenTheReentryWindowEnds = OPENED_AT.plus(INACTIVITY_PERIOD).plus(REENTRY_WINDOW);

        // When
        var stillCurrent = opportunity.recordActivityAt(whenTheReentryWindowEnds, settings);

        // Then
        assertThat(stillCurrent).isFalse();
        assertClosedWithoutResponse(opportunity, "whatsapp", OPENED_AT, OPENED_AT.plus(INACTIVITY_PERIOD));
    }

    @Test
    void withoutReentryWindowAPersonReturningAfterTheInactivityPeriodNeedsANewOpportunity() {
        // Given
        var settings = OpportunitySettings.of(7, 0);
        var opportunity = Opportunity.openFor(UUID.randomUUID(), "whatsapp", OPENED_AT);
        var whenTheInactivityPeriodEnds = OPENED_AT.plus(INACTIVITY_PERIOD);

        // When
        var stillCurrent = opportunity.recordActivityAt(whenTheInactivityPeriodEnds, settings);

        // Then
        assertThat(stillCurrent).isFalse();
        assertClosedWithoutResponse(opportunity, "whatsapp", OPENED_AT, whenTheInactivityPeriodEnds);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void opportunityCannotOpenWithoutSource(String source) {
        // When
        var rejection = catchThrowable(() -> Opportunity.openFor(UUID.randomUUID(), source, OPENED_AT));

        // Then
        assertThat(rejection).isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertOpen(Opportunity opportunity, String source, Instant openedAt, Instant lastActivityAt) {
        assertThat(opportunity.status()).isEqualTo(OpportunityStatus.OPEN);
        assertThat(opportunity.result()).isNull();
        assertThat(opportunity.closedAt()).isNull();
        assertThat(opportunity.source()).isEqualTo(source);
        assertThat(opportunity.openedAt()).isEqualTo(openedAt);
        assertThat(opportunity.lastActivityAt()).isEqualTo(lastActivityAt);
    }

    private static void assertClosedWithoutResponse(Opportunity opportunity, String source, Instant lastActivityAt,
            Instant closedAt) {
        assertThat(opportunity.status()).isEqualTo(OpportunityStatus.CLOSED);
        assertThat(opportunity.result()).isEqualTo(OpportunityResult.NO_RESPONSE);
        assertThat(opportunity.closedAt()).isEqualTo(closedAt);
        assertThat(opportunity.source()).isEqualTo(source);
        assertThat(opportunity.openedAt()).isEqualTo(OPENED_AT);
        assertThat(opportunity.lastActivityAt()).isEqualTo(lastActivityAt);
    }
}
