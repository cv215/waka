-- R14 money: Mobile Money payments through the provider, the WAKA credit of each account, withdrawals,
-- and a ledger in which every movement is one debit and one credit of the same amount. The ledger is
-- append only. No row is inserted by this migration.
CREATE TABLE payment (
    gate_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    purpose VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    method VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    charged_xaf INT NOT NULL,
    credited_xaf INT NOT NULL,
    fee_xaf INT NOT NULL,
    expected_provider_cost_xaf INT NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    applied TINYINT NOT NULL DEFAULT 0,
    provider VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    provider_reference VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL,
    phone_cipher VARBINARY(256) NULL,
    key_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    request_mac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    settled_at DATETIME(6) NULL,
    PRIMARY KEY (gate_hash, id),
    CONSTRAINT uq_payment_request UNIQUE (gate_hash, request_mac),
    CONSTRAINT uq_payment_provider_reference UNIQUE (gate_hash, provider_reference),
    CONSTRAINT fk_payment_account FOREIGN KEY (gate_hash, account_id) REFERENCES identity_account(gate_hash, id),
    CONSTRAINT ck_payment_purpose CHECK (purpose IN ('ride', 'voyage', 'subscription', 'topup')),
    CONSTRAINT ck_payment_method CHECK (method IN ('cm.mtn', 'cm.orange', 'credit')),
    CONSTRAINT ck_payment_state CHECK (state IN ('pending', 'succeeded', 'failed', 'review')),
    CONSTRAINT ck_payment_amounts CHECK (charged_xaf > 0 AND credited_xaf > 0 AND fee_xaf >= 0 AND charged_xaf = credited_xaf + fee_xaf
        AND expected_provider_cost_xaf >= 0),
    INDEX ix_payment_account (gate_hash, account_id, created_at),
    INDEX ix_payment_target (gate_hash, target_id)
) ENGINE=InnoDB;

CREATE TABLE withdrawal (
    gate_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    method VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount_xaf INT NOT NULL,
    fee_xaf INT NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_reference VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL,
    phone_cipher VARBINARY(256) NOT NULL,
    key_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_mac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    settled_at DATETIME(6) NULL,
    PRIMARY KEY (gate_hash, id),
    CONSTRAINT uq_withdrawal_request UNIQUE (gate_hash, request_mac),
    CONSTRAINT fk_withdrawal_account FOREIGN KEY (gate_hash, account_id) REFERENCES identity_account(gate_hash, id),
    CONSTRAINT ck_withdrawal_method CHECK (method IN ('cm.mtn', 'cm.orange')),
    -- sent: the provider call did not answer clearly; never reversed without the provider's final status.
    CONSTRAINT ck_withdrawal_state CHECK (state IN ('pending', 'sent', 'succeeded', 'failed')),
    CONSTRAINT ck_withdrawal_amounts CHECK (amount_xaf > 0 AND fee_xaf >= 0),
    INDEX ix_withdrawal_account (gate_hash, account_id, created_at)
) ENGINE=InnoDB;

CREATE TABLE ledger_entry (
    gate_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    id BIGINT NOT NULL AUTO_INCREMENT,
    journal VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    line_no TINYINT NOT NULL,
    debit_account VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    credit_account VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount_xaf BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_ledger_line UNIQUE (gate_hash, journal, line_no),
    CONSTRAINT ck_ledger_amount CHECK (amount_xaf > 0 AND debit_account <> credit_account),
    INDEX ix_ledger_debit (gate_hash, debit_account, id),
    INDEX ix_ledger_credit (gate_hash, credit_account, id)
) ENGINE=InnoDB;
