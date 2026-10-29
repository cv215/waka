-- R12: at most five unrecognized referral codes per account over a sliding 24 hours. Only failures are
-- kept, with no code: the table can tell how many, never which codes were tried. No row is inserted here.
CREATE TABLE referral_attempt (
    gate_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempted_at DATETIME(6) NOT NULL,
    PRIMARY KEY (gate_hash, id),
    CONSTRAINT fk_referral_attempt_account FOREIGN KEY (gate_hash, account_id) REFERENCES identity_account(gate_hash, id),
    INDEX ix_referral_attempt_account (gate_hash, account_id, attempted_at)
) ENGINE=InnoDB;
