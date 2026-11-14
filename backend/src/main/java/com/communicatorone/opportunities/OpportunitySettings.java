package com.communicatorone.opportunities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;
import org.springframework.util.Assert;

@Entity
@Table(name = "opportunity_settings")
class OpportunitySettings {

    private static final int DEFAULT_INACTIVITY_DAYS_TO_CLOSE = 7;
    private static final int DEFAULT_REENTRY_WINDOW_DAYS = 3;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(name = "organization_id")
    private UUID organizationId;

    private int inactivityDaysToClose;

    private int reentryWindowDays;

    protected OpportunitySettings() {
    }

    private OpportunitySettings(int inactivityDaysToClose, int reentryWindowDays) {
        this.inactivityDaysToClose = inactivityDaysToClose;
        this.reentryWindowDays = reentryWindowDays;
    }

    static OpportunitySettings of(int inactivityDaysToClose, int reentryWindowDays) {
        Assert.isTrue(inactivityDaysToClose >= 1, "An opportunity closes after at least one day of inactivity");
        Assert.isTrue(reentryWindowDays >= 0, "The reentry window cannot be negative");
        return new OpportunitySettings(inactivityDaysToClose, reentryWindowDays);
    }

    static OpportunitySettings defaults() {
        return of(DEFAULT_INACTIVITY_DAYS_TO_CLOSE, DEFAULT_REENTRY_WINDOW_DAYS);
    }

    Instant inactivityEndsAt(Instant lastActivityAt) {
        return lastActivityAt.plus(Duration.ofDays(inactivityDaysToClose));
    }

    boolean allowsReentryAt(Instant closedAt, Instant now) {
        return now.isBefore(closedAt.plus(Duration.ofDays(reentryWindowDays)));
    }
}
