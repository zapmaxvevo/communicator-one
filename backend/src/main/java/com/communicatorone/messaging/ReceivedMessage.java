package com.communicatorone.messaging;

import java.time.Instant;
import java.util.UUID;

public record ReceivedMessage(UUID personId, UUID opportunityId, UUID channelAccountId, ChannelType channel,
        String externalId, MessageContent content, Instant occurredAt) {
}
