-- R05 referral linkage (A34): at most one optional referrer per account, one level, never oneself.
-- A linkage creates no bonus: bonuses come only from paid and completed reservations (later points).
-- No personal value, code or key is inserted by this migration.
CREATE TABLE referral_code (
    gate_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    code CHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (gate_hash, account_id),
    CONSTRAINT uq_referral_code UNIQUE (gate_hash, code),
    CONSTRAINT fk_referral_code_account FOREIGN KEY (gate_hash, account_id)
        REFERENCES identity_account(gate_hash, id),
    CONSTRAINT ck_referral_code_format CHECK (code REGEXP '^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}$')
) ENGINE=InnoDB;

CREATE TABLE referral_link (
    gate_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    referrer_account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    linked_at DATETIME(6) NOT NULL,
    source VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (gate_hash, account_id),
    CONSTRAINT fk_referral_link_account FOREIGN KEY (gate_hash, account_id)
        REFERENCES identity_account(gate_hash, id),
    CONSTRAINT fk_referral_link_referrer FOREIGN KEY (gate_hash, referrer_account_id)
        REFERENCES identity_account(gate_hash, id),
    CONSTRAINT ck_referral_link_not_self CHECK (account_id <> referrer_account_id),
    CONSTRAINT ck_referral_link_source CHECK (source IN ('self_service')),
    INDEX ix_referral_link_referrer (gate_hash, referrer_account_id)
) ENGINE=InnoDB;
