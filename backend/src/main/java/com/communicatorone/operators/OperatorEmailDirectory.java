package com.communicatorone.operators;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class OperatorEmailDirectory {

    private final JdbcTemplate jdbcTemplate;

    OperatorEmailDirectory(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    Optional<UUID> organizationOf(String email) {
        return jdbcTemplate.queryForList("SELECT organization_id FROM operators WHERE email = ?", UUID.class, email)
                .stream()
                .findFirst();
    }
}
