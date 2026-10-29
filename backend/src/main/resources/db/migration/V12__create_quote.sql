-- A08 quotes. A quote reserves no vehicle and accepts no client price: the distance comes from the
-- server's route provider and the price from an explicit fare version. Points are encrypted.
-- No personal value, coordinate or key is inserted by this migration.
CREATE TABLE quote (
    gate_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_mac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_mac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    mode VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    passenger_count TINYINT NOT NULL,
    distance_metres INT NOT NULL,
    fare_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    band_code CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    place_price_xaf INT NOT NULL,
    total_xaf INT NOT NULL,
    key_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    points_cipher VARBINARY(512) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (gate_hash, id),
    CONSTRAINT uq_quote_request UNIQUE (gate_hash, request_mac),
    CONSTRAINT fk_quote_account FOREIGN KEY (gate_hash, account_id)
        REFERENCES identity_account(gate_hash, id),
    CONSTRAINT fk_quote_fare FOREIGN KEY (fare_version) REFERENCES fare_schedule(version),
    CONSTRAINT ck_quote_mode CHECK (mode IN ('shared', 'vip')),
    CONSTRAINT ck_quote_passengers CHECK (passenger_count BETWEEN 1 AND 5),
    CONSTRAINT ck_quote_distance CHECK (distance_metres BETWEEN 0 AND 17900),
    CONSTRAINT ck_quote_amounts CHECK (place_price_xaf > 0 AND total_xaf >= place_price_xaf),
    CONSTRAINT ck_quote_deadline CHECK (expires_at > created_at),
    INDEX ix_quote_account (gate_hash, account_id, created_at)
) ENGINE=InnoDB;
