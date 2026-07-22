CREATE TABLE object_storage_backend_identity (
    singleton_id       TINYINT UNSIGNED NOT NULL,
    provider           VARCHAR(16) NOT NULL,
    filesystem_root    VARCHAR(2048) NULL,
    s3_bucket          VARCHAR(255) NULL,
    s3_region          VARCHAR(255) NULL,
    s3_endpoint        VARCHAR(2048) NULL,
    s3_path_style      BOOLEAN NULL,
    created_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (singleton_id),
    CONSTRAINT chk_object_storage_backend_identity_singleton CHECK (singleton_id = 1),
    CONSTRAINT chk_object_storage_backend_identity_provider CHECK (
        (provider = 'FILESYSTEM'
            AND filesystem_root IS NOT NULL
            AND s3_bucket IS NULL
            AND s3_region IS NULL
            AND s3_endpoint IS NULL
            AND s3_path_style IS NULL)
        OR
        (provider = 'S3'
            AND filesystem_root IS NULL
            AND s3_bucket IS NOT NULL
            AND s3_region IS NOT NULL
            AND s3_path_style IS NOT NULL)
    )
);
