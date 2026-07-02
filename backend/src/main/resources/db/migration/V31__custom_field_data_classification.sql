ALTER TABLE custom_field_definition
    ADD COLUMN data_classification VARCHAR(16) NOT NULL DEFAULT 'standard'
        COMMENT 'standard | sensitive | special_care (APPI 要配慮個人情報)' AFTER field_type;
