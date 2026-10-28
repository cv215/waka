-- Empty candidature storage. A draft grants no admission, access or driver role.
-- No personal value, file or key is inserted by this migration.
CREATE TABLE driver_application (
    gate_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    requested_category VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    open_marker TINYINT NULL,
    version BIGINT NOT NULL,
    key_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    vehicle_cipher VARBINARY(1024) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (gate_hash, id),
    CONSTRAINT uq_driver_application_open UNIQUE (gate_hash, account_id, open_marker),
    CONSTRAINT fk_driver_application_account FOREIGN KEY (gate_hash, account_id)
        REFERENCES identity_account(gate_hash, id),
    CONSTRAINT ck_driver_application_category CHECK (requested_category IN ('taxi', 'private')),
    CONSTRAINT ck_driver_application_state CHECK (state IN ('draft')),
    CONSTRAINT ck_driver_application_open CHECK (open_marker IS NULL OR open_marker = 1),
    CONSTRAINT ck_driver_application_version CHECK (version BETWEEN 1 AND 9007199254740991),
    CONSTRAINT ck_driver_application_dates CHECK (updated_at >= created_at)
) ENGINE=InnoDB;

CREATE TABLE driver_application_command (
    request_mac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    gate_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_mac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    key_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    response_cipher VARBINARY(2048) NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_driver_application_command_account FOREIGN KEY (gate_hash, account_id)
        REFERENCES identity_account(gate_hash, id),
    CONSTRAINT ck_driver_application_command_deadline CHECK (expires_at > created_at),
    INDEX ix_driver_application_command_expiry (gate_hash, expires_at)
) ENGINE=InnoDB;
