-- Companion to the tenant-plane V207, which clears the ambiguous delivery_provider_config
-- credential references. The rows those references pointed at are stored under the retired shared
-- purpose 'workspace.delivery.provider_credential': email and sms overwrote one another in that
-- single slot, so no channel can prove ownership of the surviving ciphertext.
--
-- Without this purge the rows are unreachable and immortal: the enum constant no longer exists, so
-- no application path can read or delete them, while SecretStore.rewrapBatchToActiveKey would keep
-- re-wrapping compromised material to every future active key.
--
-- secret_value is a control-plane table (TablePlaneRegistry.CONTROL_PLANE_STATE_TABLES) and no
-- foreign key crosses the plane wall, so this statement cannot live beside V207 in the tenant
-- lineage. Operators re-enter both channel API keys after the cutover.
DELETE FROM secret_value
WHERE scope_type = 'workspace'
  AND purpose = 'workspace.delivery.provider_credential';
