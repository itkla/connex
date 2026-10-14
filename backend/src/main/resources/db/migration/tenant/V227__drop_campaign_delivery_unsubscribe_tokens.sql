INSERT INTO campaign_delivery_unsubscribe_token (token_hash, workspace_id, delivery_id)
SELECT d.unsubscribe_token_hash, d.workspace_id, d.id
FROM campaign_delivery d
WHERE d.unsubscribe_token_hash IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM campaign_delivery_unsubscribe_token t
      WHERE t.token_hash = d.unsubscribe_token_hash
  );

ALTER TABLE campaign_delivery
    DROP INDEX uq_campaign_delivery_token_hash,
    DROP INDEX uq_campaign_delivery_token,
    DROP COLUMN unsubscribe_token_hash,
    DROP COLUMN unsubscribe_token;
