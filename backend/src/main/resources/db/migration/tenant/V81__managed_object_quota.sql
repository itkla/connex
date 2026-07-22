CREATE TABLE object_storage_quota (
    workspace_id  INT PRIMARY KEY,
    used_bytes    BIGINT UNSIGNED NOT NULL DEFAULT 0,
    object_count  INT UNSIGNED NOT NULL DEFAULT 0,
    CONSTRAINT chk_object_storage_quota_used_bytes CHECK (used_bytes >= 0),
    CONSTRAINT chk_object_storage_quota_object_count CHECK (object_count >= 0)
) DEFAULT CHARSET=utf8mb4 COMMENT='Atomic private-object usage aggregate per workspace';

CREATE TABLE managed_object_usage (
    workspace_id  INT NOT NULL,
    object_key    VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    size_bytes    BIGINT UNSIGNED NOT NULL,
    created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (workspace_id, object_key),
    CONSTRAINT chk_managed_object_usage_size CHECK (size_bytes > 0),
    INDEX idx_managed_object_usage_workspace (workspace_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Exact tenant-object ledger for quota release after deletion';

INSERT INTO managed_object_usage (workspace_id, object_key, size_bytes)
SELECT workspace_id, object_key, MAX(size_bytes)
FROM (
    SELECT workspace_id,
           CONCAT('workspaces/', workspace_id, '/attachments/',
               SUBSTRING(url, LENGTH('/api/attachments/content/') + 1)) AS object_key,
           CASE WHEN size IS NULL OR size <= 0 THEN 26214400 ELSE size END AS size_bytes
    FROM attachment
    WHERE REGEXP_LIKE(
        url,
        '^/api/attachments/content/[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}([.][a-z0-9]{1,10})?$',
        'c')
) AS attachment_usage
GROUP BY workspace_id, object_key;

INSERT INTO managed_object_usage (workspace_id, object_key, size_bytes)
SELECT workspace_id,
       CONCAT('workspaces/', workspace_id, '/person-images/', id, '/',
           SUBSTRING(image_url, LENGTH(CONCAT('/api/persons/', id, '/profile-picture/')) + 1)),
       26214400
FROM person
WHERE REGEXP_LIKE(
    image_url,
    CONCAT('^/api/persons/', id,
        '/profile-picture/[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}[.](jpg|png|webp)$'),
    'c');

INSERT INTO managed_object_usage (workspace_id, object_key, size_bytes)
SELECT workspace_id,
       CONCAT('workspaces/', workspace_id, '/company-images/', id, '/',
           SUBSTRING(logo_url, LENGTH(CONCAT('/api/companies/', id, '/logo/')) + 1)),
       26214400
FROM company
WHERE REGEXP_LIKE(
    logo_url,
    CONCAT('^/api/companies/', id,
        '/logo/[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}[.](jpg|png|webp)$'),
    'c');

INSERT INTO object_storage_quota (workspace_id, used_bytes, object_count)
SELECT workspace_id, SUM(size_bytes), COUNT(*)
FROM managed_object_usage
GROUP BY workspace_id;
