package com.communicatorone.people;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "person_identities")
class PersonIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(name = "organization_id")
    private UUID organizationId;

    @Enumerated(EnumType.STRING)
    private IdentityKind kind;

    private String value;

    protected PersonIdentity() {
    }

    PersonIdentity(ContactIdentity identity) {
        this.kind = identity.kind();
        this.value = identity.value();
    }
}
