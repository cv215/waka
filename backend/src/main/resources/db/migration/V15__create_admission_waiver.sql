-- Catalogue v2 (founder decision of 29 October 2026): an ADMISSION agent may approve a file with a
-- missing or unaccepted VEHICLE document, with a traced reason per document. Identity and driving
-- licence are never waived (enforced by the service). The waived document stays "to provide".
-- No row is inserted by this migration.
CREATE TABLE admission_waiver (
    gate_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    decision_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    requirement_code VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_code VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (gate_hash, decision_id, requirement_code),
    CONSTRAINT fk_admission_waiver_decision FOREIGN KEY (gate_hash, decision_id)
        REFERENCES admission_decision(gate_hash, id),
    CONSTRAINT ck_admission_waiver_not_person CHECK (requirement_code NOT IN ('IDENTITY', 'DRIVING_LICENCE'))
) ENGINE=InnoDB;
