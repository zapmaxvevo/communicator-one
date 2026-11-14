package com.communicatorone;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

    @Test
    void runningApplicationReportsItselfHealthyToAnonymousMonitoring() {
        // Given
        var healthEndpoint = "/actuator/health";

        // When
        var response = anonymousClient.get()
                .uri(healthEndpoint)
                .retrieve()
                .toEntity(new ParameterizedTypeReference<Map<String, Object>>() {});

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
    }

    @Test
    void apiRejectsRequestsWithoutCredentials() {
        // Given
        var protectedEndpoint = "/api/organizations";

        // When
        var response = anonymousClient.get()
                .uri(protectedEndpoint)
                .retrieve()
                .toBodilessEntity();

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
