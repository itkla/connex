package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A user's saved view of a records list — a named bundle of filter/sort/search/display
 * configuration, scoped to one {@code recordType} ({@code company}, {@code person}, or
 * {@code deal}). Personal to the owning user within a workspace. {@code configJson} is an
 * opaque JSON blob owned by the client; the backend stores and returns it verbatim.
 * Mapped via {@code SavedViewMapper} / {@code SavedViewMapper.xml}.
 */
@Data
@NoArgsConstructor
public class SavedView {
    private int id;
    private int workspaceId;
    private int userId;
    private String recordType;
    private String name;
    private String configJson;
    private int position;
    private String createdAt;
    private String updatedAt;
}
