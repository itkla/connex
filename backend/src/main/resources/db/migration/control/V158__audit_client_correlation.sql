-- request_id remains the non-spoofable server-minted audit pivot. This separate value is copied
-- from the client-settable correlation header and is intentionally labelled untrusted everywhere.
ALTER TABLE audit_log
    ADD COLUMN untrusted_client_asserted_correlation_id VARCHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT 'Untrusted client-asserted correlation id; never an audit identity'
        AFTER request_id,
    ADD KEY idx_audit_log_untrusted_client_correlation_created
        (untrusted_client_asserted_correlation_id, created_at);
