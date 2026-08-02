package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.observability.JobRunRecorder;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunDetail;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunStatus;

/**
 * Verifies bounded retention independently for each job/workspace partition.
 */
@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JobRunRecorderIntegrationTest {
    private static final int WORKSPACE_ONE = 900_101;
    private static final int WORKSPACE_TWO = 900_102;
    private static final String JOB_NAME = JobRunRecorder.OBJECT_DELETION_RETRY;
    private static final LocalDateTime STARTED_AT =
            LocalDateTime.of(2026, 7, 21, 10, 0);

    @Autowired private JobRunRecorder recorder;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanPartitions() {
        jdbcTemplate.update(
                "DELETE FROM job_run WHERE job_name = ? AND (workspace_id IN (?, ?) OR workspace_id IS NULL)",
                JOB_NAME,
                WORKSPACE_ONE,
                WORKSPACE_TWO);
    }

    @Test
    void retentionIsPerWorkspaceWithIndependentNullPartitionAndIdTieBreak() {
        record(WORKSPACE_ONE, 0);
        int oldestWorkspaceOneId = newestId(WORKSPACE_ONE);
        for (int index = 1; index < 51; index++) {
            record(WORKSPACE_ONE, index);
        }
        for (int index = 0; index < 3; index++) {
            record(WORKSPACE_TWO, index);
        }
        record(null, 0);
        int oldestNullId = newestId(null);
        for (int index = 1; index < 51; index++) {
            record(null, index);
        }

        List<Integer> workspaceOneIds = ids(WORKSPACE_ONE);
        List<Integer> workspaceTwoIds = ids(WORKSPACE_TWO);
        List<Integer> nullIds = ids(null);

        assertEquals(50, workspaceOneIds.size());
        assertFalse(workspaceOneIds.contains(oldestWorkspaceOneId));
        assertEquals(3, workspaceTwoIds.size());
        assertEquals(50, nullIds.size());
        assertFalse(nullIds.contains(oldestNullId));
    }

    private void record(Integer workspaceId, int sequence) {
        recorder.record(
                JOB_NAME,
                workspaceId,
                JobRunStatus.SUCCEEDED,
                new JobRunDetail(
                        STARTED_AT,
                        Map.of("phase", "retention_test", "deletedCount", sequence)));
    }

    private int newestId(Integer workspaceId) {
        return ids(workspaceId).getLast();
    }

    private List<Integer> ids(Integer workspaceId) {
        if (workspaceId == null) {
            return jdbcTemplate.queryForList(
                    "SELECT id FROM job_run WHERE job_name = ? AND workspace_id IS NULL ORDER BY id",
                    Integer.class,
                    JOB_NAME);
        }
        return jdbcTemplate.queryForList(
                "SELECT id FROM job_run WHERE job_name = ? AND workspace_id = ? ORDER BY id",
                Integer.class,
                JOB_NAME,
                workspaceId);
    }
}
