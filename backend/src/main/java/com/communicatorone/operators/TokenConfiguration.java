package com.communicatorone.operators;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
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
        decoder.setJwtValidator(timestampValidator);
        return decoder;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    private static SecretKey signingKey(AuthenticationProperties properties) {
        return new SecretKeySpec(properties.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
