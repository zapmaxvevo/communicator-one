package com.communicatorone.operators;

import com.communicatorone.organizations.CurrentOrganization;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

class OperatorFixture {

    private final CurrentOrganization currentOrganization;
    private final OperatorRepository operators;
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    OperatorFixture(CurrentOrganization currentOrganization, OperatorRepository operators,
            JdbcTemplate jdbcTemplate) {
        this.currentOrganization = currentOrganization;
        this.operators = operators;
        this.jdbcTemplate = jdbcTemplate;
    }

    UUID organization(String name) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO organizations (id, name) VALUES (?, ?)", id, name);
        return id;
    }

    RegisteredOperator operator(UUID organizationId, String name, OperatorRole role) {
        var email = name.toLowerCase(Locale.ROOT) + "-" + UUID.randomUUID() + "@communicator-one.test";
        return register(organizationId, email, name, role);
    }

    RegisteredOperator register(UUID organizationId, String email, String name, OperatorRole role) {
        var password = "password-of-" + UUID.randomUUID();
        var operatorId = currentOrganization.callAs(organizationId,
                () -> operators.save(Operator.register(email, name, role, password, passwordEncoder)).id());
        return new RegisteredOperator(operatorId, organizationId, email, name, role, password);
    }

    record RegisteredOperator(UUID id, UUID organizationId, String email, String name, OperatorRole role,
            String password) {
    }
}
