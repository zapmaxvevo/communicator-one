package com.communicatorone.opportunities;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;

@Service
public class OpportunityTracker {

    private final OpportunityRepository opportunities;
    private final OpportunitySettingsRepository settings;
    private final TransactionTemplate writing;
    private final Clock clock;

    OpportunityTracker(OpportunityRepository opportunities, OpportunitySettingsRepository settings,
            TransactionTemplate writing, Clock clock) {
        this.opportunities = opportunities;
        this.settings = settings;
        this.writing = writing;
        this.clock = clock;
    }

    public UUID recordActivity(UUID personId, String source) {
        Objects.requireNonNull(personId, "personId");
        Assert.hasText(source, "Activity needs the source it came from");
        Assert.state(!TransactionSynchronizationManager.isSynchronizationActive(),
                "Activity is recorded in its own transaction, not inside an open one");
        TransactionCallback<UUID> recording = transaction -> currentOpportunityOf(personId, source);
        try {
            return writing.execute(recording);
        } catch (DataIntegrityViolationException concurrentOpening) {
            return writing.execute(recording);
        }
    }

    private UUID currentOpportunityOf(UUID personId, String source) {
        var currentSettings = settings.findCurrent().orElseGet(OpportunitySettings::defaults);
        var latest = opportunities.findFirstByPersonIdOrderByOpenedAtDesc(personId);
        var now = clock.instant();
        if (latest.isPresent() && latest.get().recordActivityAt(now, currentSettings)) {
            return latest.get().id();
        }
        opportunities.flush();
        return opportunities.saveAndFlush(Opportunity.openFor(personId, source, now)).id();
    }
}
