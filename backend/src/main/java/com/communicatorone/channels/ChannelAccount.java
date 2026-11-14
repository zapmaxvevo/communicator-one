package com.communicatorone.channels;

import com.communicatorone.messaging.ChannelType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.TenantId;
import org.springframework.util.Assert;

@Entity
@Table(name = "channel_accounts")
class ChannelAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(name = "organization_id")
    private UUID organizationId;

    @Enumerated(EnumType.STRING)
    private ChannelType channelType;

    private String externalAccountId;

    private String displayName;

    private boolean allowsOutbound;

    protected ChannelAccount() {
    }

    private ChannelAccount(ChannelType channelType, String externalAccountId, String displayName,
            boolean allowsOutbound) {
        this.channelType = channelType;
        this.externalAccountId = externalAccountId;
        this.displayName = displayName;
        this.allowsOutbound = allowsOutbound;
    }

    static ChannelAccount register(ChannelType channelType, String externalAccountId, String displayName,
            boolean allowsOutbound) {
        Assert.notNull(channelType, "A channel account needs its channel type");
        Assert.hasText(displayName, "A channel account needs a display name");
        return new ChannelAccount(channelType, normalizedExternalAccountId(externalAccountId), displayName,
                allowsOutbound);
    }

    static String normalizedExternalAccountId(String externalAccountId) {
        Assert.hasText(externalAccountId, "A channel account needs the id its provider gave it");
        return externalAccountId.strip();
    }

    UUID id() {
        return id;
    }

    UUID organizationId() {
        return organizationId;
    }

    ChannelType channelType() {
        return channelType;
    }

    boolean allowsOutbound() {
        return allowsOutbound;
    }
}
