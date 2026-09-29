-- One heartbeat lease row per leasable AI run subject (GitHub #1788).
--
-- Deliberately NOT columns on ai_chat_turn: that table carries ON UPDATE CURRENT_TIMESTAMP(6) and
-- its absolute-lifetime expiry predicates read updated_at, so a heartbeat written there would
-- refresh the very timestamp that bounds a stuck turn.
--
-- No foreign key to any subject table: the key is polymorphic by design so a later agent-run
-- subject reuses this table, mapper, and sweeper unchanged. Tombstoned rows are collected by the
-- lease sweeper's reap pass; tenant teardown deletes by workspace_id.
--
-- Strictly additive: no column, constraint, index, or status value on an existing table changes,
-- so an instance running the previous binary alongside this one is unaffected and a rollback that
-- retains the migration leaves a table the old binary never touches.
CREATE TABLE ai_run_lease (
    workspace_id  INT NOT NULL,
    subject_kind  VARCHAR(16)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    subject_id    BIGINT NOT NULL,
    -- NULL means the lease is released (tombstoned). The row itself is retained so that epoch keeps
    -- increasing across repeated claims of one subject; deleting on release would restart epoch at
    -- 1 and let a revived stale owner match a later claim's row.
    owner         CHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    -- Monotonic fencing token, bumped on every acquire and every takeover. A revived stale owner's
    -- renew, tombstone, or takeover matches zero rows and learns it lost.
    epoch         BIGINT UNSIGNED NOT NULL,
    acquired_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    heartbeat_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    -- Always written as DATE_ADD(CURRENT_TIMESTAMP(6), ...) by the mapper, never a JVM instant:
    -- every pooled connection is pinned to UTC by the Hikari connection-init-sql, so the database
    -- is one shared clock and instance skew cannot move a lease deadline.
    expires_at    DATETIME(6) NOT NULL,
    released_at   DATETIME(6) NULL,

    PRIMARY KEY (workspace_id, subject_kind, subject_id),
    CONSTRAINT chk_ai_run_lease_subject_kind
        CHECK (subject_kind IN ('chat_turn', 'agent_run')),
    CONSTRAINT chk_ai_run_lease_subject_id CHECK (subject_id > 0),
    CONSTRAINT chk_ai_run_lease_epoch CHECK (epoch > 0),
    CONSTRAINT chk_ai_run_lease_expiry CHECK (expires_at >= acquired_at),
    -- Held and released are the only two states; neither column may drift out of step with the
    -- other, so a half-written release is rejected by the database rather than swept later.
    CONSTRAINT chk_ai_run_lease_release_pair
        CHECK ((owner IS NULL) = (released_at IS NOT NULL)),
    -- Drives the sweeper's expired-lease scan, which also binds owner IS NOT NULL. workspace_id
    -- leads because a DIRECT teardown declaration requires its workspace column to lead an index.
    INDEX idx_ai_run_lease_expiry
        (workspace_id, expires_at, subject_kind, subject_id),
    -- Drives the tombstone reap pass.
    INDEX idx_ai_run_lease_released
        (workspace_id, released_at)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Heartbeat lease and fencing epoch for one durable AI run';
