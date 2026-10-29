-- R11 boarding (K27): each reservation has a 6-digit code shown only to its client. The driver enters it
-- at boarding; the code is kept as a keyed imprint (lookup) and encrypted (display to the client only).
-- After ten wrong codes the voyage refuses further attempts. No row is inserted by this migration.
ALTER TABLE voyage_reservation
    ADD COLUMN boarding_code_mac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN boarding_code_cipher VARBINARY(64) NULL,
    ADD COLUMN key_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN boarded_at DATETIME(6) NULL,
    ADD CONSTRAINT ck_voyage_reservation_boarded CHECK ((state = 'boarded') = (boarded_at IS NOT NULL));

ALTER TABLE voyage
    ADD COLUMN boarding_failures INT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_voyage_boarding_failures CHECK (boarding_failures BETWEEN 0 AND 1000);
