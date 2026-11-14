package com.communicatorone.opportunities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.TenantId;
import org.springframework.util.Assert;

@Entity
@Table(name = "opportunities")
class Opportunity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(name = "organization_id")
    private UUID organizationId;

    private UUID personId;

    private UUID ownerId;

    private String source;

    @Enumerated(EnumType.STRING)
    private OpportunityStatus status;

    @Enumerated(EnumType.STRING)
    private OpportunityResult result;

    private Instant openedAt;

    private Instant lastActivityAt;

    private Instant closedAt;

    protected Opportunity() {
    }

    private Opportunity(UUID personId, String source, Instant openedAt) {
        this.personId = personId;
        this.source = source;
        this.status = OpportunityStatus.OPEN;
        this.openedAt = openedAt;
        this.lastActivityAt = openedAt;
    }

    static Opportunity openFor(UUID personId, String source, Instant now) {
        Objects.requireNonNull(personId, "personId");
        Assert.hasText(source, "An opportunity needs the source it came from");
        return new Opportunity(personId, source, now);
    }

    boolean recordActivityAt(Instant now, OpportunitySettings settings) {
        var inactivityEndsAt = settings.inactivityEndsAt(lastActivityAt);
        if (isOpen() && !now.isBefore(inactivityEndsAt)) {
            closeAsUnanswered(inactivityEndsAt);
        }
        if (isOpen()) {
            lastActivityAt = now;
            return true;
        }
        if (settings.allowsReentryAt(closedAt, now)) {
            reopen(now);
            return true;
        }
        return false;
    }

    UUID id() {
        return id;
    }

    String source() {
        return source;
    }

    OpportunityStatus status() {
        return status;
    }

    OpportunityResult result() {
        return result;
    }

    Instant openedAt() {
        return openedAt;
    }

    Instant lastActivityAt() {
        return lastActivityAt;
    }

    Instant closedAt() {
        return closedAt;
    }

    private boolean isOpen() {
        return status == OpportunityStatus.OPEN;
    }

    private void closeAsUnanswered(Instant closedAt) {
        this.status = OpportunityStatus.CLOSED;
        this.result = OpportunityResult.NO_RESPONSE;
        this.closedAt = closedAt;
    }

    private void reopen(Instant now) {
        this.status = OpportunityStatus.OPEN;
        this.result = null;
        this.closedAt = null;
        this.lastActivityAt = now;
    }
}
