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
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "people")
class Person {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(name = "organization_id")
    private UUID organizationId;

    private Instant createdAt;

    @OneToMany(cascade = CascadeType.ALL)
    @JoinColumn(name = "person_id", nullable = false, updatable = false)
    private List<PersonIdentity> identities = new ArrayList<>();

    protected Person() {
    }

    private Person(Instant createdAt, PersonIdentity identity) {
        this.createdAt = createdAt;
        this.identities.add(identity);
    }

    static Person identifiedBy(ContactIdentity identity, Clock clock) {
        return new Person(clock.instant(), new PersonIdentity(identity));
    }

    UUID id() {
        return id;
    }
}
