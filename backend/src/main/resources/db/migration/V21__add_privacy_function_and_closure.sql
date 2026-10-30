-- R12: a dedicated PRIVACY function handles requests about personal data; an agent closes a request with
-- an outcome and a reason code. Closing deletes nothing by itself (the deletion procedure is A05).
-- No row is inserted by this migration.
ALTER TABLE admin_grant
    DROP CHECK ck_admin_grant_function,
    ADD CONSTRAINT ck_admin_grant_function CHECK (admin_function IN ('ADMISSION','ASSISTANCE','FINANCE','AUDIT','HABILITATIONS','PRIVACY'));

ALTER TABLE privacy_request
    DROP CHECK ck_privacy_request_state,
    ADD COLUMN outcome VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN outcome_reason VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN closed_by CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD CONSTRAINT ck_privacy_request_state CHECK (state IN ('received', 'closed')),
    ADD CONSTRAINT ck_privacy_request_closure CHECK ((state = 'closed') = (outcome IS NOT NULL AND outcome_reason IS NOT NULL
        AND closed_by IS NOT NULL AND open_marker IS NULL)),
    ADD CONSTRAINT ck_privacy_request_outcome CHECK (outcome IS NULL OR outcome IN ('completed', 'refused'));
