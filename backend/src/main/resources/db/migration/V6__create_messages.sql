ALTER TABLE opportunities
    ADD CONSTRAINT opportunities_id_person_id_organization_id_key UNIQUE (id, person_id, organization_id);

CREATE TABLE messages (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations (id),
    person_id UUID NOT NULL,
    opportunity_id UUID NOT NULL,
    channel_account_id UUID NOT NULL,
    channel TEXT NOT NULL,
    sender_type TEXT NOT NULL,
    external_id TEXT NOT NULL,
    text TEXT NOT NULL,
    status TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT messages_person_id_organization_id_fkey
        FOREIGN KEY (person_id, organization_id) REFERENCES people (id, organization_id),
    CONSTRAINT messages_opportunity_id_person_id_organization_id_fkey
        FOREIGN KEY (opportunity_id, person_id, organization_id)
        REFERENCES opportunities (id, person_id, organization_id),
    CONSTRAINT messages_organization_id_channel_account_id_external_id_key
        UNIQUE (organization_id, channel_account_id, external_id)
);

CREATE INDEX messages_person_id_occurred_at_recorded_at_id_idx
    ON messages (person_id, occurred_at DESC, recorded_at DESC, id DESC);
CREATE INDEX messages_person_id_channel_account_id_occurred_at_customer_idx
    ON messages (person_id, channel_account_id, occurred_at DESC) WHERE sender_type = 'CUSTOMER';
CREATE INDEX messages_opportunity_id_idx ON messages (opportunity_id);
