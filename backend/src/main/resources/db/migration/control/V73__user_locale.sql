ALTER TABLE app_user
    ADD COLUMN locale VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'en' AFTER timezone,
    ADD CONSTRAINT chk_app_user_locale CHECK (OCTET_LENGTH(locale) = 2 AND locale IN ('en', 'ja'));
