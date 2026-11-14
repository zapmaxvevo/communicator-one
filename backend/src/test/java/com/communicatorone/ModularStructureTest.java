package com.communicatorone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularStructureTest {

    private static final ApplicationModules MODULES = ApplicationModules.of(CommunicatorOneApplication.class);

    @Test
    void applicationIsOrganizedInTheSevenDomainModules() {
        // When
        var declaredModules = MODULES.stream()
                .map(module -> module.getIdentifier().toString())
                .toList();

        // Then
        assertThat(declaredModules).containsExactlyInAnyOrder(
                "organizations", "people", "opportunities", "messaging", "channels", "assignment", "notifications");
    }

    @Test
    void domainModulesOnlyDependOnEachOthersPublicApi() {
        // When / Then
        assertThatCode(MODULES::verify).doesNotThrowAnyException();
    }
}
