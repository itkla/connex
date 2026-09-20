-- Scheduler discovery of abandoned audience attempts runs once per tick for every pinned catalog and
-- therefore carries no workspace predicate, so neither idx_campaign_delivery_send_status
-- (workspace_id, send_id, status) nor idx_campaign_delivery_dispatch_lease
-- (workspace_id, status, dispatch_lease_until) can be seeked for it: both lead with workspace_id.
-- Leading with status and dispatch_lease_owner turns the discovery predicate into two equalities
-- (status = 'dispatching', dispatch_lease_owner IS NULL) plus a range on frequency_reserved_at, so a
-- tick reads only the unleased attempts whose reservation has already expired. Cost then follows the
-- outstanding recovery work rather than the workspace's send history, and a tick with nothing to
-- recover reads no rows at all.
-- Rollback: dropping this index leaves every statement correct and only restores the scan.
CREATE INDEX idx_campaign_delivery_unleased_reservation
    ON campaign_delivery (status, dispatch_lease_owner, frequency_reserved_at);
