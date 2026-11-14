CREATE TABLE people (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations (id),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX people_organization_id_idx ON people (organization_id);

CREATE TABLE person_identities (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations (id),
    person_id UUID NOT NULL REFERENCES people (id),
    kind TEXT NOT NULL,
    value TEXT NOT NULL,
    CONSTRAINT person_identities_organization_id_kind_value_key UNIQUE (organization_id, kind, value)
);

CREATE INDEX person_identities_organization_id_idx ON person_identities (organization_id);
CREATE INDEX person_identities_person_id_idx ON person_identities (person_id);
