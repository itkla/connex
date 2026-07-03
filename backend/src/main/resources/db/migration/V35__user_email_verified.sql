-- ============================================================================
-- app_user.email_verified : whether the account has proven control of its email
-- address. New self-service registrations start unverified when registration
-- verification is enabled (connex.registration-verification.enabled=true) and are
-- flipped true only after redeeming a token sent to the address. Verification gates
-- joining a domain-restricted workspace via a shareable invite link, so the domain
-- allow-list cannot be satisfied by an unverified (spoofable) registration email.
--
-- Existing accounts predate verification and are backfilled to TRUE so nothing they
-- can already do is revoked. The column defaults FALSE, but the application sets it
-- explicitly on insert: TRUE for trusted paths (admin create, bootstrap owner) and for
-- self-serve signups when verification is disabled, FALSE only for self-serve signups
-- when verification is enabled (they then earn TRUE by redeeming their token).
-- ============================================================================

ALTER TABLE app_user
    ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE
    COMMENT 'Whether the account has verified control of its email address';

UPDATE app_user SET email_verified = TRUE;
