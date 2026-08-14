-- A servlet session is the logical identity of one user-initiated logout. The primary key makes
-- duplicate handlers across requests or backend replicas converge before the audit-chain append.
CREATE TABLE auth_logout_audit_claim (
    session_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    claimed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (session_hash)
) ENGINE=InnoDB;
