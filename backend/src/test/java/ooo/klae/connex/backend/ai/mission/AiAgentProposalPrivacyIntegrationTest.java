package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import ooo.klae.connex.backend.ai.assistant.AiChatResourceRegistry;
import ooo.klae.connex.backend.mappers.AiAgentActionMapper;

class AiAgentProposalPrivacyIntegrationTest extends AbstractAiAgentProposalIntegrationTest {
    @Autowired private SqlSessionTemplate sqlSessions;
    @MockitoSpyBean private AiAgentActionMapper actionWrites;

    @Test
    void actionPruneCrashRetainsRunCursorAndResumesWithoutSkippingSlots() {
        Fixture fixture = staged("update_record_fields", "assign_owner");
        int beforeCount = actions.countByRoot(workspace.getId(), fixture.runId());
        String operation = invalidate();
        AiAgentActionMapper real = sqlSessions.getMapper(AiAgentActionMapper.class);
        doAnswer(invocation -> {
            int deleted = real.deleteActions(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
            assertEquals(beforeCount, deleted);
            throw new IllegalStateException("Injected failure after action deletion before cursor persistence");
        }).when(actionWrites).deleteActions(anyInt(), anyInt(), anyList());
        assertThrows(IllegalStateException.class, () -> privacyService.batch(workspace.getId(), operation));
        assertEquals(beforeCount, actions.countByRoot(workspace.getId(), fixture.runId()));
        assertEquals(0, privacyMapper.getWork(workspace.getId(), operation).getLastRunId());
        assertEquals(1, privacyMapper.getWork(workspace.getId(), operation).getRetryCount());
        assertPending(fixture);
        doAnswer(invocation -> real.deleteActions(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)))
                .when(actionWrites).deleteActions(anyInt(), anyInt(), anyList());
        finish(operation);
        assertEquals(0, actions.countByRoot(workspace.getId(), fixture.runId()));
        assertEquals(0, privacyMapper.residualCount(workspace.getId(), 1));
        assertTrue(runs.listSegments(workspace.getId(), fixture.runId()).isEmpty());
    }

    @Test
    void completedPurgeRejectsDelayedSingletonPreparationAndFreeze() {
        Fixture fixture = staged("update_record_fields");
        evidence.begin(fixture.policy());
        var candidate = fixture.policy().execute(() -> {
            var resources = new AiChatResourceRegistry();
            String handle = resources.register("company", primary.getId());
            tools.execute("get_record", json.createObjectNode().put("handle", handle), resources, false, fixture.policy().turn().scope());
            return preparation.prepare(fixture.policy(), "assign_owner",
                    json.createObjectNode().put("handle", handle).put("owner", currentUser.getUsername()), resources, Map.of());
        });
        evidence.clear();
        finish(invalidate());
        assertFalse(privacyMapper.getBarrier(workspace.getId()).isBlocked());
        assertThrows(RuntimeException.class, () -> persistence.publishPrepared(fixture.policy(), candidate));
        assertThrows(RuntimeException.class, () -> freeze(fixture));
        assertEquals(0, actions.countByRoot(workspace.getId(), fixture.runId()));
        assertEquals(0, privacyMapper.residualCount(workspace.getId(), 1));
        assertNotNull(runs.getRun(workspace.getId(), fixture.runId()).getPurgedAt());
    }
}
