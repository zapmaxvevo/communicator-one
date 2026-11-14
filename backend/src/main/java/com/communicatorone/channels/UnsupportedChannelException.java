package com.communicatorone.channels;

import com.communicatorone.messaging.ChannelType;

class UnsupportedChannelException extends RuntimeException {

    UnsupportedChannelException(ChannelType type) {
        super("No channel adapter handles " + type);
    }
}
