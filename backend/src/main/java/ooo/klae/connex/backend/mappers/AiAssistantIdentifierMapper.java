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
     * <p>A row is returned when its SQL-normalized name occurs literally in the canonical text, and
     * also whenever that SQL-normalized name leaves printable ASCII or contains a label bracket.
     * The bracket fallback lets Java apply the shared source-mapped record-link label projection without
     * trusting a link target as a resource identity. The non-ASCII condition is the escape
     * hatch: MySQL has no NFKC operation, cannot delete Java's default-ignorable set, and its
     * {@code LOWER} does not unify dotless I or final sigma with their simple-fold equivalents.
     * These differences leave non-ASCII in the SQL name, so the stored-name fallback retains it.
     * A non-ASCII stored name may canonicalize differently in SQL than in Java, and dropping it here
     * would leave a real mention unseeded. The text operand must arrive already canonicalized by
     * the caller. A fallback on that operand would return the whole corpus for any non-ASCII turn,
     * so only the stored name has this escape condition.
     *
     * <p>Because the page is a superset, its size is not evidence about the turn: callers must not
     * turn a full page into a refusal on its own. {@code limit} bounds a single page, and the caller
     * must continue after the last kind/id until a short page or admitted-mention overflow.
     *
     * @param workspaceId current tenant workspace
     * @param orgWorkspaceIdsJson control-derived organization workspace scope
     * @param text canonical literal and label lookup forms produced by the masking engine
     * @param limit maximum rows to materialize per page
     * @param afterKind last visited kind, or the empty string for the first page
     * @param afterId last visited id within that kind, or zero for the first page
     * @return visible candidate rows, ordered by immutable kind and unique id
     */
    List<AiAssistantIdentifierMention> findMentionedRecords(
            @Param("workspaceId") int workspaceId,
            @Param("orgWorkspaceIdsJson") String orgWorkspaceIdsJson,
            @Param("text") String text,
            @Param("limit") int limit,
            @Param("afterKind") String afterKind,
            @Param("afterId") int afterId);
}
