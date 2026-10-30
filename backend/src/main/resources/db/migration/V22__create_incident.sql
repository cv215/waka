-- R12 incidents (A23-A25, A39) on a voyage reservation: declared by one of its two parties, discussed by
-- encrypted messages, decided with a reason by ASSISTANCE. A decision moves no money. No row is inserted here.
CREATE TABLE incident (
    gate_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reservation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reporter_account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reporter_role VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    client_account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    driver_account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    category VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    open_marker TINYINT NOT NULL,
    version BIGINT NOT NULL,
    request_mac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_mac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (gate_hash, id),
    CONSTRAINT uq_incident_request UNIQUE (gate_hash, request_mac),
    CONSTRAINT uq_incident_open UNIQUE (gate_hash, reservation_id, reporter_account_id, open_marker),
    CONSTRAINT fk_incident_reservation FOREIGN KEY (gate_hash, reservation_id) REFERENCES voyage_reservation(gate_hash, id),
    CONSTRAINT ck_incident_role CHECK (reporter_role IN ('client', 'driver')),
    CONSTRAINT ck_incident_category CHECK (category IN ('no_show_dispute', 'safety', 'behaviour', 'payment', 'vehicle', 'other')),
    CONSTRAINT ck_incident_state CHECK (state IN ('open', 'decided', 'contested')),
    -- One incident per reservation and reporter: a second report finds the first; a contest reopens it.
    CONSTRAINT ck_incident_open CHECK (open_marker = 1),
    INDEX ix_incident_state (gate_hash, state, updated_at)
) ENGINE=InnoDB;

CREATE TABLE incident_message (
    gate_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    incident_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    author_account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    author_role VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    kind VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    decision VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL,
    reason_code VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NULL,
    key_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    text_cipher VARBINARY(4096) NULL,
    request_mac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_mac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (gate_hash, id),
    CONSTRAINT uq_incident_message_request UNIQUE (gate_hash, request_mac),
    CONSTRAINT fk_incident_message_incident FOREIGN KEY (gate_hash, incident_id) REFERENCES incident(gate_hash, id),
    CONSTRAINT ck_incident_message_role CHECK (author_role IN ('client', 'driver', 'assistance')),
    CONSTRAINT ck_incident_message_kind CHECK (kind IN ('report', 'reply', 'contest', 'decision')),
    CONSTRAINT ck_incident_message_decision CHECK ((kind = 'decision') = (decision IS NOT NULL AND reason_code IS NOT NULL)),
    CONSTRAINT ck_incident_message_decision_kind CHECK (decision IS NULL
        OR decision IN ('no_action', 'refund_review', 'driver_warning', 'account_review')),
    INDEX ix_incident_message_incident (gate_hash, incident_id, created_at)
) ENGINE=InnoDB;
