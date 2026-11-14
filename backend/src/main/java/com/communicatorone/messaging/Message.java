package com.communicatorone.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.TenantId;
import org.springframework.util.Assert;

@Entity
@Table(name = "messages")
class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(name = "organization_id")
    private UUID organizationId;

    private UUID personId;

    private UUID opportunityId;

    private UUID channelAccountId;

    @Enumerated(EnumType.STRING)
    private ChannelType channel;

    @Enumerated(EnumType.STRING)
    private SenderType senderType;

    private String externalId;

    private String text;

    @Enumerated(EnumType.STRING)
    private MessageStatus status;

    private Instant occurredAt;

    private Instant recordedAt;

    protected Message() {
    }

    private Message(UUID personId, UUID opportunityId, UUID channelAccountId, ChannelType channel,
            SenderType senderType, String externalId, MessageContent content, MessageStatus status,
            Instant occurredAt, Instant recordedAt) {
        Assert.hasText(externalId, "A message needs the id its provider gave it");
        this.personId = Objects.requireNonNull(personId, "personId");
        this.opportunityId = Objects.requireNonNull(opportunityId, "opportunityId");
        this.channelAccountId = Objects.requireNonNull(channelAccountId, "channelAccountId");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.senderType = Objects.requireNonNull(senderType, "senderType");
        this.externalId = externalId;
        this.text = Objects.requireNonNull(content, "content").text();
        this.status = status;
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        this.recordedAt = Objects.requireNonNull(recordedAt, "recordedAt");
    }

    static Message received(ReceivedMessage received, Instant now) {
        Objects.requireNonNull(received, "received");
        return new Message(received.personId(), received.opportunityId(), received.channelAccountId(),
                received.channel(), SenderType.CUSTOMER, received.externalId(), received.content(),
                MessageStatus.DELIVERED, received.occurredAt(), now);
    }

    static Message sent(SentMessage sent, Instant now) {
        Objects.requireNonNull(sent, "sent");
        if (sent.senderType() == SenderType.CUSTOMER) {
            throw new IllegalArgumentException("A sent message comes from the organization, not the customer");
        }
        return new Message(sent.personId(), sent.opportunityId(), sent.channelAccountId(), sent.channel(),
                sent.senderType(), sent.externalId(), sent.content(), MessageStatus.SENT, now, now);
    }

    boolean advanceTo(MessageStatus reported) {
        Objects.requireNonNull(reported, "reported");
        if (!reported.isAheadOf(status)) {
            return false;
        }
        status = reported;
        return true;
    }

    UUID id() {
        return id;
    }

    UUID channelAccountId() {
        return channelAccountId;
    }

    String externalId() {
        return externalId;
    }

    MessageStatus status() {
        return status;
    }
}
