package com.communicatorone.opportunities;

import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class InactiveOpportunityClosing {

    private final OpportunityRepository opportunities;
    private final OpportunitySettingsRepository settings;
    private final Clock clock;

    InactiveOpportunityClosing(OpportunityRepository opportunities, OpportunitySettingsRepository settings,
            Clock clock) {
        this.opportunities = opportunities;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional
    void closeInactiveOpportunities() {
        var currentSettings = settings.currentOrDefaults();
        var now = clock.instant();
        opportunities.findOpenInactiveSince(currentSettings.inactivityCutoffAt(now))
                .forEach(opportunity -> opportunity.closeIfInactiveAt(now, currentSettings));
    }
}
