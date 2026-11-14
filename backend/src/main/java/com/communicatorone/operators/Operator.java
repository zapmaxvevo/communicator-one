package com.communicatorone.operators;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Locale;
import java.util.UUID;
import org.hibernate.annotations.TenantId;
import org.springframework.security.crypto.password.PasswordEncoder;

@Entity
@Table(name = "operators")
class Operator {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(name = "organization_id")
    private UUID organizationId;

    private String email;

    private String name;

    private String passwordHash;

    @Enumerated(EnumType.STRING)
    private OperatorRole role;

    @Enumerated(EnumType.STRING)
    private Availability availability;

    protected Operator() {
    }

    private Operator(String email, String name, OperatorRole role, String passwordHash) {
        this.email = email;
        this.name = name;
        this.role = role;
        this.passwordHash = passwordHash;
        this.availability = Availability.AWAY;
    }

    static Operator register(String email, String name, OperatorRole role, String rawPassword,
            PasswordEncoder passwordEncoder) {
        requireText(email, "An operator needs an email");
        requireText(name, "An operator needs a name");
        if (role == null) {
            throw new IllegalArgumentException("An operator needs a role");
        }
        requireText(rawPassword, "An operator needs a password");
        return new Operator(normalizedEmail(email), name, role, passwordEncoder.encode(rawPassword));
    }

    static String normalizedEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    boolean acceptsPassword(String rawPassword, PasswordEncoder passwordEncoder) {
        return passwordEncoder.matches(rawPassword, passwordHash);
    }

    UUID id() {
        return id;
    }

    UUID organizationId() {
        return organizationId;
    }

    String email() {
        return email;
    }

    String name() {
        return name;
    }

    OperatorRole role() {
        return role;
    }

    Availability availability() {
        return availability;
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}
