package com.communicatorone.channels;

import com.communicatorone.messaging.MessageStatus;
import org.springframework.util.Assert;

record StatusUpdate(String externalAccountId, String externalId, MessageStatus status) {

    StatusUpdate {
        Assert.hasText(externalAccountId, "A status update needs the account of its message");
        Assert.hasText(externalId, "A status update needs the id of its message");
        Assert.notNull(status, "A status update needs its status");
    }
}
