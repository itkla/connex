ALTER TABLE deal_document
    ADD INDEX idx_deal_document_recency (workspace_id, generated_at, id),
    ALGORITHM=INPLACE, LOCK=NONE;
