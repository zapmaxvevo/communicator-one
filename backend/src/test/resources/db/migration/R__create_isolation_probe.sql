CREATE TABLE IF NOT EXISTS isolation_probes (
    id UUID PRIMARY KEY,
    label TEXT NOT NULL,
    organization_id UUID NOT NULL REFERENCES organizations (id)
);

CREATE INDEX IF NOT EXISTS isolation_probes_organization_id_idx ON isolation_probes (organization_id);
