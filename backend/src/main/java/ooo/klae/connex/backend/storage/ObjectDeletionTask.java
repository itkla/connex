package ooo.klae.connex.backend.storage;

/**
 * Durable private-object deletion task loaded from its owning catalog.
 *
 * @param id queue row identifier
 * @param workspaceId owning workspace, or zero for a control-plane user object
 * @param objectKey private adapter key
 * @param attempts number of delete attempts already recorded
 */
public record ObjectDeletionTask(long id, int workspaceId, String objectKey, int attempts) {}
