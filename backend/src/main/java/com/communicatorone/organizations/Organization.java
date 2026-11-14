package com.communicatorone.organizations;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "organizations")
class Organization {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String name;

    protected Organization() {
    }

    private Organization(String name) {
        this.name = name;
    }

    static Organization named(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("An organization needs a name");
        }
        return new Organization(name);
    }

    UUID id() {
        return id;
    }
}
