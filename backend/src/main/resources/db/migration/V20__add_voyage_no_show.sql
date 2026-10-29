-- R12: a paid passenger absent at boarding is settled like a late cancellation (30 %), declared by the
-- driver after the planned time at the boarding stop. No row is inserted by this migration.
ALTER TABLE voyage_reservation
    DROP CHECK ck_voyage_reservation_state,
    ADD CONSTRAINT ck_voyage_reservation_state CHECK (state IN ('awaiting_payment', 'confirmed', 'boarded',
        'cancelled_by_client', 'cancelled_by_driver', 'no_show'));
