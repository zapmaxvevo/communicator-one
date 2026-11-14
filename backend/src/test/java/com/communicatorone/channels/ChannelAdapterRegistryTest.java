package com.communicatorone.channels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.communicatorone.messaging.ChannelType;
import com.communicatorone.messaging.MessageContent;
import com.communicatorone.people.ContactIdentity;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChannelAdapterRegistryTest {

    @Test
    void registryReturnsTheAdapterOfItsChannelType() {
        // Given
        var sandbox = new SandboxChannelAdapter();
        var whatsApp = new WhatsAppAdapterStandIn();
        var registry = new ChannelAdapterRegistry(List.of(sandbox, whatsApp));

        // When
        var sandboxAdapter = registry.adapterFor(ChannelType.SANDBOX);
        var whatsAppAdapter = registry.adapterFor(ChannelType.WHATSAPP);

        // Then
        assertThat(sandboxAdapter).isSameAs(sandbox);
        assertThat(whatsAppAdapter).isSameAs(whatsApp);
    }

    @Test
    void channelTypeWithoutAdapterIsRejected() {
        // Given
        var registry = new ChannelAdapterRegistry(List.of(new SandboxChannelAdapter()));

        // When
        var rejection = catchThrowable(() -> registry.adapterFor(ChannelType.WHATSAPP));

        // Then
        assertThat(rejection).isInstanceOf(UnsupportedChannelException.class)
                .hasMessage("No channel adapter handles WHATSAPP");
    }

    @Test
    void twoAdaptersForTheSameChannelTypeAreRejected() {
        // Given
        var adapters = List.<ChannelAdapter>of(new SandboxChannelAdapter(), new AnotherSandboxAdapter());

        // When
        var rejection = catchThrowable(() -> new ChannelAdapterRegistry(adapters));

        // Then
        assertThat(rejection).isInstanceOf(IllegalStateException.class)
                .hasMessage("Channel type SANDBOX has two adapters: SandboxChannelAdapter and AnotherSandboxAdapter");
    }

    private abstract static class AdapterStandIn implements ChannelAdapter {

        @Override
        public List<InboundMessage> parseInbound(WebhookPayload payload) {
            throw new UnsupportedOperationException("The registry does not parse webhooks");
        }

        @Override
        public List<StatusUpdate> parseStatusUpdates(WebhookPayload payload) {
            throw new UnsupportedOperationException("The registry does not parse webhooks");
        }

        @Override
        public SendResult send(ContactIdentity to, ChannelAccount from, MessageContent content) {
            throw new UnsupportedOperationException("The registry does not send messages");
        }
    }

    private static final class WhatsAppAdapterStandIn extends AdapterStandIn {

        @Override
        public ChannelType type() {
            return ChannelType.WHATSAPP;
        }
    }

    private static final class AnotherSandboxAdapter extends AdapterStandIn {

        @Override
        public ChannelType type() {
            return ChannelType.SANDBOX;
        }
    }
}
