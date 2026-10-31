-- R16, step 4 (founder, 31 October 2026): one driver profile at a time, and the situation may change (taxi <-> private)
-- without a new account. While the new file is examined, the approved one keeps serving (serving_marker); on approval
-- it is closed as 'replaced', on refusal it becomes the open file again. No row is inserted or changed here.
ALTER TABLE driver_application
    ADD COLUMN serving_marker TINYINT NULL,
    ADD COLUMN replaces_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    DROP CHECK ck_driver_application_state,
    DROP CHECK ck_driver_application_open_state;

ALTER TABLE driver_application
    ADD CONSTRAINT ck_driver_application_state
        CHECK (state IN ('draft','submitted','correction_requested','refused','approved','replaced')),
    ADD CONSTRAINT ck_driver_application_open_state
        CHECK ((state IN ('refused','replaced')) = (open_marker IS NULL AND serving_marker IS NULL)),
    ADD CONSTRAINT ck_driver_application_serving
        CHECK (serving_marker IS NULL OR (serving_marker = 1 AND state = 'approved' AND open_marker IS NULL)),
    ADD CONSTRAINT uq_driver_application_serving UNIQUE (gate_hash, account_id, serving_marker);
