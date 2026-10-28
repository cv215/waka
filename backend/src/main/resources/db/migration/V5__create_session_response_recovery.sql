-- Bounded encrypted response recovery; no row, token or configuration key inserted.
ALTER TABLE identity_session
    ADD COLUMN revocation_key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD CONSTRAINT uq_session_revocation_key UNIQUE (gate_hash, revocation_key_hash),
    ADD CONSTRAINT uq_session_gate_id UNIQUE (gate_hash, id);

CREATE TABLE identity_session_recovery (
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    gate_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_mac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    key_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    token_cipher VARBINARY(128) NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    CONSTRAINT uq_session_recovery_session UNIQUE (session_id),
    CONSTRAINT fk_recovery_session FOREIGN KEY (gate_hash, session_id)
        REFERENCES identity_session(gate_hash, id),
    CONSTRAINT ck_recovery_deadline CHECK (expires_at > created_at),
    INDEX ix_recovery_gate_expiry (gate_hash, expires_at)
) ENGINE=InnoDB;

