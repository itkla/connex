INSERT INTO campaign_delivery_unsubscribe_token (token_hash, workspace_id, delivery_id)
SELECT d.unsubscribe_token_hash, d.workspace_id, d.id
FROM campaign_delivery d
WHERE d.unsubscribe_token_hash IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM campaign_delivery_unsubscribe_token t
      WHERE t.token_hash = d.unsubscribe_token_hash
  );

UPDATE campaign_delivery
SET last_error = REGEXP_REPLACE(last_error, unsubscribe_token, '[redacted]', 1, 0, 'i')
WHERE last_error LIKE CONCAT('%', unsubscribe_token, '%');

UPDATE campaign_delivery
SET provider_message_id = REGEXP_REPLACE(provider_message_id, unsubscribe_token, unsubscribe_token_hash, 1, 0, 'i')
WHERE provider_message_id LIKE CONCAT('%', unsubscribe_token, '%');

UPDATE campaign_delivery
SET provider_message_id = NULL
WHERE provider_message_id LIKE CONCAT('%', unsubscribe_token, '%');

UPDATE campaign_delivery_event e
JOIN campaign_delivery d ON e.workspace_id = d.workspace_id AND e.delivery_id = d.id
SET e.detail = REGEXP_REPLACE(e.detail, d.unsubscribe_token, '[redacted]', 1, 0, 'i')
WHERE e.detail LIKE CONCAT('%', d.unsubscribe_token, '%');

UPDATE IGNORE campaign_delivery_event e
JOIN campaign_delivery d ON e.workspace_id = d.workspace_id AND e.delivery_id = d.id
SET e.provider_event_id = REGEXP_REPLACE(e.provider_event_id, d.unsubscribe_token, d.unsubscribe_token_hash, 1, 0, 'i')
WHERE e.provider_event_id LIKE CONCAT('%', d.unsubscribe_token, '%');

UPDATE campaign_delivery_event e
JOIN campaign_delivery d ON e.workspace_id = d.workspace_id AND e.delivery_id = d.id
SET e.provider_event_id = NULL
WHERE e.provider_event_id LIKE CONCAT('%', d.unsubscribe_token, '%');

ALTER TABLE campaign_delivery
    DROP INDEX uq_campaign_delivery_token_hash,
    DROP INDEX uq_campaign_delivery_token,
    DROP COLUMN unsubscribe_token_hash,
    DROP COLUMN unsubscribe_token;
