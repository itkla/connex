CREATE TABLE sso_domain_mutation_lock (
    singleton_id TINYINT UNSIGNED NOT NULL,
    PRIMARY KEY (singleton_id),
    CONSTRAINT chk_sso_domain_mutation_lock_singleton CHECK (singleton_id = 1)
) ENGINE=InnoDB COMMENT='Singleton lock root serializing global SSO domain mutations';

INSERT INTO sso_domain_mutation_lock (singleton_id) VALUES (1);
