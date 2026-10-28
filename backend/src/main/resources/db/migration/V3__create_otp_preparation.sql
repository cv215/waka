-- Empty preparation only: no provider, contact, code, secret or budget activation.
CREATE TABLE otp_gate (
    gate_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    created_at DATETIME(6) NOT NULL
) ENGINE=InnoDB;

CREATE TABLE otp_challenge (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    global_gate CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    context_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    destination_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    destination_cipher VARBINARY(128) NULL,
    code_mac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    submission VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    lifecycle VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    wrong_attempts INT NOT NULL DEFAULT 0,
    verify_key_mac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    verify_code_mac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    proof_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    CONSTRAINT uq_otp_request UNIQUE (request_hash),
    CONSTRAINT ck_otp_submission CHECK (submission IN ('SENDING','ACCEPTED','REJECTED','UNKNOWN')),
    CONSTRAINT ck_otp_lifecycle CHECK (lifecycle IN ('ACTIVE','CONSUMED','EXPIRED','LOCKED','SUPERSEDED')),
    CONSTRAINT ck_otp_attempts CHECK (wrong_attempts BETWEEN 0 AND 5),
    INDEX ix_otp_global_time (global_gate, created_at),
    INDEX ix_otp_destination_time (destination_hash, created_at),
    INDEX ix_otp_context_time (context_hash, created_at)
) ENGINE=InnoDB;

CREATE TABLE otp_rate_event (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    global_gate CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    destination_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    INDEX ix_otp_failure_window (destination_hash, created_at),
    INDEX ix_otp_failure_gate (global_gate)
) ENGINE=InnoDB;

CREATE TABLE otp_verification (
    key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    global_gate CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_mac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    proof_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    expires_at DATETIME(6) NULL,
    INDEX ix_otp_verification_gate (global_gate)
) ENGINE=InnoDB;
