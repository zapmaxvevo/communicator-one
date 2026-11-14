package com.communicatorone.opportunities;

import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class InactiveOpportunityClosing {

    private final OpportunityRepository opportunities;
    private final OpportunitySettingsRepository settings;
    private final Clock clock;
    private final Duration lockTimeout;

    InactiveOpportunityClosing(OpportunityRepository opportunities, OpportunitySettingsRepository settings,
            Clock clock,
            @Value("${communicator-one.opportunities.closing-lock-timeout}") Duration lockTimeout) {
        this.opportunities = opportunities;
        this.settings = settings;
        this.clock = clock;
        this.lockTimeout = lockTimeout;
    }

    @Transactional
    void closeInactiveOpportunities() {
        opportunities.limitLockWaitingInCurrentTransaction(lockTimeout.toMillis() + "ms");
        var currentSettings = settings.currentOrDefaults();
        var now = clock.instant();
        opportunities.findOpenInactiveSince(currentSettings.inactivityCutoffAt(now))
                .forEach(opportunity -> opportunity.closeIfInactiveAt(now, currentSettings));
    }
}
