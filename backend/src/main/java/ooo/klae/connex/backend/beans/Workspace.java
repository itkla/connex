package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents an isolated CRM workspace.
 */
@Data
@NoArgsConstructor
public class Workspace {
    private int id;
    private String name;
    private String slug;
    private String createdAt;
    private String updatedAt;
}