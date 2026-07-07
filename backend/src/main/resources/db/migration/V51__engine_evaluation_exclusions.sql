-- Per-record opt-outs from engine evaluation (issue #358).
-- person.risk_excluded: no relationship-cooling nudges about this contact, and their warmth no
--   longer contributes a stakeholder_cold factor to deal risk.
-- person.intro_excluded: never surfaced as an introduction suggestion or intro-opportunity nudge.
-- deal.risk_excluded: skipped by the deal-risk engine (assessment + deal.risk notifications).
-- Plain date reminders (deal.close, task.due) are unaffected by design.
ALTER TABLE person
    ADD COLUMN risk_excluded BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN intro_excluded BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE deal
    ADD COLUMN risk_excluded BOOLEAN NOT NULL DEFAULT FALSE;
