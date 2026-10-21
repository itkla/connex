package ooo.klae.connex.backend.dto;

/** Current global name and database-native LIKE result for a tenant-derived attachment identity. */
public record AttachmentQueryUserFact(int id, String displayName, boolean nameMatches) { }
