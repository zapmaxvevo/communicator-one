package com.communicatorone.channels;

import com.communicatorone.messaging.ChannelType;
import java.util.UUID;

public record MessageReceived(UUID organizationId, UUID messageId, UUID personId, UUID opportunityId,
        UUID channelAccountId, ChannelType channel) {
}
