package com.communicatorone.organizations;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "isolation_probes")
class IsolationProbe {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String label;

    @TenantId
    @Column(name = "organization_id")
    private UUID organizationId;

    protected IsolationProbe() {
    }

    private IsolationProbe(String label) {
        this.label = label;
    }

    static IsolationProbe labeled(String label) {
        return new IsolationProbe(label);
    }

    UUID id() {
        return id;
    }
}
