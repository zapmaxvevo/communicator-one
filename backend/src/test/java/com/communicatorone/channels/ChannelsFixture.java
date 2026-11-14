package com.communicatorone.channels;

import com.communicatorone.messaging.ChannelType;
import com.communicatorone.organizations.CurrentOrganization;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

class ChannelsFixture {

    private final JdbcTemplate jdbcTemplate;
    private final CurrentOrganization currentOrganization;
    private final ChannelAccountRepository accounts;

    ChannelsFixture(JdbcTemplate jdbcTemplate, CurrentOrganization currentOrganization,
            ChannelAccountRepository accounts) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentOrganization = currentOrganization;
        this.accounts = accounts;
    }

    UUID organization(String name) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO organizations (id, name) VALUES (?, ?)", id, name);
        return id;
    }

    String externalAccountId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    RegisteredAccount account(UUID organizationId, ChannelType channelType, String externalAccountId,
            boolean allowsOutbound) {
        var id = currentOrganization.callAs(organizationId, () -> accounts.save(
                ChannelAccount.register(channelType, externalAccountId, "Ventas", allowsOutbound)).id());
        return new RegisteredAccount(id, organizationId, channelType, allowsOutbound);
    }

    SandboxAccount sandboxAccount(UUID organizationId) {
        var externalAccountId = externalAccountId("sandbox");
        var account = account(organizationId, ChannelType.SANDBOX, externalAccountId, true);
        return new SandboxAccount(account.id(), organizationId, externalAccountId);
    }

    int accountsOf(UUID organizationId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM channel_accounts WHERE organization_id = ?",
                Integer.class, organizationId);
    }

    record RegisteredAccount(UUID id, UUID organizationId, ChannelType channelType, boolean allowsOutbound) {

        static RegisteredAccount of(ChannelAccount account) {
            return new RegisteredAccount(account.id(), account.organizationId(), account.channelType(),
                    account.allowsOutbound());
        }
    }

    record SandboxAccount(UUID id, UUID organizationId, String externalAccountId) {
    }
}
