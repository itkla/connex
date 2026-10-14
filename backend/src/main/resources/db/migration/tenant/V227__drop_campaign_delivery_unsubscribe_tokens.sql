ALTER TABLE campaign_delivery
    DROP INDEX uq_campaign_delivery_token_hash,
    DROP INDEX uq_campaign_delivery_token,
    DROP COLUMN unsubscribe_token_hash,
    DROP COLUMN unsubscribe_token;
