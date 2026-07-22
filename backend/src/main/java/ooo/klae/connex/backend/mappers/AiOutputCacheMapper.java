package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.AiOutputCache;

/**
 * Mapper for {@code AiOutputCache} — persisted AI feature outputs. SQL lives in
 * {@code resources/mappers/AiOutputCacheMapper.xml}. Every statement is workspace-scoped. There is
 * at most one row per {@code (workspace_id, feature, subject_a_id, subject_b_id)}, so writes go
 * through {@code upsert}; deal-scoped features pass {@code 0} for {@code subjectBId}.
 */
public interface AiOutputCacheMapper {
    AiOutputCache getBySubject(
            @Param("workspaceId") int workspaceId,
            @Param("feature") String feature,
            @Param("subjectAId") int subjectAId,
            @Param("subjectBId") int subjectBId);

    int upsert(AiOutputCache entry);

    /**
     * Deletes every cached AI output that retains the person's data across every workspace in the
     * person's organization: person-keyed intro rationales (the person is either subject), and
     * deal-keyed risk rationales for deals the person is a stakeholder of, plus deal briefs where
     * the person is a stakeholder or is currently linked through an activity, note, or task
     * (including same-org grantee workspaces the contact was shared into). Org-anchored via the
     * workspace join off {@code workspaceId}. This purges the listed currently discoverable
     * structured outputs; report narratives and removed-link, indirect-connection, or free-text
     * provenance remain outside this method.
     * @param workspaceId the restricting (owning) workspace, used to resolve the organization
     * @param personId the restricted contact
     * @return the number of cache rows removed
     */
    int deleteForPerson(@Param("workspaceId") int workspaceId, @Param("personId") int personId);
}
