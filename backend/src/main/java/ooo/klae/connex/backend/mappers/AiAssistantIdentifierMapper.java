package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.AiAssistantIdentifierMention;

/** Visibility-scoped bounded identifier lookup used by Ask Connex masking. */
@Mapper
public interface AiAssistantIdentifierMapper {
    /**
     * Returns one page from visible candidates whose complete result is a deliberate
     * <em>superset</em> of the mentions the masking gate will accept. The resolver alone decides
     * which candidates the turn mentions.
     *
     * <p>Current bundles use binary containment of shared Java literal and label keys. A bundle
     * is current only at the running format version with a byte-exact source-name snapshot and
     * both keys present and neither alias marked unkeyed. Other rows retain the legacy SQL normalization and conservative
     * non-ASCII/bracket fallback, preserving coverage during rollout and old-binary renames.
     *
     * <p>Because the page is a superset, its size is not evidence about the turn: callers must not
     * turn a full page into a refusal on its own. {@code limit} bounds a single page, and the caller
     * must continue after the last kind/id until a short page or admitted-mention overflow.
     *
     * @param workspaceId current tenant workspace
     * @param orgWorkspaceIdsJson control-derived organization workspace scope
     * @param literalText canonical literal turn projection from the masking engine
     * @param labelText canonical label turn projection from the masking engine
     * @param limit maximum rows to materialize per page
     * @param afterKind last visited kind, or the empty string for the first page
     * @param afterId last visited id within that kind, or zero for the first page
     * @return visible candidate rows, ordered by immutable kind and unique id
     */
    List<AiAssistantIdentifierMention> findMentionedRecords(
            @Param("workspaceId") int workspaceId,
            @Param("orgWorkspaceIdsJson") String orgWorkspaceIdsJson,
            @Param("literalText") String literalText,
            @Param("labelText") String labelText,
            @Param("limit") int limit,
            @Param("afterKind") String afterKind,
            @Param("afterId") int afterId);

    /** Returns a keyset page of owned, processable rows whose canonical bundle is stale. */
    List<AiAssistantIdentifierMention> findPersonKeysToBackfill(
            @Param("workspaceId") int workspaceId,
            @Param("afterId") int afterId,
            @Param("limit") int limit,
            @Param("keyVersion") int keyVersion);

    /** Repairs only derived fields when the authoritative name still matches byte-for-byte. */
    int updatePersonKeysIfNameCurrent(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id,
            @Param("name") String name,
            @Param("keyVersion") int keyVersion);

    /** Returns a keyset page of owned, processable rows whose canonical bundle is stale. */
    List<AiAssistantIdentifierMention> findCompanyKeysToBackfill(
            @Param("workspaceId") int workspaceId,
            @Param("afterId") int afterId,
            @Param("limit") int limit,
            @Param("keyVersion") int keyVersion);

    /** Repairs only derived fields when the authoritative name still matches byte-for-byte. */
    int updateCompanyKeysIfNameCurrent(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id,
            @Param("name") String name,
            @Param("keyVersion") int keyVersion);

    /** Returns a keyset page of owned, processable rows whose canonical bundle is stale. */
    List<AiAssistantIdentifierMention> findDealKeysToBackfill(
            @Param("workspaceId") int workspaceId,
            @Param("afterId") int afterId,
            @Param("limit") int limit,
            @Param("keyVersion") int keyVersion);

    /** Repairs only derived fields when the authoritative name still matches byte-for-byte. */
    int updateDealKeysIfNameCurrent(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id,
            @Param("name") String name,
            @Param("keyVersion") int keyVersion);

    /** Counts processable, non-archived owned rows still requiring reconciliation. */
    long countPendingKeys(@Param("workspaceId") int workspaceId, @Param("keyVersion") int keyVersion);
}
