-- Servers referenced by error logs are soft-deleted (design §5, api-contract DELETE /servers/{id}).
ALTER TABLE servers ADD COLUMN deleted_at TIMESTAMPTZ;

-- A soft-deleted server's name can be reused: uniqueness applies to active servers only.
ALTER TABLE servers DROP CONSTRAINT servers_name_key;
CREATE UNIQUE INDEX servers_name_active ON servers (name) WHERE deleted_at IS NULL;
