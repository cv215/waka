-- R15 shared rides by direction: the driver's direction (encrypted, like his position), the detour already
-- accepted since the car took its first client, the detour of each proposal, and several shared reservations
-- per car (the single-reservation rule now holds only for VIP and for drivers without a direction, in code).
-- No row is inserted or changed by this migration.
ALTER TABLE driver_presence
    ADD COLUMN destination_cipher VARBINARY(256) NULL,
    ADD COLUMN detour_used_seconds INT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_driver_presence_detour CHECK (detour_used_seconds BETWEEN 0 AND 300);

ALTER TABLE ride_proposal
    ADD COLUMN detour_seconds INT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_ride_proposal_detour CHECK (detour_seconds BETWEEN 0 AND 300);

ALTER TABLE ride_reservation
    ADD INDEX ix_ride_reservation_driver_active (gate_hash, driver_account_id, active_marker),
    DROP INDEX uq_ride_reservation_active;
