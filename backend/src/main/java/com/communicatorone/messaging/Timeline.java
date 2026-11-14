package com.communicatorone.messaging;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;

@Service
public class Timeline {

    private final MessageRepository messages;
    private final TransactionTemplate writing;
    private final Clock clock;

    Timeline(MessageRepository messages, TransactionTemplate writing, Clock clock) {
        this.messages = messages;
        this.writing = writing;
        this.clock = clock;
    }

    public RecordedMessage recordReceived(ReceivedMessage received) {
        requireNoOpenTransaction();
        return record(Message.received(received, clock.instant()));
    }

    public RecordedMessage recordSent(SentMessage sent) {
        requireNoOpenTransaction();
        return record(Message.sent(sent, clock.instant()));
    }

    @Transactional
    public StatusUpdateOutcome applyStatus(UUID channelAccountId, String externalId, MessageStatus status) {
        requireProviderKey(channelAccountId, externalId);
        Objects.requireNonNull(status, "status");
        return messages.findByChannelAccountIdAndExternalId(channelAccountId, externalId)
                .map(message -> message.advanceTo(status)
                        ? StatusUpdateOutcome.ADVANCED
                        : StatusUpdateOutcome.UNCHANGED)
                .orElse(StatusUpdateOutcome.UNKNOWN_MESSAGE);
    }

    public boolean isRecorded(UUID channelAccountId, String externalId) {
        requireProviderKey(channelAccountId, externalId);
        return messages.findIdByChannelAccountIdAndExternalId(channelAccountId, externalId).isPresent();
    }

    private static void requireProviderKey(UUID channelAccountId, String externalId) {
        Objects.requireNonNull(channelAccountId, "channelAccountId");
        Assert.hasText(externalId, "A message is recognized by the id its provider gave it");
    }

    private static void requireNoOpenTransaction() {
        Assert.state(!TransactionSynchronizationManager.isSynchronizationActive(),
                "A message is recorded in its own transaction, not inside an open one");
    }

    private RecordedMessage record(Message message) {
        var alreadyRecorded = messages.findIdByChannelAccountIdAndExternalId(message.channelAccountId(),
                message.externalId());
        if (alreadyRecorded.isPresent()) {
            return new RecordedMessage(alreadyRecorded.get(), true);
        }
        try {
            return new RecordedMessage(writing.execute(transaction -> messages.saveAndFlush(message).id()), false);
        } catch (DataIntegrityViolationException concurrentRecording) {
            return messages.findIdByChannelAccountIdAndExternalId(message.channelAccountId(), message.externalId())
                    .map(winner -> new RecordedMessage(winner, true))
                    .orElseThrow(() -> concurrentRecording);
        }
    }
}
