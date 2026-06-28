-- ============================================================================
-- A single global system user that automation rules act as when they run in
-- "system" execution mode (i.e. not on behalf of a workspace member). It cannot
-- authenticate (no password hash, no passkeys) and belongs to no workspace; the
-- rule engine grants it only the automation action permissions and always scopes
-- its actions to the firing rule's own workspace at execution time. Identified at
-- runtime by its reserved username.
-- ============================================================================

INSERT INTO app_user (username, display_name, email, password_hash, timezone)
VALUES ('__connex_system__', 'Automation', '__connex_system__@connex.internal', NULL, 'UTC');
