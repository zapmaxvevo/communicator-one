package com.communicatorone;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class CommunicatorOneApplicationTest {

    @Value("${local.server.port}")
    private int port;

    private RestClient anonymousClient;

    @BeforeEach
    void setUp() {
        anonymousClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultStatusHandler(status -> true, (request, response) -> {})
                .build();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/actuator/health",
            "/actuator/health/liveness",
            "/actuator/health/readiness"})
    void healthAndItsProbesReportApplicationUpToAnonymousMonitoring(String healthPath) {
        // Given
        var anonymousRequest = anonymousClient.get().uri(healthPath);

        // When
        var response = anonymousRequest.retrieve()
                .toEntity(new ParameterizedTypeReference<Map<String, Object>>() {});

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/organizations",
            "/v3/api-docs",
            "/swagger-ui/index.html",
            "/actuator",
            "/actuator/env"})
    void protectedEndpointsRejectRequestsWithoutCredentials(String protectedPath) {
        // Given
        var anonymousRequest = anonymousClient.get().uri(protectedPath);

        // When
        var response = anonymousRequest.retrieve().toBodilessEntity();

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
