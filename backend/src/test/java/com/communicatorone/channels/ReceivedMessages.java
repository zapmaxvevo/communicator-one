package com.communicatorone.channels;

import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
class ReceivedMessages {

    private final Queue<MessageReceived> received = new ConcurrentLinkedQueue<>();

    @EventListener
    void on(MessageReceived event) {
        received.add(event);
    }

    void forget() {
        received.clear();
    }

    List<MessageReceived> all() {
        return List.copyOf(received);
    }

    List<MessageReceived> in(UUID organizationId) {
        return received.stream().filter(event -> event.organizationId().equals(organizationId)).toList();
    }
}
