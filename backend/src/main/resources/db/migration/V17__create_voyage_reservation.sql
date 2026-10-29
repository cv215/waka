-- R11 seat reservations on a voyage section. Without a payment provider a reservation waits for payment
-- and holds its seats only during the payment window (R10, three minutes). Settlements are computed and
-- recorded on cancellation; no money moves here. No row is inserted by this migration.
CREATE TABLE voyage_reservation (
    gate_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    voyage_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    client_account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    from_index TINYINT NOT NULL,
    to_index TINYINT NOT NULL,
    seats TINYINT NOT NULL,
    seat_price_xaf INT NOT NULL,
    total_xaf INT NOT NULL,
    state VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_mac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_mac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    hold_expires_at DATETIME(6) NOT NULL,
    ended_at DATETIME(6) NULL,
    refund_xaf INT NULL,
    driver_xaf INT NULL,
    waka_xaf INT NULL,
    fee_percent TINYINT NULL,
    PRIMARY KEY (gate_hash, id),
    CONSTRAINT uq_voyage_reservation_request UNIQUE (gate_hash, request_mac),
    CONSTRAINT fk_voyage_reservation_voyage FOREIGN KEY (gate_hash, voyage_id) REFERENCES voyage(gate_hash, id),
    CONSTRAINT fk_voyage_reservation_client FOREIGN KEY (gate_hash, client_account_id) REFERENCES identity_account(gate_hash, id),
    CONSTRAINT ck_voyage_reservation_pair CHECK (from_index >= 0 AND to_index > from_index AND to_index <= 5),
    CONSTRAINT ck_voyage_reservation_seats CHECK (seats BETWEEN 1 AND 5),
    CONSTRAINT ck_voyage_reservation_amounts CHECK (seat_price_xaf BETWEEN 1 AND 10000 AND total_xaf = seat_price_xaf * seats),
    CONSTRAINT ck_voyage_reservation_state CHECK (state IN ('awaiting_payment', 'confirmed', 'boarded',
        'cancelled_by_client', 'cancelled_by_driver')),
    CONSTRAINT ck_voyage_reservation_settlement CHECK (refund_xaf IS NULL
        OR (driver_xaf IS NOT NULL AND waka_xaf IS NOT NULL AND fee_percent IS NOT NULL AND refund_xaf + driver_xaf + waka_xaf = total_xaf)),
    INDEX ix_voyage_reservation_voyage (gate_hash, voyage_id, state),
    INDEX ix_voyage_reservation_client (gate_hash, client_account_id, created_at)
) ENGINE=InnoDB;
