-- R06/R12: the private driver's single trial (seven 24-hour periods) starts with their first completed
-- paid service as a driver; paid periods (thirty 24-hour periods, 3 000 XAF) wait for payment.
-- No row is inserted by this migration.
CREATE TABLE driver_trial (
    gate_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    started_at DATETIME(6) NOT NULL,
    ends_at DATETIME(6) NOT NULL,
    source_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (gate_hash, account_id),
    CONSTRAINT fk_driver_trial_account FOREIGN KEY (gate_hash, account_id) REFERENCES identity_account(gate_hash, id),
    CONSTRAINT ck_driver_trial_length CHECK (ends_at > started_at),
    CONSTRAINT ck_driver_trial_source CHECK (source_type IN ('ride_reservation', 'voyage_reservation'))
) ENGINE=InnoDB;

CREATE TABLE subscription_period (
    gate_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    price_xaf INT NOT NULL,
    starts_at DATETIME(6) NULL,
    ends_at DATETIME(6) NULL,
    request_mac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (gate_hash, id),
    CONSTRAINT uq_subscription_period_request UNIQUE (gate_hash, request_mac),
    CONSTRAINT fk_subscription_period_account FOREIGN KEY (gate_hash, account_id) REFERENCES identity_account(gate_hash, id),
    CONSTRAINT ck_subscription_period_state CHECK (state IN ('awaiting_payment', 'paid', 'refunded')),
    CONSTRAINT ck_subscription_period_price CHECK (price_xaf = 3000),
    CONSTRAINT ck_subscription_period_dates CHECK ((state = 'paid') = (starts_at IS NOT NULL AND ends_at IS NOT NULL)),
    INDEX ix_subscription_period_account (gate_hash, account_id, state, ends_at)
) ENGINE=InnoDB;
