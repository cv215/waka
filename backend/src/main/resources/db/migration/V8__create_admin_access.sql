-- Empty administrative access storage. No grant, secret, token or person is inserted here:
-- the founder's first grant is a documented local maintenance operation, never an HTTP call.
CREATE TABLE admin_grant (
    gate_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    admin_function VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    granted_at DATETIME(6) NOT NULL,
    granted_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_code VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revoked_at DATETIME(6) NULL,
    revoked_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    revoke_reason VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (gate_hash, id),
    CONSTRAINT fk_admin_grant_account FOREIGN KEY (gate_hash, account_id)
        REFERENCES identity_account(gate_hash, id),
    CONSTRAINT ck_admin_grant_function CHECK (admin_function IN ('ADMISSION','ASSISTANCE','FINANCE','AUDIT','HABILITATIONS')),
    CONSTRAINT ck_admin_grant_revocation CHECK ((revoked_at IS NULL AND revoked_by IS NULL AND revoke_reason IS NULL)
        OR (revoked_at IS NOT NULL AND revoked_by IS NOT NULL AND revoke_reason IS NOT NULL AND revoked_at >= granted_at)),
    INDEX ix_admin_grant_account (gate_hash, account_id)
) ENGINE=InnoDB;

CREATE TABLE admin_totp (
    gate_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    key_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    secret_cipher VARBINARY(128) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    confirmed_at DATETIME(6) NULL,
    last_step BIGINT NOT NULL,
    failures INT NOT NULL,
    failure_window_start DATETIME(6) NULL,
    locked_until DATETIME(6) NULL,
    PRIMARY KEY (gate_hash, account_id),
    CONSTRAINT fk_admin_totp_account FOREIGN KEY (gate_hash, account_id)
        REFERENCES identity_account(gate_hash, id),
    CONSTRAINT ck_admin_totp_state CHECK (state IN ('pending','active')),
    CONSTRAINT ck_admin_totp_confirmed CHECK ((state = 'pending') = (confirmed_at IS NULL)),
    CONSTRAINT ck_admin_totp_failures CHECK (failures BETWEEN 0 AND 1000)
) ENGINE=InnoDB;

CREATE TABLE admin_session (
    gate_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    base_session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    last_seen_at DATETIME(6) NOT NULL,
    idle_expires_at DATETIME(6) NOT NULL,
    absolute_expires_at DATETIME(6) NOT NULL,
    ended_at DATETIME(6) NULL,
    end_reason VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (gate_hash, id),
    CONSTRAINT uq_admin_session_token UNIQUE (gate_hash, token_hash),
    CONSTRAINT fk_admin_session_account FOREIGN KEY (gate_hash, account_id)
        REFERENCES identity_account(gate_hash, id),
    CONSTRAINT fk_admin_session_base FOREIGN KEY (gate_hash, base_session_id)
        REFERENCES identity_session(gate_hash, id),
    CONSTRAINT ck_admin_session_deadlines CHECK (idle_expires_at <= absolute_expires_at AND absolute_expires_at > created_at),
    CONSTRAINT ck_admin_session_end CHECK ((ended_at IS NULL) = (end_reason IS NULL))
) ENGINE=InnoDB;
