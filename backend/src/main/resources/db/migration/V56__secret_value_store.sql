CREATE TABLE secret_value (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'Secret reference id',
    scope_type         VARCHAR(32) NOT NULL COMMENT 'organization | workspace | instance',
    scope_id           INT NOT NULL COMMENT 'Owning scope id; 0 for instance scope',
    purpose            VARCHAR(96) NOT NULL COMMENT 'Approved secret purpose token',
    key_id             VARCHAR(128) NOT NULL COMMENT 'Configured key-encryption-key id',
    key_algorithm      VARCHAR(64) NOT NULL COMMENT 'Key wrapping algorithm',
    data_algorithm     VARCHAR(64) NOT NULL COMMENT 'Secret payload encryption algorithm',
    encrypted_data_key MEDIUMTEXT NOT NULL COMMENT 'Envelope-encrypted per-secret data key',
    ciphertext         MEDIUMTEXT NOT NULL COMMENT 'Encrypted secret payload',
    created_at         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
    updated_at         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Last update timestamp',
    rotated_at         DATETIME NULL COMMENT 'Last payload/key rotation timestamp',
    UNIQUE KEY uq_secret_value_scope_purpose (scope_type, scope_id, purpose),
    KEY idx_secret_value_scope (scope_type, scope_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Envelope-encrypted integration secrets';
