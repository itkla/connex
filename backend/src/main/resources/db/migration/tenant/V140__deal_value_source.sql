ALTER TABLE deal
    ADD COLUMN value_source VARCHAR(16) NOT NULL DEFAULT 'manual'
        COMMENT 'manual = deal.value is operator-entered; line_items = deal.value is derived from deal_line_item totals',
    ADD CONSTRAINT chk_deal_value_source
        CHECK (value_source IN ('manual', 'line_items'));

UPDATE deal d
SET d.value = (
        SELECT COALESCE(SUM(li.line_total), 0)
        FROM deal_line_item li
        WHERE li.deal_id = d.id
          AND li.workspace_id = d.workspace_id
    ),
    d.value_source = 'line_items'
WHERE EXISTS (
    SELECT 1
    FROM deal_line_item li
    WHERE li.deal_id = d.id
      AND li.workspace_id = d.workspace_id
);
