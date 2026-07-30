CREATE TABLE organization_duplicate_decision_lock (
    organization_id INT NOT NULL,
    PRIMARY KEY (organization_id),
    CONSTRAINT fk_org_duplicate_decision_lock_organization
        FOREIGN KEY (organization_id) REFERENCES organization(id) ON DELETE CASCADE
) ENGINE=InnoDB;

INSERT INTO organization_duplicate_decision_lock (organization_id)
SELECT id FROM organization;
