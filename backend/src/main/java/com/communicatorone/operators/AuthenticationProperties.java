package com.communicatorone.operators;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("communicator-one.authentication")
record AuthenticationProperties(String jwtSecret, @DefaultValue("8h") Duration tokenLifetime) {

    private static final int MINIMUM_SECRET_BYTES = 32;

    AuthenticationProperties {
        if (jwtSecret == null || jwtSecret.isBlank()
                || jwtSecret.getBytes(StandardCharsets.UTF_8).length < MINIMUM_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "communicator-one.authentication.jwt-secret must have at least 32 bytes");
        }
    }

    @Override
    public String toString() {
        return "AuthenticationProperties[jwtSecret=[REDACTED], tokenLifetime=" + tokenLifetime + "]";
    }
}
