-- Infrastructure uniquement : aucun compte, evenement ou jeu de donnees insere.
CREATE TABLE audit_event (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    occurred_at DATETIME(6) NOT NULL COMMENT 'Instant UTC fourni par le serveur applicatif',
    event_type VARCHAR(64) NOT NULL,
    actor_ref VARCHAR(128) NULL COMMENT 'Reference opaque ; jamais nom, telephone ou secret',
    actor_scope VARCHAR(64) NOT NULL,
    object_type VARCHAR(64) NOT NULL,
    object_ref VARCHAR(128) NULL COMMENT 'Reference opaque de l objet concerne',
    reason_code VARCHAR(64) NOT NULL COMMENT 'Code structure ; aucun message libre ou contenu de piece',
    outcome VARCHAR(32) NOT NULL,
    correlation_ref VARCHAR(128) NULL,
    PRIMARY KEY (id),
    INDEX idx_audit_event_occurred_at (occurred_at, id),
    INDEX idx_audit_event_object (object_type, object_ref, occurred_at)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
