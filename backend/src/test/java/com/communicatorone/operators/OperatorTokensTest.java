package com.communicatorone.operators;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class OperatorTokensTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    private final OperatorTokens tokens = new OperatorTokens(
            NimbusJwtEncoder.withSecretKey(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                    .build(),
            new AuthenticationProperties(SECRET, Duration.ofHours(8)),
            Clock.systemUTC());

    @Test
    void authenticationFromATokenIsNamedAfterTheOperator() {
        // Given
        var operatorId = UUID.randomUUID();
        var jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(operatorId.toString())
                .claim("organization_id", UUID.randomUUID().toString())
                .build();

        // When
        var authentication = tokens.convert(jwt);

        // Then
        assertThat(authentication.getName()).isEqualTo(operatorId.toString());
    }
}
