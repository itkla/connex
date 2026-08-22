package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.AiAssistantIdentifierMention;

/** Visibility-scoped bounded identifier lookup used by Ask Connex masking. */
@Mapper
public interface AiAssistantIdentifierMapper {
    /** Returns one globally bounded set of visible record names present in supplied text. */
    List<AiAssistantIdentifierMention> findMentionedRecords(
            @Param("workspaceId") int workspaceId,
            @Param("text") String text,
            @Param("limit") int limit);
}
