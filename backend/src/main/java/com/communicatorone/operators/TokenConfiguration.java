package com.communicatorone.operators;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthenticationProperties.class)
class TokenConfiguration {

    @Bean
    JwtEncoder jwtEncoder(AuthenticationProperties properties) {
        return NimbusJwtEncoder.withSecretKey(signingKey(properties)).build();
    }

    @Bean
    JwtDecoder jwtDecoder(AuthenticationProperties properties, Clock clock) {
        var decoder = NimbusJwtDecoder.withSecretKey(signingKey(properties)).macAlgorithm(MacAlgorithm.HS256).build();
        var timestampValidator = new JwtTimestampValidator();
        timestampValidator.setClock(clock);
        timestampValidator.setAllowEmptyExpiryClaim(false);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestampValidator,
                new JwtClaimValidator<>(JwtClaimNames.SUB, TokenConfiguration::isUuid),
                new JwtClaimValidator<>(OperatorTokens.ORGANIZATION_CLAIM, TokenConfiguration::isUuid)));
        return decoder;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    private static boolean isUuid(Object claim) {
        if (!(claim instanceof String value)) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException notAUuid) {
            return false;
        }
    }

    private static SecretKey signingKey(AuthenticationProperties properties) {
        return new SecretKeySpec(properties.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
