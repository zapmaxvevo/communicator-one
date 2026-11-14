package com.communicatorone.channels;

import com.communicatorone.messaging.ChannelType;
import com.communicatorone.messaging.MessageContent;
import com.communicatorone.people.ContactIdentity;
import java.util.List;

interface ChannelAdapter {

    ChannelType type();

    List<InboundMessage> parseInbound(WebhookPayload payload);

    List<StatusUpdate> parseStatusUpdates(WebhookPayload payload);

    SendResult send(ContactIdentity to, ChannelAccount from, MessageContent content);
}
