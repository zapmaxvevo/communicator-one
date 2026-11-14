CREATE TABLE operators (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations (id),
    email TEXT NOT NULL,
    name TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    role TEXT NOT NULL,
    availability TEXT NOT NULL,
    CONSTRAINT operators_email_key UNIQUE (email)
);

CREATE INDEX operators_organization_id_idx ON operators (organization_id);
