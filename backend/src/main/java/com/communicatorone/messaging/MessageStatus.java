package com.communicatorone.messaging;

public enum MessageStatus {
    SENT(0),
    FAILED(1),
    DELIVERED(2),
    READ(3),
    DELETED(4);

    private final int progress;

    MessageStatus(int progress) {
        this.progress = progress;
    }

    boolean isAheadOf(MessageStatus other) {
        return progress > other.progress;
    }
}
