package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.AiAssistantPrivacyFence;
import ooo.klae.connex.backend.beans.AiAssistantPrivacyOperation;

/** Control coordination statements use the caller's transaction-bound connection. */
@Mapper
public interface AiAssistantPrivacyControlMapper {
    List<ooo.klae.connex.backend.ai.assistant.AiAssistantWorkspacePrivacy> notificationWorkspaceGenerations();
    AiAssistantPrivacyFence getFence(@Param("organizationId") int organizationId);
    AiAssistantPrivacyFence lockFenceForShare(@Param("organizationId") int organizationId);
    AiAssistantPrivacyFence lockFence(@Param("organizationId") int organizationId);
    int insertFence(@Param("organizationId") int organizationId, @Param("operationKey") String operationKey);
    int advanceFence(@Param("organizationId") int organizationId, @Param("generation") long generation);
    AiAssistantPrivacyOperation getOperation(@Param("organizationId") int organizationId, @Param("operationKey") String operationKey);
    List<AiAssistantPrivacyOperation> listOperations(@Param("organizationId") int organizationId);
    int insertOperation(@Param("operation") AiAssistantPrivacyOperation operation);
    int attachAcceptanceAudit(@Param("organizationId") int organizationId, @Param("operationKey") String operationKey, @Param("auditId") int auditId, @Param("fingerprint") byte[] fingerprint);
    int reconcileWorkspaceIntents(@Param("organizationId") int organizationId, @Param("operationKey") String operationKey);
    int verifyWorkspace(@Param("organizationId") int organizationId, @Param("operationKey") String operationKey, @Param("workspaceId") int workspaceId, @Param("catalog") String catalog, @Param("revision") long revision);
    long countUnverified(@Param("organizationId") int organizationId, @Param("operationKey") String operationKey);
    int completeOperation(@Param("organizationId") int organizationId, @Param("operationKey") String operationKey);
    List<Integer> operationWorkspaceIds(@Param("organizationId") int organizationId, @Param("operationKey") String operationKey);
    AiAssistantPrivacyOperation lockOperation(@Param("organizationId") int organizationId, @Param("operationKey") String operationKey);
    Integer lockWorkspaceIntent(@Param("organizationId") int organizationId, @Param("operationKey") String operationKey, @Param("workspaceId") int workspaceId);
    int reopenWorkspace(@Param("organizationId") int organizationId, @Param("operationKey") String operationKey, @Param("workspaceId") int workspaceId);
    String workspaceLifecycleState(@Param("workspaceId") int workspaceId);
    long countIncompleteOperations(@Param("organizationId") int organizationId);
    int deleteWorkspaceIntents(@Param("organizationId") int organizationId);
    int deleteOperations(@Param("organizationId") int organizationId);
    int deleteFence(@Param("organizationId") int organizationId);
    int reopenBootstrap(@Param("organizationId") int organizationId, @Param("operationKey") String operationKey);
    int reopenOperation(@Param("organizationId") int organizationId, @Param("operationKey") String operationKey);
    int completeBootstrap(@Param("organizationId") int organizationId, @Param("operationKey") String operationKey);
}
