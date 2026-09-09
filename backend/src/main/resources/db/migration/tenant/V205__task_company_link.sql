ALTER TABLE task
    ADD COLUMN company_id INT NULL AFTER deal_id,
    ADD INDEX idx_task_company (company_id),
    ADD CONSTRAINT fk_task_company
        FOREIGN KEY (company_id) REFERENCES company(id) ON DELETE SET NULL;
