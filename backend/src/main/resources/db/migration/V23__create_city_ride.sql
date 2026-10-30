-- R13 city rides: driver presence (latest position only, encrypted), searches on a quote, one-by-one
-- proposals, and one active reservation per car. No payment here: a reservation waits for it.
-- No row is inserted by this migration.
CREATE TABLE driver_presence (
    gate_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    online TINYINT NOT NULL,
    key_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    position_cipher VARBINARY(256) NULL,
    position_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (gate_hash, account_id),
    CONSTRAINT fk_driver_presence_account FOREIGN KEY (gate_hash, account_id) REFERENCES identity_account(gate_hash, id),
    CONSTRAINT ck_driver_presence_online CHECK (online IN (0, 1)),
    INDEX ix_driver_presence_online (gate_hash, online, position_at)
) ENGINE=InnoDB;

CREATE TABLE ride_search (
    gate_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    quote_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    client_account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reservation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    request_mac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (gate_hash, id),
    CONSTRAINT uq_ride_search_request UNIQUE (gate_hash, request_mac),
    CONSTRAINT uq_ride_search_quote UNIQUE (gate_hash, quote_id),
    CONSTRAINT fk_ride_search_quote FOREIGN KEY (gate_hash, quote_id) REFERENCES quote(gate_hash, id),
    CONSTRAINT ck_ride_search_state CHECK (state IN ('searching', 'matched', 'cancelled')),
    INDEX ix_ride_search_client (gate_hash, client_account_id, created_at)
) ENGINE=InnoDB;

CREATE TABLE ride_proposal (
    gate_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    search_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    driver_account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    pickup_seconds INT NOT NULL,
    state VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sent_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    responded_at DATETIME(6) NULL,
    PRIMARY KEY (gate_hash, id),
    CONSTRAINT uq_ride_proposal_driver UNIQUE (gate_hash, search_id, driver_account_id),
    CONSTRAINT fk_ride_proposal_search FOREIGN KEY (gate_hash, search_id) REFERENCES ride_search(gate_hash, id),
    CONSTRAINT ck_ride_proposal_state CHECK (state IN ('sent', 'accepted', 'refused')),
    CONSTRAINT ck_ride_proposal_pickup CHECK (pickup_seconds BETWEEN 0 AND 600),
    INDEX ix_ride_proposal_driver (gate_hash, driver_account_id, state, expires_at)
) ENGINE=InnoDB;

CREATE TABLE ride_reservation (
    gate_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    search_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    client_account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    driver_account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    mode VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    seats TINYINT NOT NULL,
    price_xaf INT NOT NULL,
    commission_xaf INT NOT NULL,
    driver_xaf INT NOT NULL,
    fare_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    active_marker TINYINT NULL,
    hold_expires_at DATETIME(6) NOT NULL,
    arrived_at DATETIME(6) NULL,
    boarded_at DATETIME(6) NULL,
    ended_at DATETIME(6) NULL,
    refund_xaf INT NULL,
    key_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    boarding_code_mac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    boarding_code_cipher VARBINARY(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (gate_hash, id),
    -- R13: one active city reservation per car (no pooling at launch).
    CONSTRAINT uq_ride_reservation_active UNIQUE (gate_hash, driver_account_id, active_marker),
    CONSTRAINT uq_ride_reservation_search UNIQUE (gate_hash, search_id),
    CONSTRAINT fk_ride_reservation_search FOREIGN KEY (gate_hash, search_id) REFERENCES ride_search(gate_hash, id),
    CONSTRAINT ck_ride_reservation_mode CHECK (mode IN ('shared', 'vip')),
    CONSTRAINT ck_ride_reservation_seats CHECK (seats BETWEEN 1 AND 5),
    CONSTRAINT ck_ride_reservation_amounts CHECK (price_xaf > 0 AND commission_xaf >= 0 AND driver_xaf = price_xaf - commission_xaf),
    CONSTRAINT ck_ride_reservation_state CHECK (state IN ('awaiting_payment', 'confirmed', 'driver_arrived', 'boarded', 'finished',
        'cancelled_by_client', 'cancelled_by_driver', 'client_absent', 'expired')),
    CONSTRAINT ck_ride_reservation_active CHECK (active_marker IS NULL OR active_marker = 1),
    INDEX ix_ride_reservation_client (gate_hash, client_account_id, created_at)
) ENGINE=InnoDB;
