CREATE TABLE channel_accounts (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations (id),
    channel_type TEXT NOT NULL,
    external_account_id TEXT NOT NULL,
    display_name TEXT NOT NULL,
    allows_outbound BOOLEAN NOT NULL,
    CONSTRAINT channel_accounts_channel_type_external_account_id_key UNIQUE (channel_type, external_account_id),
    CONSTRAINT channel_accounts_id_organization_id_channel_type_key UNIQUE (id, organization_id, channel_type)
);

CREATE INDEX channel_accounts_organization_id_idx ON channel_accounts (organization_id);

ALTER TABLE messages
    ADD CONSTRAINT messages_channel_account_id_organization_id_channel_fkey
        FOREIGN KEY (channel_account_id, organization_id, channel)
        REFERENCES channel_accounts (id, organization_id, channel_type);
