package com.communicatorone.channels;

import com.communicatorone.messaging.ChannelType;

class InvalidWebhookPayloadException extends RuntimeException {

    InvalidWebhookPayloadException(ChannelType type) {
        super("Invalid " + type + " webhook payload");
    }
}
