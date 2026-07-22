package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.ApprovalPolicy;

/** Mapper for {@code approval_policy}; every statement is workspace-scoped. */
public interface ApprovalPolicyMapper {
    List<ApprovalPolicy> getAll(@Param("workspaceId") int workspaceId);
    List<ApprovalPolicy> getActive(@Param("workspaceId") int workspaceId);
    ApprovalPolicy getById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    int insert(ApprovalPolicy policy);
    int update(ApprovalPolicy policy);
    int delete(@Param("workspaceId") int workspaceId, @Param("id") int id);
}
