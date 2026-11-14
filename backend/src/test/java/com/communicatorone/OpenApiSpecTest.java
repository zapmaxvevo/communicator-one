package com.communicatorone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = {
        "springdoc.writer-with-order-by-keys=true",
        "springdoc.writer-with-default-pretty-printer=true"
})
@Import(TestcontainersConfiguration.class)
class OpenApiSpecTest {

    private static final Path COMMITTED_SPEC = Path.of(System.getProperty("basedir", ""), "openapi.json")
            .toAbsolutePath();
    private static final String OUT_OF_DATE_MESSAGE = COMMITTED_SPEC + " was out of date and has been regenerated: "
            + "review and commit it, then run npm run generate:api in web/";

    private final MockMvcTester mvc;

    @Autowired
    OpenApiSpecTest(WebApplicationContext context) {
        this.mvc = MockMvcTester.from(context);
    }

    @Test
    void committedOpenApiSpecMatchesTheApiServedByTheBackend() throws IOException {
        // Given
        var committedSpec = Files.exists(COMMITTED_SPEC)
                ? withNormalizedLineEndings(Files.readString(COMMITTED_SPEC))
                : "";

        // When
        var result = mvc.get().uri("/v3/api-docs").exchange();
        var servedSpec = withNormalizedLineEndings(result.getResponse().getContentAsString(StandardCharsets.UTF_8));

        // Then
        assertThat(result).hasStatusOk();
        if (!servedSpec.equals(committedSpec)) {
            Files.writeString(COMMITTED_SPEC, servedSpec + "\n");
            fail(OUT_OF_DATE_MESSAGE);
        }
    }

    private static String withNormalizedLineEndings(String content) {
        return content.replace("\r\n", "\n").stripTrailing();
    }
}
