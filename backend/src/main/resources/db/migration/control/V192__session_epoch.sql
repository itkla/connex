-- ============================================================================
-- Fail-closed session revocation (#1477).
--
-- Session revocation is enumerate-and-expire, which fails open: a login whose
-- SPRING_SESSION row is written after a revocation has enumerated is never seen
-- and stays live. commitSession() runs after the filter chain unwinds and outside
-- every application transaction, so no lock held by the revoking transaction can
-- span it. An attacker holding the victim's password can therefore log in as the
-- victim resets it and keep the session.
--
-- session_epoch turns that miss from silent into harmless. Login stamps the epoch
-- it authenticated against into the session; revocation bumps the column; a filter
-- compares them on every request and de-authenticates on mismatch. A session
-- established during the race carries the pre-bump value and is refused on its
-- next request.
--
-- The sweep repeats V191's, for the only population an upgrade path does not
-- already cover: databases sitting at exactly V191 -- staging, which auto-deploys
-- main, and developer clones. No released version contains V191, and Flyway is
-- strictly ordered, so every other database has already run it. Sessions carrying
-- no epoch stamp are refused rather than grandfathered, and this leaves none to
-- refuse. Anonymous sessions are spared, as in V191, because they carry the CSRF
-- token and the one-time-link lineage that in-flight reset and verification flows
-- depend on.
-- ============================================================================

ALTER TABLE app_user
    ADD COLUMN session_epoch INT NOT NULL DEFAULT 0 AFTER password_hash;

DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME IS NOT NULL;
