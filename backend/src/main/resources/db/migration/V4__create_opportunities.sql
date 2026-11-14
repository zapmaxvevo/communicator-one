ALTER TABLE operators ADD CONSTRAINT operators_id_organization_id_key UNIQUE (id, organization_id);

CREATE TABLE opportunities (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations (id),
    person_id UUID NOT NULL,
    owner_id UUID,
    source TEXT NOT NULL,
    status TEXT NOT NULL,
    result TEXT,
    opened_at TIMESTAMPTZ NOT NULL,
    last_activity_at TIMESTAMPTZ NOT NULL,
    closed_at TIMESTAMPTZ,
    CONSTRAINT opportunities_person_id_organization_id_fkey
        FOREIGN KEY (person_id, organization_id) REFERENCES people (id, organization_id),
    CONSTRAINT opportunities_owner_id_organization_id_fkey
        FOREIGN KEY (owner_id, organization_id) REFERENCES operators (id, organization_id),
    CONSTRAINT opportunities_closure_check CHECK (
        (status = 'OPEN' AND result IS NULL AND closed_at IS NULL)
        OR (status = 'CLOSED' AND result IS NOT NULL AND closed_at IS NOT NULL))
);

CREATE INDEX opportunities_organization_id_idx ON opportunities (organization_id);
CREATE INDEX opportunities_person_id_opened_at_idx ON opportunities (person_id, opened_at DESC);
CREATE INDEX opportunities_owner_id_organization_id_idx ON opportunities (owner_id, organization_id);
CREATE UNIQUE INDEX opportunities_person_id_open_key ON opportunities (person_id) WHERE status = 'OPEN';

CREATE TABLE opportunity_settings (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations (id),
    inactivity_days_to_close INTEGER NOT NULL,
    reentry_window_days INTEGER NOT NULL,
    CONSTRAINT opportunity_settings_organization_id_key UNIQUE (organization_id),
    CONSTRAINT opportunity_settings_inactivity_days_to_close_check CHECK (inactivity_days_to_close > 0),
    CONSTRAINT opportunity_settings_reentry_window_days_check CHECK (reentry_window_days >= 0)
);

CREATE INDEX opportunity_settings_organization_id_idx ON opportunity_settings (organization_id);
