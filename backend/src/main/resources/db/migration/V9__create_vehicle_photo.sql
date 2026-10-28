-- Private file records (D08/D09) for the six vehicle views. The encrypted content lives in the
-- injected private directory, never in this table, Git or a public root. Nothing is inserted here.
CREATE TABLE vehicle_photo (
    gate_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    application_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    photo_view CHAR(4) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    current_marker TINYINT NULL,
    content_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    byte_size INT NOT NULL,
    width INT NOT NULL,
    height INT NOT NULL,
    key_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_mac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_mac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    superseded_at DATETIME(6) NULL,
    PRIMARY KEY (gate_hash, id),
    CONSTRAINT uq_vehicle_photo_request UNIQUE (gate_hash, request_mac),
    CONSTRAINT uq_vehicle_photo_current UNIQUE (gate_hash, application_id, photo_view, current_marker),
    CONSTRAINT fk_vehicle_photo_application FOREIGN KEY (gate_hash, application_id)
        REFERENCES driver_application(gate_hash, id),
    CONSTRAINT fk_vehicle_photo_account FOREIGN KEY (gate_hash, account_id)
        REFERENCES identity_account(gate_hash, id),
    CONSTRAINT ck_vehicle_photo_view CHECK (photo_view IN ('PH01','PH02','PH03','PH04','PH05','PH06')),
    CONSTRAINT ck_vehicle_photo_type CHECK (content_type = 'image/jpeg'),
    CONSTRAINT ck_vehicle_photo_current CHECK ((current_marker = 1 AND superseded_at IS NULL)
        OR (current_marker IS NULL AND superseded_at IS NOT NULL)),
    CONSTRAINT ck_vehicle_photo_size CHECK (byte_size > 0 AND width >= 320 AND height >= 240 AND width <= 8000 AND height <= 8000)
) ENGINE=InnoDB;
