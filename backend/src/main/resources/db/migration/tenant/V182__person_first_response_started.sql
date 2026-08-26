-- When the first-response SLA clock started (#559, increment 6 of docs/LEAD_LIFECYCLE.md).
--
-- V180 stored the deadline, the response, and the breach, which is everything the sweep and the
-- queue need but not enough to report elapsed time to first response: the deadline is
-- start + N hours and N was never stored, so the start cannot be recovered from it. Increment 4b
-- recorded that as a deferral; this is the follow-through.
--
-- Deliberately not backfilled. A clock already running has a real start time that this column cannot
-- know, and inventing one — created_at, the deadline minus an assumed window — would put fabricated
-- durations into the very report that exists to measure real ones. Existing clocks therefore report
-- no elapsed time, and every clock started from here on reports a true one.
--
-- Deliberately no CHECK pairing this with the deadline. During a rolling deploy a pre-V182 binary
-- still clears a clock by nulling due/responded/breached without knowing about this column, and a
-- constraint requiring a deadline whenever a start exists would make the database reject that
-- write. The service maintains the pairing; the schema does not need to make a mixed-version
-- rollout impossible to hold it.
ALTER TABLE person
    ADD COLUMN first_response_started_at DATETIME NULL
        COMMENT 'When the first-response clock started; NULL for clocks predating this column';
