CREATE INDEX opportunities_organization_id_last_activity_at_open_idx ON opportunities (organization_id, last_activity_at) WHERE status = 'OPEN';
