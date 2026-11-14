package com.communicatorone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularStructureTest {

    @Test
    void applicationIsOrganizedInTheSevenDomainModules() {
        // Given
        var modules = ApplicationModules.of(CommunicatorOneApplication.class);

        // When
        var declaredModules = modules.stream()
                .map(module -> module.getIdentifier().toString())
                .toList();

        // Then
        assertThat(declaredModules).containsExactlyInAnyOrder(
                "organizations", "people", "opportunities", "messaging", "channels", "assignment", "notifications");
    }

    @Test
    void domainModulesOnlyDependOnEachOthersPublicApi() {
        // Given
        var modules = ApplicationModules.of(CommunicatorOneApplication.class);

        // When / Then
        assertThatCode(modules::verify).doesNotThrowAnyException();
    }
}
