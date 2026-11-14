package com.communicatorone.messaging;

import java.util.UUID;

public record RecordedMessage(UUID id, boolean duplicate) {
}
