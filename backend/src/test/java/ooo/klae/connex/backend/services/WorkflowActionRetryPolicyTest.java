package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;

import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.services.WorkflowActionRetryPolicy.RetrySafety;

class WorkflowActionRetryPolicyTest {

    @Test
    void schemaV1ActionsUseTheClosedSafetyTable() {
        WorkflowActionRetryPolicy policy = policy();
        for (String type : List.of(
                "create_task",
                "log_activity",
                "add_tag",
                "remove_tag",
                "create_note",
                "assign_owner",
                "change_stage")) {
            assertEquals(RetrySafety.TRANSACTIONAL, policy.safety(action(type)), type);
        }
        assertEquals(RetrySafety.DEDUPLICATED, policy.safety(action("notify")));
        assertEquals(RetrySafety.DEDUPLICATED, policy.safety(action("send_message")));
        assertEquals(RetrySafety.NONE, policy.safety(action("future_action")));
        assertEquals(RetrySafety.NONE, policy.safety(null));
    }

    @Test
    void onlyTheTransientDatabaseAllowlistRetries() {
        WorkflowActionRetryPolicy policy = policy();

        assertTrue(policy.transientDatabaseFailure(
            new CannotAcquireLockException("lock")));
        assertTrue(policy.transientDatabaseFailure(
            new IllegalStateException(
                "wrapper", new DeadlockLoserDataAccessException("deadlock", null))));
        assertFalse(policy.transientDatabaseFailure(
            new DuplicateKeyException("business conflict")));
        assertFalse(policy.transientDatabaseFailure(
            new IllegalStateException("unexpected")));
    }

    @Test
    void retryDelayIsDeterministicAndBounded() {
        WorkflowActionRetryPolicy policy = policy();

        Duration first = policy.retryDelay(31L, "action", 1);
        assertEquals(first, policy.retryDelay(31L, "action", 1));
        assertTrue(first.compareTo(Duration.ofSeconds(30)) >= 0);
        assertTrue(policy.retryDelay(31L, "action", 3)
            .compareTo(Duration.ofMinutes(15)) <= 0);
    }

    private static WorkflowActionRetryPolicy policy() {
        WorkflowRuntimeProperties properties = mock(WorkflowRuntimeProperties.class);
        when(properties.retryBase()).thenReturn(Duration.ofSeconds(30));
        when(properties.retryMaximum()).thenReturn(Duration.ofMinutes(15));
        return new WorkflowActionRetryPolicy(properties);
    }

    private static RuleAction action(String type) {
        RuleAction action = new RuleAction();
        action.setType(type);
        return action;
    }
}
