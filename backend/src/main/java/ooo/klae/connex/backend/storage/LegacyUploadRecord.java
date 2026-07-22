package ooo.klae.connex.backend.storage;

import lombok.Data;

/**
 * Database reference to one file written by the retired frontend upload handlers.
 */
@Data
public class LegacyUploadRecord {
    private int id;
    private Integer workspaceId;
    private String entityType;
    private Integer entityId;
    private String fileName;
    private String contentType;
    private String url;
}
