-- Messages of an incident are read in their order of arrival: a position numbered under the incident's
-- lock, since several messages can share the same instant. Existing rows keep position 0 (test data only).
ALTER TABLE incident_message
    ADD COLUMN position INT NOT NULL DEFAULT 0,
    ADD INDEX ix_incident_message_position (gate_hash, incident_id, position);