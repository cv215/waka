-- R15 revised by the founder (31 October 2026): a shared client is proposed when the driver is within 10 minutes of
-- the pickup and the client's trip leads towards the driver's direction; the detour is shown, no longer capped at
-- five minutes. The cumulative counter is no longer used (left at its value). No row is inserted or changed.
ALTER TABLE ride_proposal
    DROP CHECK ck_ride_proposal_detour,
    ADD CONSTRAINT ck_ride_proposal_detour CHECK (detour_seconds BETWEEN 0 AND 7200);
