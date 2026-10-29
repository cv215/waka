-- A06 privacy requests. Receiving a request deletes nothing and promises no total deletion:
-- the handling procedure and retention periods are still to be qualified. Details are encrypted.
-- No personal value or key is inserted by this migration.
CREATE TABLE privacy_request (
    gate_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    open_marker TINYINT NULL,
    key_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    details_cipher VARBINARY(4096) NULL,
    received_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (gate_hash, id),
    -- At most one open request per kind: a repeated request finds the open one.
    CONSTRAINT uq_privacy_request_open UNIQUE (gate_hash, account_id, kind, open_marker),
    CONSTRAINT fk_privacy_request_account FOREIGN KEY (gate_hash, account_id)
        REFERENCES identity_account(gate_hash, id),
    CONSTRAINT ck_privacy_request_kind CHECK (kind IN ('access', 'deletion')),
    CONSTRAINT ck_privacy_request_state CHECK (state IN ('received')),
    CONSTRAINT ck_privacy_request_open CHECK (open_marker IS NULL OR open_marker = 1),
    CONSTRAINT ck_privacy_request_dates CHECK (updated_at >= received_at),
    INDEX ix_privacy_request_account (gate_hash, account_id, received_at)
) ENGINE=InnoDB;

CREATE TABLE privacy_request_command (
    gate_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_mac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_mac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (gate_hash, request_mac),
    CONSTRAINT fk_privacy_request_command_request FOREIGN KEY (gate_hash, request_id)
        REFERENCES privacy_request(gate_hash, id)
) ENGINE=InnoDB;
