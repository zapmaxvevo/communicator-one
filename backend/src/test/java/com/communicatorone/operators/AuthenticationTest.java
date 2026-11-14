package com.communicatorone.operators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.communicatorone.MutableClock;
import com.communicatorone.TestcontainersConfiguration;
import com.communicatorone.operators.OperatorFixture.RegisteredOperator;
import com.communicatorone.organizations.CurrentOrganization;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.TestConstructor;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class AuthenticationTest {

    private static final Instant NOW = MutableClock.INITIAL_INSTANT;
    private static final Duration TOKEN_LIFETIME = Duration.ofHours(8);
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() {};

    private final OperatorFixture fixture;
    private final JdbcTemplate jdbcTemplate;
    private final MutableClock clock;
    private final JwtEncoder jwtEncoder;
    private final JwtDecoder jwtDecoder;
    private final RestClient client;

    private UUID acme;
    private UUID globex;
    private RegisteredOperator ana;
    private RegisteredOperator bruno;

    AuthenticationTest(CurrentOrganization currentOrganization, OperatorRepository operators,
            JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder, MutableClock clock, JwtEncoder jwtEncoder,
            JwtDecoder jwtDecoder, @Value("${local.server.port}") int port) {
        this.fixture = new OperatorFixture(currentOrganization, operators, jdbcTemplate, passwordEncoder);
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.jwtEncoder = jwtEncoder;
        this.jwtDecoder = jwtDecoder;
        this.client = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE,
                        MediaType.APPLICATION_PROBLEM_JSON_VALUE)
                .defaultStatusHandler(status -> true, (request, response) -> {})
                .build();
    }

    @BeforeEach
    void createTwoOrganizationsWithOneOperatorEach() {
        acme = fixture.organization("Acme");
        globex = fixture.organization("Globex");
        ana = fixture.operator(acme, "Ana", OperatorRole.OPERATOR);
        bruno = fixture.operator(globex, "Bruno", OperatorRole.SUPERVISOR);
    }

    @AfterEach
    void restoreTheClock() {
        clock.setTo(NOW);
    }

    @Test
    void signedInOperatorSeesTheirOwnProfile() {
        // When
        var signIn = signIn(ana.email(), ana.password());
        var profile = profileWith(accessTokenOf(signIn));

        // Then
        assertThat(signIn.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(signIn.getBody()).containsOnlyKeys("accessToken", "expiresAt");
        var expiresAt = Instant.parse((String) signIn.getBody().get("expiresAt"));
        assertThat(expiresAt).isEqualTo(NOW.plus(TOKEN_LIFETIME));
        assertThat(expirationOf(accessTokenOf(signIn))).isEqualTo(expiresAt);
        assertThat(profile.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(profile.getBody()).isEqualTo(profileOf(ana));
    }

    @Test
    void emailIsCaseInsensitiveAtSignIn() {
        // When
        var signIn = signIn(ana.email().toUpperCase(Locale.ROOT), ana.password());
        var profile = profileWith(accessTokenOf(signIn));

        // Then
        assertThat(signIn.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(profile.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(profile.getBody()).isEqualTo(profileOf(ana));
    }

    @Test
    void operatorOfAnotherOrganizationSignsInToTheirOwnOrganization() {
        // When
        var signIn = signIn(bruno.email(), bruno.password());
        var profile = profileWith(accessTokenOf(signIn));

        // Then
        assertThat(signIn.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(profile.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(profile.getBody()).isEqualTo(profileOf(bruno));
    }

    @Test
    void tokenClaimingAnotherOrganizationDoesNotRevealTheOperator() {
        // Given
        var token = tokenSignedWith(jwtEncoder, claims -> claims
                .subject(ana.id().toString())
                .claim("organization_id", globex.toString()));

        // When
        var profile = client.get().uri("/api/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .toEntity(String.class);

        // Then
        assertThat(profile.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(profile.getBody()).doesNotContain(ana.email(), ana.name(), acme.toString());
    }

    @Test
    void wrongPasswordIsRejected() {
        // When
        var signIn = signIn(ana.email(), "not-" + ana.password());

        // Then
        assertThat(signIn.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(signIn.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(signIn.getBody())
                .containsEntry("status", 401)
                .containsEntry("detail", "Invalid email or password")
                .doesNotContainKey("accessToken");
    }

    @Test
    void unknownEmailIsRejectedExactlyLikeAWrongPassword() {
        // Given
        var wrongPassword = signIn(ana.email(), "not-" + ana.password());

        // When
        var unknownEmail = signIn("nobody-" + UUID.randomUUID() + "@acme.test", ana.password());

        // Then
        assertThat(unknownEmail.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownEmail.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(unknownEmail.getStatusCode()).isEqualTo(wrongPassword.getStatusCode());
        assertThat(unknownEmail.getHeaders().getContentType()).isEqualTo(wrongPassword.getHeaders().getContentType());
        assertThat(unknownEmail.getBody()).isNotEmpty().isEqualTo(wrongPassword.getBody());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"password\": \"secret\"}",
            "{\"email\": \"ana@acme.test\"}",
            "{\"email\": \"\", \"password\": \"secret\"}",
            "{\"email\": \"not-an-email\", \"password\": \"secret\"}",
            "{\"email\": \"ana@acme.test\", \"password\": \"   \"}",
            "{\"email\": \"ana@acme.test\", \"password\": \""
                    + "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
                    + "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\"}",
            "{\"email\": \"ana@acme.test\", "})
    void malformedSignInRequestIsRejectedAsBadRequest(String malformedRequest) {
        // When
        var signIn = client.post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(malformedRequest)
                .retrieve()
                .toEntity(String.class);

        // Then
        assertThat(signIn.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(signIn.getBody()).doesNotContain("accessToken");
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        // Given
        var anotherSecret = encoderWithSecret("another-secret-that-is-also-longer-than-32-bytes");
        var token = tokenSignedWith(anotherSecret, claims -> claims
                .subject(ana.id().toString())
                .claim("organization_id", acme.toString()));

        // When
        var profile = profileWith(token);

        // Then
        assertRejectedAsUnauthenticated(profile);
    }

    @Test
    void tokenIsStillAcceptedJustBeforeItExpires() {
        // Given
        var token = accessTokenOf(signIn(ana.email(), ana.password()));
        clock.advance(TOKEN_LIFETIME.plusSeconds(59));

        // When
        var profile = profileWith(token);

        // Then
        assertThat(profile.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(profile.getBody()).isEqualTo(profileOf(ana));
    }

    @Test
    void expiredTokenIsRejected() {
        // Given
        var token = accessTokenOf(signIn(ana.email(), ana.password()));
        clock.advance(TOKEN_LIFETIME.plusSeconds(61));

        // When
        var profile = profileWith(token);

        // Then
        assertRejectedAsUnauthenticated(profile);
    }

    @Test
    void tokenWithoutOrganizationIsRejected() {
        // Given
        var token = tokenSignedWith(jwtEncoder, claims -> claims.subject(ana.id().toString()));

        // When
        var profile = profileWith(token);

        // Then
        assertRejectedAsUnauthenticated(profile);
    }

    @Test
    void tokenWithoutExpirationIsRejected() {
        // Given
        var token = tokenSignedWith(jwtEncoder, claims -> claims
                .subject(ana.id().toString())
                .claim("organization_id", acme.toString())
                .claims(all -> all.remove("exp")));

        // When
        var profile = profileWith(token);

        // Then
        assertRejectedAsUnauthenticated(profile);
    }

    @Test
    void registeredOperatorIsStoredWithoutTheirPasswordInClearText() {
        // When
        var carla = fixture.operator(acme, "Carla", OperatorRole.SUPERVISOR);

        // Then
        var stored = jdbcTemplate.queryForMap(
                "SELECT organization_id, email, name, role, availability, password_hash FROM operators WHERE id = ?",
                carla.id());
        assertThat(stored)
                .containsEntry("organization_id", acme)
                .containsEntry("email", carla.email())
                .containsEntry("name", "Carla")
                .containsEntry("role", "SUPERVISOR")
                .containsEntry("availability", "AWAY");
        assertThat((String) stored.get("password_hash")).isNotBlank().doesNotContain(carla.password());
    }

    @Test
    void emailAlreadyUsedInAnotherOrganizationCannotBeRegistered() {
        // Given
        var sameEmailWithOtherCase = " " + ana.email().toUpperCase(Locale.ROOT) + " ";

        // When
        var rejection = catchThrowable(
                () -> fixture.register(globex, sameEmailWithOtherCase, "Ana Globex", OperatorRole.OPERATOR));

        // Then
        assertThat(rejection).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbcTemplate.queryForList("SELECT organization_id FROM operators WHERE email = ?",
                UUID.class, ana.email())).containsExactly(acme);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM operators WHERE name = ? AND organization_id = ?",
                Integer.class, "Ana Globex", globex)).isZero();
    }

    private ResponseEntity<Map<String, Object>> signIn(String email, String password) {
        return client.post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", password))
                .retrieve()
                .toEntity(JSON_OBJECT);
    }

    private ResponseEntity<Map<String, Object>> profileWith(String accessToken) {
        return client.get().uri("/api/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .toEntity(JSON_OBJECT);
    }

    private static String accessTokenOf(ResponseEntity<Map<String, Object>> signIn) {
        assertThat(signIn.getStatusCode()).as("sign-in status").isEqualTo(HttpStatus.OK);
        var accessToken = signIn.getBody().get("accessToken");
        assertThat(accessToken).as("access token").isInstanceOf(String.class);
        return (String) accessToken;
    }

    private static Map<String, Object> profileOf(RegisteredOperator operator) {
        return Map.of(
                "id", operator.id().toString(),
                "organizationId", operator.organizationId().toString(),
                "email", operator.email(),
                "name", operator.name(),
                "role", operator.role().name(),
                "availability", "AWAY");
    }

    private static void assertRejectedAsUnauthenticated(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
        assertThat(response.getBody()).isNull();
    }

    private String tokenSignedWith(JwtEncoder encoder, Consumer<JwtClaimsSet.Builder> claims) {
        var claimsSet = JwtClaimsSet.builder()
                .issuedAt(clock.instant())
                .expiresAt(clock.instant().plus(Duration.ofHours(1)));
        claims.accept(claimsSet);
        var parameters = JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claimsSet.build());
        return encoder.encode(parameters).getTokenValue();
    }

    private static JwtEncoder encoderWithSecret(String secret) {
        return NimbusJwtEncoder.withSecretKey(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                .build();
    }

    private Instant expirationOf(String accessToken) {
        return jwtDecoder.decode(accessToken).getExpiresAt();
    }
}
