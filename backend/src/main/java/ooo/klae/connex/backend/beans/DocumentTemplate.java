package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A workspace-scoped, admin-managed commercial-document template. The section fields
 * ({@code title}/{@code intro}/{@code terms}/{@code footer}) may contain {@code {{merge tokens}}}
 * resolved server-side when a document is generated from this template. Newer templates carry a
 * free-form {@code body} (ProseMirror/Tiptap JSON) authored in the block builder; when present it
 * supersedes the legacy section fields for rendering.
 */
@Data
@NoArgsConstructor
public class DocumentTemplate {
    private int id;
    private int workspaceId;
    private String name;
    private String type;
    private String locale;
    private String title;
    private String intro;
    private String terms;
    private String footer;
    private String body;
    private boolean active = true;
    private String createdAt;
    private String updatedAt;
}
