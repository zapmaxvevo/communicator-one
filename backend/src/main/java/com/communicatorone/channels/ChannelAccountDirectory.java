package com.communicatorone.channels;

import com.communicatorone.messaging.ChannelType;
import com.communicatorone.organizations.CurrentOrganization;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

@Component
class ChannelAccountDirectory {

    private final JdbcTemplate jdbcTemplate;
    private final CurrentOrganization currentOrganization;
    private final ChannelAccountRepository accounts;

    ChannelAccountDirectory(JdbcTemplate jdbcTemplate, CurrentOrganization currentOrganization,
            ChannelAccountRepository accounts) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentOrganization = currentOrganization;
        this.accounts = accounts;
    }

    Optional<ChannelAccount> resolve(ChannelType channelType, String externalAccountId) {
        Assert.notNull(channelType, "A channel account is resolved by its channel type");
        var normalized = ChannelAccount.normalizedExternalAccountId(externalAccountId);
        return organizationOf(channelType, normalized)
                .flatMap(organizationId -> currentOrganization.callAs(organizationId,
                        () -> accounts.findByChannelTypeAndExternalAccountId(channelType, normalized)));
    }

    private Optional<UUID> organizationOf(ChannelType channelType, String externalAccountId) {
        return jdbcTemplate.queryForList(
                        "SELECT organization_id FROM channel_accounts WHERE channel_type = ? AND external_account_id = ?",
                        UUID.class, channelType.name(), externalAccountId)
                .stream()
                .findFirst();
    }
}
