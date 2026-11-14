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
    private static final String SOURCE = "whatsapp";
    private static final int INACTIVITY_DAYS_TO_CLOSE = 7;
    private static final int REENTRY_WINDOW_DAYS = 3;
    private static final Duration INACTIVITY_PERIOD = Duration.ofDays(INACTIVITY_DAYS_TO_CLOSE);
    private static final Duration REENTRY_WINDOW = Duration.ofDays(REENTRY_WINDOW_DAYS);
    private static final OpportunitySettings SETTINGS = OpportunitySettings.of(INACTIVITY_DAYS_TO_CLOSE,
            REENTRY_WINDOW_DAYS);
    private static final Duration ONE_SECOND = Duration.ofSeconds(1);

    @Test
    void activityWithinTheInactivityPeriodContinuesTheOpenOpportunity() {
        // Given
        var opportunity = Opportunity.openFor(UUID.randomUUID(), SOURCE, OPENED_AT);
        var justBeforeBecomingInactive = OPENED_AT.plus(INACTIVITY_PERIOD).minus(ONE_SECOND);

        // When
        var stillCurrent = opportunity.recordActivityAt(justBeforeBecomingInactive, SETTINGS);

        // Then
        assertThat(stillCurrent).isTrue();
        assertOpen(opportunity, justBeforeBecomingInactive);
    }

    @Test
    void inactivityIsCountedFromTheLastActivityNotFromTheOpening() {
        // Given
        var opportunity = Opportunity.openFor(UUID.randomUUID(), SOURCE, OPENED_AT);
        opportunity.recordActivityAt(OPENED_AT.plus(Duration.ofDays(5)), SETTINGS);
        var elevenDaysAfterOpening = OPENED_AT.plus(Duration.ofDays(11));

        // When
        var stillCurrent = opportunity.recordActivityAt(elevenDaysAfterOpening, SETTINGS);

        // Then
        assertThat(stillCurrent).isTrue();
        assertOpen(opportunity, elevenDaysAfterOpening);
    }

    @Test
    void activityDatedBeforeTheLastRecordedActivityDoesNotMoveItBack() {
        // Given
        var opportunity = Opportunity.openFor(UUID.randomUUID(), SOURCE, OPENED_AT);
        var lastRecordedActivity = OPENED_AT.plus(Duration.ofDays(2));
        opportunity.recordActivityAt(lastRecordedActivity, SETTINGS);
        var fromANodeWithALaggingClock = lastRecordedActivity.minus(Duration.ofMinutes(5));

        // When
        var stillCurrent = opportunity.recordActivityAt(fromANodeWithALaggingClock, SETTINGS);

        // Then
        assertThat(stillCurrent).isTrue();
        assertOpen(opportunity, lastRecordedActivity);
    }

    @Test
    void personReturningJustBeforeTheReentryWindowEndsGetsTheSameOpportunityBack() {
        // Given
        var opportunity = Opportunity.openFor(UUID.randomUUID(), SOURCE, OPENED_AT);
        var justBeforeTheReentryWindowEnds = OPENED_AT.plus(INACTIVITY_PERIOD).plus(REENTRY_WINDOW).minus(ONE_SECOND);

        // When
        var stillCurrent = opportunity.recordActivityAt(justBeforeTheReentryWindowEnds, SETTINGS);

        // Then
        assertThat(stillCurrent).isTrue();
        assertOpen(opportunity, justBeforeTheReentryWindowEnds);
    }

    @Test
    void personReturningWhenTheReentryWindowEndsNeedsANewOpportunity() {
        // Given
        var opportunity = Opportunity.openFor(UUID.randomUUID(), SOURCE, OPENED_AT);
        var whenTheReentryWindowEnds = OPENED_AT.plus(INACTIVITY_PERIOD).plus(REENTRY_WINDOW);

        // When
        var stillCurrent = opportunity.recordActivityAt(whenTheReentryWindowEnds, SETTINGS);

        // Then
        assertThat(stillCurrent).isFalse();
        assertClosedWithoutResponse(opportunity, OPENED_AT.plus(INACTIVITY_PERIOD));
    }

    @Test
    void withoutReentryWindowAPersonReturningAfterTheInactivityPeriodNeedsANewOpportunity() {
        // Given
        var withoutReentryWindow = OpportunitySettings.of(INACTIVITY_DAYS_TO_CLOSE, 0);
        var opportunity = Opportunity.openFor(UUID.randomUUID(), SOURCE, OPENED_AT);
        var whenTheInactivityPeriodEnds = OPENED_AT.plus(INACTIVITY_PERIOD);

        // When
        var stillCurrent = opportunity.recordActivityAt(whenTheInactivityPeriodEnds, withoutReentryWindow);

        // Then
        assertThat(stillCurrent).isFalse();
        assertClosedWithoutResponse(opportunity, whenTheInactivityPeriodEnds);
    }

    @Test
    void inactiveOpportunityIsClosedWithoutResponseAtTheEndOfItsInactivity() {
        // Given
        var opportunity = Opportunity.openFor(UUID.randomUUID(), SOURCE, OPENED_AT);
        var hoursAfterTheInactivityPeriodEnds = OPENED_AT.plus(INACTIVITY_PERIOD).plus(Duration.ofHours(3));

        // When
        opportunity.closeIfInactiveAt(hoursAfterTheInactivityPeriodEnds, SETTINGS);

        // Then
        assertClosedWithoutResponse(opportunity, OPENED_AT.plus(INACTIVITY_PERIOD));
    }

    @Test
    void opportunityStillWithinItsInactivityPeriodIsNotClosed() {
        // Given
        var opportunity = Opportunity.openFor(UUID.randomUUID(), SOURCE, OPENED_AT);
        var justBeforeBecomingInactive = OPENED_AT.plus(INACTIVITY_PERIOD).minus(ONE_SECOND);

        // When
        opportunity.closeIfInactiveAt(justBeforeBecomingInactive, SETTINGS);

        // Then
        assertOpen(opportunity, OPENED_AT);
    }

    @Test
    void closingAnAlreadyClosedOpportunityAgainChangesNothing() {
        // Given
        var opportunity = Opportunity.openFor(UUID.randomUUID(), SOURCE, OPENED_AT);
        opportunity.closeIfInactiveAt(OPENED_AT.plus(Duration.ofDays(8)), SETTINGS);

        // When
        opportunity.closeIfInactiveAt(OPENED_AT.plus(Duration.ofDays(20)), SETTINGS);

        // Then
        assertClosedWithoutResponse(opportunity, OPENED_AT.plus(INACTIVITY_PERIOD));
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

    private static void assertOpen(Opportunity opportunity, Instant lastActivityAt) {
        assertThat(opportunity.status()).isEqualTo(OpportunityStatus.OPEN);
        assertThat(opportunity.result()).isNull();
        assertThat(opportunity.closedAt()).isNull();
        assertThat(opportunity.source()).isEqualTo(SOURCE);
        assertThat(opportunity.openedAt()).isEqualTo(OPENED_AT);
        assertThat(opportunity.lastActivityAt()).isEqualTo(lastActivityAt);
    }

    private static void assertClosedWithoutResponse(Opportunity opportunity, Instant closedAt) {
        assertThat(opportunity.status()).isEqualTo(OpportunityStatus.CLOSED);
        assertThat(opportunity.result()).isEqualTo(OpportunityResult.NO_RESPONSE);
        assertThat(opportunity.closedAt()).isEqualTo(closedAt);
        assertThat(opportunity.source()).isEqualTo(SOURCE);
        assertThat(opportunity.openedAt()).isEqualTo(OPENED_AT);
        assertThat(opportunity.lastActivityAt()).isEqualTo(OPENED_AT);
    }
}
