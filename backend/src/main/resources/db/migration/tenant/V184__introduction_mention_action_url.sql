-- Repairs introduction mention notifications persisted with the retired
-- '/introductions' action URL (#1348). New rows already target
-- '/overview/introductions', and reconciliation never rewrites mention rows,
-- so stored links would 404 forever without this backfill.
UPDATE notification
SET action_url = '/overview/introductions'
WHERE action_url = '/introductions';
