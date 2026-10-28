-- Empty profile storage. No personal value or key is inserted by this migration.
CREATE TABLE identity_profile (
    gate_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version BIGINT NOT NULL,
    key_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    display_cipher VARBINARY(384) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (gate_hash, account_id),
    CONSTRAINT fk_profile_account FOREIGN KEY (gate_hash, account_id)
        REFERENCES identity_account(gate_hash, id),
    CONSTRAINT ck_profile_version CHECK (version BETWEEN 2 AND 9007199254740991)
) ENGINE=InnoDB;

CREATE TABLE identity_profile_command (
    request_mac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    gate_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_mac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    key_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    response_cipher VARBINARY(1024) NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_profile_command_account FOREIGN KEY (gate_hash, account_id)
        REFERENCES identity_account(gate_hash, id),
    CONSTRAINT ck_profile_command_deadline CHECK (expires_at > created_at),
    INDEX ix_profile_command_expiry (gate_hash, expires_at)
) ENGINE=InnoDB;
