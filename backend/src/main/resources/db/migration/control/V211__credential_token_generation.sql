-- Legacy issuance generations are unknown, including across password or MFA recovery.
-- Leave them NULL: exchange and confirmation refuse these tokens after upgrade.
-- All legacy password-reset and email-change links must be requested again,
-- including links already exchanged into browser grants. Old writers also produce
-- NULL generations; complete the application rollout before requesting fresh links.
ALTER TABLE password_reset_token
    ADD COLUMN credential_generation INT NULL;

ALTER TABLE email_change_token
    ADD COLUMN credential_generation INT NULL;
