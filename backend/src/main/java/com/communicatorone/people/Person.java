package com.communicatorone.people;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "people")
class Person {

    private static final int MAX_DISPLAY_NAME_LENGTH = 200;
    private static final Pattern UNPRINTABLE_AND_SPACE_RUNS = Pattern.compile("[\\p{Cc}\\p{Z}\\p{Cs}]+");

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(name = "organization_id")
    private UUID organizationId;

    private Instant createdAt;

    private String displayName;

    @OneToMany(cascade = CascadeType.ALL)
    @JoinColumn(name = "person_id", nullable = false, updatable = false)
    private List<PersonIdentity> identities = new ArrayList<>();

    protected Person() {
    }

    private Person(Instant createdAt, String displayName, PersonIdentity identity) {
        this.createdAt = createdAt;
        this.displayName = displayName;
        this.identities.add(identity);
    }

    static Person identifiedBy(ContactIdentity identity, String displayName, Clock clock) {
        return new Person(clock.instant(), normalizedDisplayName(displayName), new PersonIdentity(identity));
    }

    private static String normalizedDisplayName(String displayName) {
        if (displayName == null) {
            return null;
        }
        var cleaned = UNPRINTABLE_AND_SPACE_RUNS.matcher(displayName).replaceAll(" ").strip();
        if (cleaned.codePointCount(0, cleaned.length()) > MAX_DISPLAY_NAME_LENGTH) {
            cleaned = cleaned.substring(0, cleaned.offsetByCodePoints(0, MAX_DISPLAY_NAME_LENGTH)).strip();
        }
        return cleaned.isEmpty() ? null : cleaned;
    }

    UUID id() {
        return id;
    }
}
