-- Reusable driver documents (catalogue v1) and their examination. Encrypted content lives in the
-- private directory; nothing is inserted here. PERSON documents are reused across files; VEHICLE
-- documents only for the same registration, through an opaque vehicle key (HMAC of the plate).
CREATE TABLE driver_document (
    gate_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    document_type VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    vehicle_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    current_marker TINYINT NULL,
    review_state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    valid_until DATE NULL,
    byte_size INT NOT NULL,
    key_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_mac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_mac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    reviewed_at DATETIME(6) NULL,
    superseded_at DATETIME(6) NULL,
    PRIMARY KEY (gate_hash, id),
    CONSTRAINT uq_driver_document_request UNIQUE (gate_hash, request_mac),
    CONSTRAINT uq_driver_document_current UNIQUE (gate_hash, account_id, document_type, vehicle_key, current_marker),
    CONSTRAINT fk_driver_document_account FOREIGN KEY (gate_hash, account_id) REFERENCES identity_account(gate_hash, id),
    CONSTRAINT ck_driver_document_review CHECK (review_state IN ('received','accepted','to_correct')),
    CONSTRAINT ck_driver_document_current CHECK ((current_marker = 1 AND superseded_at IS NULL)
        OR (current_marker IS NULL AND superseded_at IS NOT NULL)),
    CONSTRAINT ck_driver_document_reviewed CHECK ((review_state = 'received') = (reviewed_at IS NULL))
) ENGINE=InnoDB;

CREATE TABLE admission_document_review (
    gate_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    decision_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    document_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    document_type VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_code VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (gate_hash, decision_id, document_id),
    CONSTRAINT fk_admission_document_review_decision FOREIGN KEY (gate_hash, decision_id) REFERENCES admission_decision(gate_hash, id),
    CONSTRAINT fk_admission_document_review_document FOREIGN KEY (gate_hash, document_id) REFERENCES driver_document(gate_hash, id),
    CONSTRAINT ck_admission_document_review_result CHECK (result IN ('acceptable','to_correct')),
    CONSTRAINT ck_admission_document_review_reason CHECK (result = 'acceptable' OR reason_code IS NOT NULL)
) ENGINE=InnoDB;

-- Approval becomes a recordable decision; the service still refuses it while any applicable
-- requirement is to be confirmed or not accepted.
ALTER TABLE admission_decision
    DROP CHECK ck_admission_decision_action,
    ADD CONSTRAINT ck_admission_decision_action CHECK (action IN ('request_correction','refuse','approve')),
    ADD COLUMN catalogue_version VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NULL;
