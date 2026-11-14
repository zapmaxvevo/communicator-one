CREATE TABLE organizations (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL,
    CONSTRAINT organizations_id_not_sentinel_check CHECK (id <> '00000000-0000-0000-0000-000000000000')
);
