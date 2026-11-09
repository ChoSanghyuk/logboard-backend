-- SSH host-key trust on first use: SHA256 fingerprint of the key the server presented on the first successful login.
ALTER TABLE servers ADD COLUMN host_key_fingerprint VARCHAR(128);
