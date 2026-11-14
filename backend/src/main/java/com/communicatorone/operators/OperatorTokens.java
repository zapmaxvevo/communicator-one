package com.communicatorone.operators;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

@Component
class OperatorTokens implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final String ORGANIZATION_CLAIM = "organization_id";

    private final JwtEncoder jwtEncoder;
    private final AuthenticationProperties properties;
    private final Clock clock;

    OperatorTokens(JwtEncoder jwtEncoder, AuthenticationProperties properties, Clock clock) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
        this.clock = clock;
    }

    IssuedToken issueFor(Operator operator) {
        var issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        var expiresAt = issuedAt.plus(properties.tokenLifetime());
        var claims = JwtClaimsSet.builder()
                .subject(operator.id().toString())
                .claim(ORGANIZATION_CLAIM, operator.organizationId().toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();
        var header = JwsHeader.with(MacAlgorithm.HS256).build();
        var token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims));
        return new IssuedToken(token.getTokenValue(), expiresAt);
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        var operator = new AuthenticatedOperator(uuidClaim(jwt.getSubject()),
                uuidClaim(jwt.getClaimAsString(ORGANIZATION_CLAIM)));
        return UsernamePasswordAuthenticationToken.authenticated(operator, null, List.of());
    }

    private static UUID uuidClaim(String value) {
        if (value == null) {
            throw new InvalidBearerTokenException("The access token does not identify an operator");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException malformedIdentifier) {
            throw new InvalidBearerTokenException("The access token does not identify an operator");
        }
    }
}
