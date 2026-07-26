package ooo.klae.connex.backend.dto;

/** Active tenant object metadata selected from attachment and image records. */
public record ActiveObjectReference(
        String objectKey,
        String kind,
        int ownerId,
        String persistedUrl,
        Long usageSizeBytes) {

    /** Whether the managed-object usage ledger contains this active object. */
    public boolean usagePresent() {
        return usageSizeBytes != null;
    }
}
