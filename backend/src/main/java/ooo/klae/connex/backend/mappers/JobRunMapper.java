package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.JobRun;

/**
 * Tenant-scoped persistence for bounded scheduled-job diagnostics.
 */
public interface JobRunMapper {
    void insert(JobRun jobRun);

    int deleteBeyondRetention(
        @Param("jobName") String jobName,
        @Param("workspaceId") Integer workspaceId,
        @Param("status") String status,
        @Param("keepCount") int keepCount);

    List<JobRun> findLatestVisible(
        @Param("workspaceId") int workspaceId,
        @Param("orgWorkspaceIdsJson") String orgWorkspaceIdsJson,
        @Param("status") String status);
}
