-- One row per lead-lifecycle pass (#559, increment 6 of docs/LEAD_LIFECYCLE.md).
--
-- A contact can enter the lifecycle, be disqualified, be recycled, and enter again. Everything the
-- epic asks reporting to answer — volume, qualification rate, conversion rate, time to convert,
-- time to first response — is a statement about one of those passes, not about the contact's
-- current columns. Reporting from current state was wrong in two ways this table fixes:
--
--   * response outcomes are cleared when a pass ends, so historical response time and breach rate
--     silently disappeared and past reports changed retroactively;
--   * latency measured from the contact's first-ever entry, inflating every recycled-pass
--     conversion.
--
-- The pass is therefore the unit of analysis, and every lead measure is attributed to the pass's
-- entry date, so a cohort's counts and the rates derived from them describe one population and
-- visibly divide into each other.
CREATE TABLE person_lifecycle_pass (
    id                         BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'Lifecycle pass ID',
    workspace_id               INT NOT NULL COMMENT 'Owning workspace ID',
    person_id                  INT NOT NULL COMMENT 'Contact this pass belongs to',
    entered_at                 DATETIME NOT NULL
                                   COMMENT 'When the contact entered the lifecycle for this pass',
    qualified_at               DATETIME NULL COMMENT 'First time this pass reached QUALIFIED',
    converted_at               DATETIME NULL COMMENT 'First time this pass reached CONVERTED',
    disqualified_at            DATETIME NULL COMMENT 'First time this pass reached DISQUALIFIED',
    ended_at                   DATETIME NULL
                                   COMMENT 'When the pass closed by withdrawal or recycling; NULL while open',
    first_response_started_at  DATETIME NULL COMMENT 'When this pass first-response clock started',
    first_responded_at         DATETIME NULL COMMENT 'When the first response was recorded in this pass',
    first_response_due_at      DATETIME NULL COMMENT 'The deadline this pass was held to',
    first_response_breached_at DATETIME NULL COMMENT 'When the deadline was observed to pass unmet',
    -- Milestones cannot precede the pass they belong to. Enforced here because the report divides by
    -- these intervals, and a negative latency would be published as fact.
    CONSTRAINT chk_person_lifecycle_pass_order CHECK (
        (qualified_at IS NULL OR qualified_at >= entered_at)
        AND (converted_at IS NULL OR converted_at >= entered_at)
        AND (disqualified_at IS NULL OR disqualified_at >= entered_at)
        AND (ended_at IS NULL OR ended_at >= entered_at)
        AND (first_responded_at IS NULL OR first_response_started_at IS NOT NULL)
        AND (first_response_breached_at IS NULL OR first_response_started_at IS NOT NULL)),
    CONSTRAINT fk_person_lifecycle_pass_person
        FOREIGN KEY (workspace_id, person_id)
        REFERENCES person(workspace_id, id) ON DELETE CASCADE,
    -- The cohort date axis: every lead measure groups and ranges on entered_at.
    INDEX idx_person_lifecycle_pass_workspace_entered (workspace_id, entered_at),
    -- Finding the one open pass for a contact is on the write path of every transition.
    INDEX idx_person_lifecycle_pass_open (workspace_id, person_id, ended_at)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='One row per lead-lifecycle pass; the reporting spine for #559';

-- Backfill the stage milestones from the append-only transition history, which is complete for
-- stages. A pass runs from a NEW transition until the next NEW for the same contact.
--
-- Response outcomes are deliberately left NULL for backfilled passes: the current columns hold at
-- most the live pass, and any earlier pass's response data was cleared when it ended. Deriving a
-- plausible value would put invented durations into the report that exists to measure real ones.
INSERT INTO person_lifecycle_pass (
    workspace_id, person_id, entered_at, qualified_at, converted_at, disqualified_at, ended_at)
SELECT
    entry.workspace_id,
    entry.person_id,
    entry.changed_at AS entered_at,
    MIN(CASE WHEN milestone.to_stage = 'QUALIFIED' THEN milestone.changed_at END) AS qualified_at,
    MIN(CASE WHEN milestone.to_stage = 'CONVERTED' THEN milestone.changed_at END) AS converted_at,
    MIN(CASE WHEN milestone.to_stage = 'DISQUALIFIED' THEN milestone.changed_at END) AS disqualified_at,
    MIN(CASE WHEN milestone.to_stage IS NULL OR milestone.to_stage = 'RECYCLED'
             THEN milestone.changed_at END) AS ended_at
FROM (
    SELECT
        history.workspace_id,
        history.person_id,
        history.changed_at,
        LEAD(history.changed_at) OVER (
            PARTITION BY history.workspace_id, history.person_id
            ORDER BY history.changed_at, history.id) AS next_entered_at
    FROM person_lifecycle_history history
    WHERE history.to_stage = 'NEW'
) entry
LEFT JOIN person_lifecycle_history milestone
    ON milestone.workspace_id = entry.workspace_id
    AND milestone.person_id = entry.person_id
    AND milestone.changed_at >= entry.changed_at
    AND (entry.next_entered_at IS NULL OR milestone.changed_at < entry.next_entered_at)
GROUP BY entry.workspace_id, entry.person_id, entry.changed_at;

-- Carry the live clock onto the pass that is still open, so the one pass whose response data still
-- exists reports it. Passes that already ended keep NULL, as above.
UPDATE person_lifecycle_pass pass
JOIN person ON person.workspace_id = pass.workspace_id AND person.id = pass.person_id
SET pass.first_response_started_at = person.first_response_started_at,
    pass.first_responded_at = person.first_responded_at,
    pass.first_response_due_at = person.first_response_due_at,
    pass.first_response_breached_at = person.first_response_breached_at
WHERE pass.ended_at IS NULL
  AND person.first_response_due_at IS NOT NULL;
