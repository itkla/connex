-- ============================================================================
-- app_user.email_verified : whether the account has proven control of its email
-- address. New self-service registrations start unverified when registration
-- verification is enabled (connex.registration-verification.enabled=true) and are
-- flipped true only after redeeming a token sent to the address. Verification gates
-- joining a domain-restricted workspace via a shareable invite link, so the domain
-- allow-list cannot be satisfied by an unverified (spoofable) registration email.
--
-- Existing accounts predate verification and are backfilled to TRUE so nothing they
-- can already do is revoked; the column defaults FALSE so new inserts are unverified
-- unless a trusted path (admin create / bootstrap) marks them verified.
-- ============================================================================

ALTER TABLE app_user
    ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE
    COMMENT 'Whether the account has verified control of its email address';

UPDATE app_user SET email_verified = TRUE;
