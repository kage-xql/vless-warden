CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE servers (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id               UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name                  VARCHAR(100) NOT NULL,
    host                  VARCHAR(255) NOT NULL,
    ssh_port              INT          NOT NULL DEFAULT 22,
    ssh_user              VARCHAR(64)  NOT NULL,
    encrypted_credentials BYTEA        NOT NULL,
    host_key_fingerprint  VARCHAR(255),
    status                VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    operating_system      VARCHAR(32),
    connection_error      VARCHAR(255),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_servers_user ON servers (user_id);

CREATE TABLE server_metrics (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    server_id UUID             NOT NULL REFERENCES servers (id) ON DELETE CASCADE,
    ts        TIMESTAMPTZ      NOT NULL DEFAULT now(),
    cpu       DOUBLE PRECISION NOT NULL,
    ram       DOUBLE PRECISION NOT NULL,
    disk      DOUBLE PRECISION NOT NULL,
    net_rx    BIGINT           NOT NULL,
    net_tx    BIGINT           NOT NULL
);
CREATE INDEX idx_server_metrics_server_ts ON server_metrics (server_id, ts DESC);

CREATE TABLE vless_inbounds (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    server_id              UUID         NOT NULL REFERENCES servers (id) ON DELETE CASCADE,
    tag                    VARCHAR(32)  NOT NULL,
    listen_port            INT          NOT NULL,
    dest                   VARCHAR(255) NOT NULL,
    server_names           VARCHAR(512) NOT NULL,
    short_ids              VARCHAR(255) NOT NULL,
    public_key             VARCHAR(128) NOT NULL,
    encrypted_private_key  BYTEA        NOT NULL,
    fingerprint            VARCHAR(32)  NOT NULL DEFAULT 'chrome',
    flow                   VARCHAR(32)  NOT NULL DEFAULT 'xtls-rprx-vision',
    enabled                BOOLEAN      NOT NULL DEFAULT TRUE,
    status                 VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    last_error             VARCHAR(255),
    xray_version           VARCHAR(64),
    firewall_port          INT,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (server_id, tag)
);
CREATE INDEX idx_vless_inbounds_server ON vless_inbounds (server_id);

CREATE TABLE vless_clients (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    inbound_id          UUID         NOT NULL REFERENCES vless_inbounds (id) ON DELETE CASCADE,
    name                VARCHAR(100) NOT NULL,
    email               VARCHAR(120) NOT NULL,
    encrypted_uuid      BYTEA        NOT NULL,
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    expires_at          TIMESTAMPTZ,
    traffic_limit_bytes BIGINT,
    traffic_used_bytes  BIGINT       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (inbound_id, email)
);
CREATE INDEX idx_vless_clients_inbound ON vless_clients (inbound_id);

CREATE TABLE vless_client_metrics (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id UUID        NOT NULL REFERENCES vless_clients (id) ON DELETE CASCADE,
    ts        TIMESTAMPTZ NOT NULL DEFAULT now(),
    rx_bytes  BIGINT      NOT NULL,
    tx_bytes  BIGINT      NOT NULL,
    online    BOOLEAN     NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_vless_client_metrics_client_ts ON vless_client_metrics (client_id, ts DESC);
