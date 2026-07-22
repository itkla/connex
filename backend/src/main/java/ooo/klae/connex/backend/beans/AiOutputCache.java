package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A persisted AI feature output, cached to avoid re-prompting the model on every page load.
 * There is at most one row per {@code (workspace_id, feature, subject_a_id, subject_b_id)}; a row is
 * reused while its {@code contentHash} still matches the freshly assembled prompt and regenerated
 * (upserted) otherwise. {@code payload} is the demasked structured content as JSON, stored verbatim.
 * Mapped via {@code AiOutputCacheMapper} / {@code AiOutputCacheMapper.xml}.
 */
@Data
@NoArgsConstructor
public class AiOutputCache {
    private int id;
    private int workspaceId;
    private String feature;
    private int subjectAId;
    private int subjectBId;
    private String contentHash;
    private String payload;
    private int warnings;
    private String generatedAt;
    private String createdAt;
    private String updatedAt;
}
