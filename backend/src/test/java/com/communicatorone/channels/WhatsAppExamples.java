package com.communicatorone.channels;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;

final class WhatsAppExamples {

    static final String PHONE_NUMBER_ID = "106540352242922";
    static final String TEXT_MESSAGE_ID = "wamid.HBgNNTQ5MTE1NTU1MDEwMRUCABIYFjNFQjBBMUYyQzk4RDQ3RTVCNjAxAA==";

    private WhatsAppExamples() {
    }

    static WebhookPayload payload(String name) {
        try {
            return new WebhookPayload(new ClassPathResource("channels/whatsapp/" + name)
                    .getContentAsString(StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
