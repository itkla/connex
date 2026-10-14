CREATE TABLE campaign_delivery_unsubscribe_token (
    token_hash   CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    workspace_id INT NOT NULL,
    delivery_id  INT NOT NULL,
    INDEX idx_campaign_unsubscribe_delivery (workspace_id, delivery_id),
    CONSTRAINT fk_campaign_unsubscribe_delivery
        FOREIGN KEY (workspace_id, delivery_id)
        REFERENCES campaign_delivery(workspace_id, id) ON DELETE CASCADE
) DEFAULT CHARSET=utf8mb4 COMMENT='Digests of every issued campaign unsubscribe bearer';

INSERT INTO campaign_delivery_unsubscribe_token (token_hash, workspace_id, delivery_id)
SELECT unsubscribe_token_hash, workspace_id, id FROM campaign_delivery;
