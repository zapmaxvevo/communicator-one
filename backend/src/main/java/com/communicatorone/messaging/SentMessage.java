package com.communicatorone.messaging;

import java.util.UUID;

public record SentMessage(UUID personId, UUID opportunityId, UUID channelAccountId, ChannelType channel,
        SenderType senderType, String externalId, MessageContent content) {
}
