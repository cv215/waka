-- R11 intercity voyages: a driver's listing with its stops and a seat price per pair of stops.
-- Listing only: reservations and payments come with the payment provider. No row is inserted here.
CREATE TABLE voyage (
    gate_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    driver_account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    application_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    category VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    seats TINYINT NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    departure_at DATETIME(6) NOT NULL,
    request_mac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_mac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    cancel_reason VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NULL,
    cancelled_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (gate_hash, id),
    CONSTRAINT uq_voyage_request UNIQUE (gate_hash, request_mac),
    CONSTRAINT fk_voyage_driver FOREIGN KEY (gate_hash, driver_account_id) REFERENCES identity_account(gate_hash, id),
    CONSTRAINT fk_voyage_application FOREIGN KEY (gate_hash, application_id) REFERENCES driver_application(gate_hash, id),
    CONSTRAINT ck_voyage_category CHECK (category IN ('taxi', 'private')),
    CONSTRAINT ck_voyage_seats CHECK (seats BETWEEN 1 AND 5),
    CONSTRAINT ck_voyage_state CHECK (state IN ('published', 'cancelled')),
    CONSTRAINT ck_voyage_cancel CHECK ((state = 'cancelled') = (cancelled_at IS NOT NULL AND cancel_reason IS NOT NULL)),
    INDEX ix_voyage_listing (gate_hash, state, departure_at),
    INDEX ix_voyage_driver (gate_hash, driver_account_id, departure_at)
) ENGINE=InnoDB;

CREATE TABLE voyage_stop (
    gate_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    voyage_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    stop_index TINYINT NOT NULL,
    place VARCHAR(240) NOT NULL,
    planned_at DATETIME(6) NOT NULL,
    PRIMARY KEY (gate_hash, voyage_id, stop_index),
    CONSTRAINT fk_voyage_stop_voyage FOREIGN KEY (gate_hash, voyage_id) REFERENCES voyage(gate_hash, id),
    CONSTRAINT ck_voyage_stop_index CHECK (stop_index BETWEEN 0 AND 5)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE voyage_price (
    gate_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    voyage_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    from_index TINYINT NOT NULL,
    to_index TINYINT NOT NULL,
    seat_price_xaf INT NOT NULL,
    PRIMARY KEY (gate_hash, voyage_id, from_index, to_index),
    CONSTRAINT fk_voyage_price_voyage FOREIGN KEY (gate_hash, voyage_id) REFERENCES voyage(gate_hash, id),
    CONSTRAINT ck_voyage_price_pair CHECK (from_index >= 0 AND to_index > from_index AND to_index <= 5),
    CONSTRAINT ck_voyage_price_cap CHECK (seat_price_xaf BETWEEN 1 AND 10000)
) ENGINE=InnoDB;
