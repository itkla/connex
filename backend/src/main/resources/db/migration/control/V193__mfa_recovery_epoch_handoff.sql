-- ============================================================================
-- Durable MFA-recovery epoch handoff (#1491).
--
-- Session revocation still begins with enumeration, which fails open: a passkey
-- login whose SPRING_SESSION row is written by commitSession() after recovery has
-- enumerated is never seen. Recovery previously could not advance session_epoch,
-- because deleting every credential first can leave a passwordless account with
-- only the ceremony session able to enroll a replacement passkey. Stamping that
-- session before the recovery transaction commits is unsafe: a rollback can leave
-- the servlet session carrying an epoch the database never adopted. Stamping it
-- after commit is also not durable by itself, because the session write happens
-- only when the request unwinds and can be lost, permanently refusing the sole
-- surviving session after its credentials are gone.
--
-- These columns make the post-commit restamp a durable handoff. Recovery advances
-- session_epoch and records the ceremony session's SPRING_SESSION.PRIMARY_ID plus
-- the new epoch in the same locked transaction.
--
-- The primary id, not the logical session id, is what the handoff names. Spring
-- Session rotates SESSION_ID on every authentication while keeping the same
-- physical row, and a request's view of its own session id is fixed for the whole
-- request -- so a concurrent login on the ceremony session can make that id stale
-- no matter where recovery reads it. Binding to the row identity removes that
-- race instead of trying to observe it. The controller stamps the ceremony session only after
-- commit. If that servlet-session write is lost, SessionEpochFilter may repeat the
-- stamp on a later request only when both the session id and the current account
-- epoch match the durable grant. No other session can use the handoff, and a later
-- epoch bump clears it atomically so an old grant cannot survive revocation.
--
-- The grant remains until replacement enrollment commits. Repair deliberately does
-- not consume it: the repair request's own session write can also be lost, so the
-- next request must be able to repeat the handoff. Enrollment clears the grant in
-- the same transaction as the replacement credential. This removes the lockout
-- objection to advancing the epoch while retaining enumerate-and-expire for the
-- sessions it can invalidate immediately.
-- ============================================================================

ALTER TABLE app_user
    ADD COLUMN epoch_restamp_session_primary_id CHAR(36) NULL AFTER session_epoch,
    ADD COLUMN epoch_restamp_epoch INT NULL AFTER epoch_restamp_session_primary_id,
    ADD CONSTRAINT chk_app_user_epoch_restamp_grant
        CHECK (
            (epoch_restamp_session_primary_id IS NULL AND epoch_restamp_epoch IS NULL)
            OR (
                epoch_restamp_session_primary_id IS NOT NULL
                AND epoch_restamp_epoch IS NOT NULL
                AND epoch_restamp_epoch >= 0
            )
        );
