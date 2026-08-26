-- Ask Connex proactive state: per-member brief schedules and typed watches.
--
-- Both tables are tenant-plane org data. User identifiers are deliberately bare integers because
-- app_user and workspace_member live on the control plane and no foreign key may cross the wall
-- (V65). The (user_id, workspace_id) indexes are what the offboarding fan-out needs so an erased
-- account's schedules and watches can be deleted without a full-table scan.

CREATE TABLE ai_brief_schedule (
    workspace_id        INT NOT NULL,
    id                  INT AUTO_INCREMENT PRIMARY KEY,
    user_id             INT NOT NULL,
    time_zone           VARCHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'UTC',
    daily_enabled       BOOLEAN NOT NULL DEFAULT FALSE,
    daily_hour          TINYINT NOT NULL DEFAULT 8,
    weekly_enabled      BOOLEAN NOT NULL DEFAULT FALSE,
    weekly_weekday      TINYINT NOT NULL DEFAULT 1,
    weekly_hour         TINYINT NOT NULL DEFAULT 8,
    -- The local date a run was last claimed for, not the date it was delivered. Claiming the date
    -- before the turn starts is what makes a run at-most-once per member per local day across
    -- instances, and what stops a failed brief from being retried until the next period.
    last_daily_claim_on DATE NULL,
    last_weekly_claim_on DATE NULL,
    -- One in-flight generated brief. Delivery is a second pass so a failed or timed-out turn is
    -- never announced: the sweep that observes a terminal turn either delivers it or drops it.
    pending_kind        VARCHAR(8)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    pending_session_id  INT NULL,
    pending_turn_id     INT NULL,
    pending_started_at  DATETIME(6) NULL,
    -- The session the most recently delivered brief lives in, so the command centre can offer it
    -- without scanning turn history. It is a plain identifier, not a reference: the session may have
    -- been archived or purged since, and the reader re-resolves it under its own authorization.
    last_delivered_session_id INT NULL,
    last_delivered_kind VARCHAR(8)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    last_delivered_at   DATETIME(6) NULL,
    last_failure_at     DATETIME(6) NULL,
    last_failure_reason VARCHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT chk_ai_brief_schedule_daily_hour CHECK (daily_hour BETWEEN 0 AND 23),
    CONSTRAINT chk_ai_brief_schedule_weekly_hour CHECK (weekly_hour BETWEEN 0 AND 23),
    CONSTRAINT chk_ai_brief_schedule_weekday CHECK (weekly_weekday BETWEEN 1 AND 7),
    CONSTRAINT chk_ai_brief_schedule_pending_kind
        CHECK (pending_kind IS NULL OR pending_kind IN ('daily', 'weekly')),
    CONSTRAINT chk_ai_brief_schedule_delivered_kind
        CHECK (last_delivered_kind IS NULL OR last_delivered_kind IN ('daily', 'weekly')),
    CONSTRAINT chk_ai_brief_schedule_pending_pairing
        CHECK ((pending_kind IS NULL AND pending_session_id IS NULL AND pending_turn_id IS NULL)
            OR (pending_kind IS NOT NULL AND pending_session_id IS NOT NULL
                AND pending_turn_id IS NOT NULL)),
    UNIQUE KEY uq_ai_brief_schedule_member (workspace_id, user_id),
    UNIQUE KEY uq_ai_brief_schedule_workspace_id (workspace_id, id),
    INDEX idx_ai_brief_schedule_daily_due
        (workspace_id, daily_enabled, last_daily_claim_on, id),
    INDEX idx_ai_brief_schedule_weekly_due
        (workspace_id, weekly_enabled, last_weekly_claim_on, id),
    INDEX idx_ai_brief_schedule_pending (workspace_id, pending_turn_id),
    INDEX idx_ai_brief_schedule_user (user_id, workspace_id)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Per-member Ask Connex daily and weekly brief schedules';

CREATE TABLE ai_watch (
    workspace_id      INT NOT NULL,
    id                INT AUTO_INCREMENT PRIMARY KEY,
    owner_user_id     INT NOT NULL,
    watch_type        VARCHAR(48)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    subject_kind      VARCHAR(16)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    subject_id        INT NOT NULL,
    -- Exactly one threshold column carries the declared condition; which one is decided by the
    -- watch type. Keeping them as typed columns rather than a JSON blob is what lets the trigger be
    -- stated back to the member verbatim and evaluated by deterministic SQL/service reads.
    threshold_band    VARCHAR(8)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    threshold_days    INT NULL,
    threshold_level   VARCHAR(8)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    status            VARCHAR(16)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'active',
    cooldown_days     INT NOT NULL DEFAULT 7,
    expires_on        DATE NULL,
    last_evaluated_at DATETIME(6) NULL,
    last_fired_at     DATETIME(6) NULL,
    -- The deterministic state token that last fired. A repeat evaluation with the same token inside
    -- the cooldown is a no-op, so replay, backfill, and every-sweep re-evaluation cannot flood.
    last_fired_state  VARCHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT chk_ai_watch_type
        CHECK (watch_type IN ('relationship_cooling', 'no_interaction',
            'commitment_overdue', 'deal_risk_threshold')),
    CONSTRAINT chk_ai_watch_subject_kind
        CHECK (subject_kind IN ('person', 'company', 'deal')),
    CONSTRAINT chk_ai_watch_status CHECK (status IN ('active', 'paused')),
    CONSTRAINT chk_ai_watch_band
        CHECK (threshold_band IS NULL OR threshold_band IN ('warm', 'cool', 'cold')),
    CONSTRAINT chk_ai_watch_level
        CHECK (threshold_level IS NULL OR threshold_level IN ('medium', 'high')),
    CONSTRAINT chk_ai_watch_days
        CHECK (threshold_days IS NULL OR threshold_days BETWEEN 1 AND 365),
    CONSTRAINT chk_ai_watch_cooldown CHECK (cooldown_days BETWEEN 1 AND 90),
    UNIQUE KEY uq_ai_watch_owner_subject
        (workspace_id, owner_user_id, watch_type, subject_kind, subject_id),
    UNIQUE KEY uq_ai_watch_workspace_id (workspace_id, id),
    INDEX idx_ai_watch_evaluation (workspace_id, status, watch_type, id),
    INDEX idx_ai_watch_owner (owner_user_id, workspace_id)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Typed user-defined Ask Connex watches over source-owned CRM conditions';
