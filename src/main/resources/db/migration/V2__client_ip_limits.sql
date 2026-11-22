ALTER TABLE vless_clients
    ADD COLUMN ip_limit          INTEGER,
    ADD COLUMN allowed_ip_count  INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN blocked_ip_count  INTEGER NOT NULL DEFAULT 0;

CREATE TABLE vless_client_ips (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id     UUID        NOT NULL REFERENCES vless_clients (id) ON DELETE CASCADE,
    ip            VARCHAR(64) NOT NULL,
    first_seen    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen     TIMESTAMPTZ NOT NULL DEFAULT now(),
    over_since    TIMESTAMPTZ,
    blocked_until TIMESTAMPTZ,
    UNIQUE (client_id, ip)
);
CREATE INDEX idx_vless_client_ips_client ON vless_client_ips (client_id);

ALTER TABLE vless_inbounds
    ADD COLUMN routing_ready BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN rules_applied BOOLEAN NOT NULL DEFAULT FALSE;
