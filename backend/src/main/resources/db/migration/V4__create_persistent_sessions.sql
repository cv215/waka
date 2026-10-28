-- Empty identity/session infrastructure. No role, profile, contact, token or key inserted.
CREATE TABLE identity_session_gate (
    gate_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    last_operation_at DATETIME(6) NOT NULL
) ENGINE=InnoDB;

CREATE TABLE identity_account (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    gate_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    phone_lookup_mac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    phone_verified_at DATETIME(6) NOT NULL,
    CONSTRAINT uq_identity_phone UNIQUE (phone_lookup_mac),
    CONSTRAINT uq_identity_account_gate UNIQUE (gate_hash, id),
    CONSTRAINT fk_account_session_gate FOREIGN KEY (gate_hash) REFERENCES identity_session_gate(gate_hash),
    CONSTRAINT ck_identity_account_status CHECK (status IN ('ACTIVE','DISABLED')),
    INDEX ix_account_gate (gate_hash)
) ENGINE=InnoDB;

CREATE TABLE identity_session (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    gate_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    context_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_challenge_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_proof_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    last_seen_at DATETIME(6) NOT NULL,
    absolute_expires_at DATETIME(6) NOT NULL,
    idle_expires_at DATETIME(6) NOT NULL,
    idle_timeout_micros BIGINT NOT NULL,
    ended_at DATETIME(6) NULL,
    end_reason VARCHAR(7) CHARACTER SET ascii COLLATE ascii_bin NULL,
    CONSTRAINT uq_session_token UNIQUE (token_hash),
    CONSTRAINT uq_session_source_challenge UNIQUE (source_challenge_id),
    CONSTRAINT uq_session_source_proof UNIQUE (source_proof_id),
    CONSTRAINT fk_session_gate FOREIGN KEY (gate_hash) REFERENCES identity_session_gate(gate_hash),
    CONSTRAINT fk_session_account FOREIGN KEY (gate_hash, account_id) REFERENCES identity_account(gate_hash, id),
    CONSTRAINT fk_session_otp_challenge FOREIGN KEY (source_challenge_id) REFERENCES otp_challenge(id),
    CONSTRAINT ck_session_deadlines CHECK (absolute_expires_at > created_at AND idle_expires_at > created_at
        AND idle_expires_at <= absolute_expires_at AND idle_timeout_micros > 0 AND last_seen_at >= created_at),
    CONSTRAINT ck_session_end CHECK ((ended_at IS NULL AND end_reason IS NULL)
        OR (ended_at IS NOT NULL AND end_reason IN ('REVOKED','EXPIRED'))),
    INDEX ix_session_gate (gate_hash),
    INDEX ix_session_account (account_id)
) ENGINE=InnoDB;

