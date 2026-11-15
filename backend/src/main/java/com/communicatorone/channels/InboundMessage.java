package com.communicatorone.channels;

import com.communicatorone.messaging.MessageContent;
import com.communicatorone.people.ContactIdentity;
import java.time.Instant;
import org.springframework.util.Assert;

record InboundMessage(String externalAccountId, ContactIdentity sender, String externalId, MessageContent content,
        Instant occurredAt, String senderDisplayName) {

    InboundMessage {
        Assert.hasText(externalAccountId, "An inbound message needs the account it was sent to");
        Assert.notNull(sender, "An inbound message needs its sender");
        Assert.hasText(externalId, "An inbound message needs the id its provider gave it");
        Assert.notNull(content, "An inbound message needs its content");
        Assert.notNull(occurredAt, "An inbound message needs the time it was sent");
        Assert.doesNotContain(externalAccountId, "\0", "An inbound message cannot contain NUL in its account");
        Assert.doesNotContain(sender.value(), "\0", "An inbound message cannot contain NUL in its sender");
        Assert.doesNotContain(externalId, "\0", "An inbound message cannot contain NUL in its id");
        Assert.doesNotContain(content.text(), "\0", "An inbound message cannot contain NUL in its text");
    }

    @Override
    public String toString() {
        return "InboundMessage[" + externalAccountId + ", " + sender + "]";
    }
}
