-- First-response SLA timers for contacts in a lead lifecycle (#559, increment 4b of
-- docs/LEAD_LIFECYCLE.md).
--
-- The clock is started by the rule engine's set_response_due action rather than by a workspace-wide
-- setting, so the existing condition language decides which leads get which deadline and no second
-- configuration surface is introduced. All three columns are nullable: a contact with no clock has
-- never been put under an SLA, which is the correct state for every contact that predates this
-- feature and for every relationship that is not a prospect.
ALTER TABLE person
    ADD COLUMN first_response_due_at DATETIME NULL
        COMMENT 'When the first response to this lead is due; NULL when no SLA clock is running',
    ADD COLUMN first_responded_at DATETIME NULL
        COMMENT 'When the first activity was logged against the lead after the clock started',
    ADD COLUMN first_response_breached_at DATETIME NULL
        COMMENT 'When the sweep observed the deadline pass with no response recorded',
    -- A response or a breach only means anything against a deadline, so neither may be recorded
    -- without one. The two are deliberately not mutually exclusive: a lead answered after its
    -- deadline is both breached and responded to, and erasing the breach to record the answer would
    -- destroy the very evidence the SLA exists to produce.
    ADD CONSTRAINT chk_person_first_response_pairing CHECK (
        (first_responded_at IS NULL OR first_response_due_at IS NOT NULL)
        AND (first_response_breached_at IS NULL OR first_response_due_at IS NOT NULL)),
    -- Queue index for the breach sweep. MySQL has no partial index, but it does treat IS NULL as an
    -- index-usable equality, so leading with the two settled-state columns lets the sweep scan only
    -- contacts still owed a first response and read them in deadline order. Answered and breached
    -- contacts keep their deadline forever; without this shape the past-due range would grow
    -- without bound and every sweep would walk the whole history to find the few live rows.
    ADD INDEX idx_person_workspace_first_response_queue (
        workspace_id, first_response_breached_at, first_responded_at, first_response_due_at);
