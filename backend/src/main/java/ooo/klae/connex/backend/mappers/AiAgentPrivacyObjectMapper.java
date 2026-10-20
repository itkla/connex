package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Exact queue identities supply positive deletion evidence independently of work-row locking. */
@Mapper
public interface AiAgentPrivacyObjectMapper {
    int insertPending(@Param("workspaceId") int workspaceId, @Param("operationKey") String operationKey,
            @Param("objectKey") String objectKey, @Param("queueId") long queueId);
    int insertRetained(@Param("workspaceId") int workspaceId, @Param("operationKey") String operationKey,
            @Param("objectKey") String objectKey, @Param("attachmentId") int attachmentId);
    int acknowledgeDeleted(@Param("workspaceId") int workspaceId, @Param("queueId") long queueId,
            @Param("objectKey") String objectKey);
}
