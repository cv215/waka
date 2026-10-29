-- Submission and append-only examination of a candidature. No row is inserted here.
-- 'approved' exists in the schema only: no route reaches it until document requirements are qualified.
ALTER TABLE driver_application
    DROP CHECK ck_driver_application_state,
    ADD COLUMN submitted_at DATETIME(6) NULL,
    ADD CONSTRAINT ck_driver_application_state
        CHECK (state IN ('draft','submitted','correction_requested','refused','approved')),
    ADD CONSTRAINT ck_driver_application_open_state
        CHECK ((state = 'refused') = (open_marker IS NULL)),
    ADD CONSTRAINT ck_driver_application_submitted
        CHECK (state = 'draft' OR submitted_at IS NOT NULL);

CREATE TABLE admission_decision (
    gate_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    application_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    examined_version BIGINT NOT NULL,
    agent_account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    admin_function VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    action VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_code VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    key_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    details_cipher VARBINARY(2304) NULL,
    request_mac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_mac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    decided_at DATETIME(6) NOT NULL,
    PRIMARY KEY (gate_hash, id),
    CONSTRAINT uq_admission_decision_request UNIQUE (gate_hash, request_mac),
    CONSTRAINT uq_admission_decision_version UNIQUE (gate_hash, application_id, examined_version),
    CONSTRAINT fk_admission_decision_application FOREIGN KEY (gate_hash, application_id)
        REFERENCES driver_application(gate_hash, id),
    CONSTRAINT fk_admission_decision_agent FOREIGN KEY (gate_hash, agent_account_id)
        REFERENCES identity_account(gate_hash, id),
    CONSTRAINT ck_admission_decision_action CHECK (action IN ('request_correction','refuse')),
    CONSTRAINT ck_admission_decision_function CHECK (admin_function = 'ADMISSION'),
    INDEX ix_admission_decision_application (gate_hash, application_id, decided_at)
) ENGINE=InnoDB;

CREATE TABLE admission_photo_review (
    gate_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    decision_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    photo_view CHAR(4) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    file_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_code VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (gate_hash, decision_id, photo_view),
    CONSTRAINT fk_admission_photo_review_decision FOREIGN KEY (gate_hash, decision_id)
        REFERENCES admission_decision(gate_hash, id),
    CONSTRAINT fk_admission_photo_review_file FOREIGN KEY (gate_hash, file_id)
        REFERENCES vehicle_photo(gate_hash, id),
    CONSTRAINT ck_admission_photo_review_result CHECK (result IN ('acceptable','to_correct')),
    CONSTRAINT ck_admission_photo_review_reason CHECK (result = 'acceptable' OR reason_code IS NOT NULL)
) ENGINE=InnoDB;
