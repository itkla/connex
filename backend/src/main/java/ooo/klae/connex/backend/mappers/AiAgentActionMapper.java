package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.AiAgentAction;

/** Workspace/root-scoped action persistence; callers retain ordered session and run roots. */
@Mapper
public interface AiAgentActionMapper {

    /** Executes the workspace-scoped listByRoot operation. */
    List<AiAgentAction> listByRoot(@Param("workspaceId") int workspaceId, @Param("rootRunId") int rootRunId);

    /** Executes the workspace-scoped getById operation. */
    AiAgentAction getById(@Param("workspaceId") int workspaceId, @Param("rootRunId") int rootRunId, @Param("id") int id);

    /** Executes the workspace-scoped getBySlot operation. */
    AiAgentAction getBySlot(@Param("workspaceId") int workspaceId, @Param("rootRunId") int rootRunId, @Param("actionSlot") String actionSlot);

    /** Executes the workspace-scoped getBySemantic operation. */
    AiAgentAction getBySemantic(@Param("workspaceId") int workspaceId, @Param("rootRunId") int rootRunId, @Param("semanticSlotKey") String semanticSlotKey);

    /** Locks one exact action before any canonical tool or attempt root. */
    AiAgentAction lockAction(@Param("workspaceId") int workspaceId, @Param("rootRunId") int rootRunId, @Param("id") int id);

    /** Executes the workspace-scoped insertSlot operation. */
    int insertSlot(@Param("action") AiAgentAction action);

    /** Binds original preparation exactly once while the root is locked. */
    int bindPreparation(@Param("action") AiAgentAction action);

    /** Executes the workspace-scoped assignOutputOrdinal operation. */
    int assignOutputOrdinal(@Param("workspaceId") int workspaceId, @Param("rootRunId") int rootRunId, @Param("id") int id, @Param("ordinal") int ordinal);

    /** Executes the workspace-scoped countByRoot operation. */
    int countByRoot(@Param("workspaceId") int workspaceId, @Param("rootRunId") int rootRunId);

    /** Prunes at most twenty-five exact actions, cascading their canonical proposals. */
    int deleteActions(@Param("workspaceId") int workspaceId, @Param("rootRunId") int rootRunId, @Param("ids") List<Integer> ids);
}
