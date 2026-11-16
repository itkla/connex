-- Refuse a table rebuild; updated_at remains a freshness timestamp, not a mutation token.
ALTER TABLE contact_channel_consent
    ADD COLUMN version BIGINT NOT NULL DEFAULT 1,
    ALGORITHM=INSTANT, LOCK=DEFAULT;

-- Cover every writer, including trusted unsubscribe and provider revocations.
CREATE TRIGGER trg_contact_consent_version
BEFORE UPDATE ON contact_channel_consent
FOR EACH ROW
    SET NEW.version = OLD.version + 1;
