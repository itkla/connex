package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolExecutor;
import ooo.klae.connex.backend.ai.assistant.AiChatResourceRegistry;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseGuard;
import ooo.klae.connex.backend.beans.AiAgentAction;
import ooo.klae.connex.backend.beans.AiAgentRun;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.dto.AiAgentProposalDecisionRequest;
import ooo.klae.connex.backend.dto.AiAgentRunCreateRequest;
import ooo.klae.connex.backend.mappers.AiAgentActionMapper;
import ooo.klae.connex.backend.mappers.AiAgentRunMapper;
import tools.jackson.databind.ObjectMapper;

/** Production preparation, publication and decision fixtures with a controllable server clock. */
@Import(AbstractAiAgentProposalIntegrationTest.ProposalClockConfiguration.class)
abstract class AbstractAiAgentProposalIntegrationTest extends AbstractAiAgentPrivacyIntegrationTest {
    @Autowired protected AiAgentRunPersistenceService persistence;
    @Autowired protected AiAgentRunService service;
    @Autowired protected AiAgentProposalService preparation;
    @Autowired protected AiAgentProposalReadService reads;
    @Autowired protected AiAgentProposalDecisionService decisions;
    @Autowired protected AiAgentEvidenceService evidence;
    @Autowired protected AiAgentRunMapper runs;
    @Autowired protected AiAgentActionMapper actions;
    @Autowired protected AiAssistantToolExecutor tools;
    @Autowired protected AiRestrictionEpoch restriction;
    @Autowired protected AiProperties properties;
    @Autowired protected ObjectMapper json;
    @Autowired protected ProposalClock clock;
    @MockitoBean protected AiAgentCapabilityGate capability;
    @MockitoBean protected AiAgentScheduler scheduler;
    protected Company primary;
    protected Instant originalNow;

    @BeforeEach
    void prepareProposalFixture() {
        originalNow = Instant.now().truncatedTo(ChronoUnit.MICROS);
        clock.current.set(originalNow);
        when(capability.requireReady()).thenReturn(new byte[32]);
        primary = newCompany();
        jdbc.update("UPDATE company SET updated_at=DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 DAY) WHERE workspace_id=? AND id=?",
                workspace.getId(), primary.getId());
    }

    @AfterEach
    void removeProposalFixture() {
        evidence.clear();
        if (primary != null) {
            jdbc.update("DELETE FROM company WHERE workspace_id=? AND id=?", workspace.getId(), primary.getId());
        }
    }

    protected Fixture staged(String... toolNames) {
        AiChatSession session = new AiChatSession();
        session.setWorkspaceId(workspace.getId());
        session.setCreatedByUserId(currentUser.getId());
        session.setTitle("Mission proposed changes");
        session.setVisibility("private");
        session.setStatus("active");
        chatMapper.insertSession(session);
        var request = new AiAgentRunCreateRequest(UUID.randomUUID().toString(), "record_preparation_v1", "Prepare useful changes",
                new AiAgentRunCreateRequest.Scope("record", "company", primary.getId()), null);
        AiAgentRun run = persistence.create(session.getId(), request, "en", AiAgentCapabilityGate.hash(request.clientRequestKey()),
                json.writeValueAsString(request.scope()), restriction.current(workspace.getId()));
        AiAgentExecutionPolicy policy = persistence.claim(run, new byte[32], new AiRunLeaseGuard(properties.getRunLeaseTtl()), capability);
        var selected = new ArrayList<AiAgentAction>();
        for (String tool : toolNames) {
            evidence.begin(policy);
            policy.execute(() -> {
                AiChatResourceRegistry resources = new AiChatResourceRegistry();
                String handle = resources.register("company", primary.getId());
                tools.execute("get_record", json.createObjectNode().put("handle", handle), resources, false, policy.turn().scope());
                var arguments = json.createObjectNode().put("handle", handle);
                if ("update_record_fields".equals(tool)) {
                    arguments.put("industry", "Prepared industry");
                } else if ("assign_owner".equals(tool)) {
                    arguments.put("owner", currentUser.getUsername());
                } else {
                    throw new IllegalArgumentException("Unsupported fixture tool");
                }
                var candidate = preparation.prepare(policy, tool, arguments, resources, Map.of());
                persistence.publishPrepared(policy, candidate);
                selected.add(actions.getById(workspace.getId(), run.getId(), candidate.action().getId()));
                return null;
            });
            evidence.clear();
        }
        return new Fixture(run.getId(), session.getId(), policy, List.copyOf(selected));
    }

    protected Fixture frozen(String... toolNames) {
        Fixture fixture = staged(toolNames);
        freeze(fixture);
        return fixture;
    }

    protected void freeze(Fixture fixture) {
        fixture.policy().execute(() -> persistence.resolve(fixture.policy(), "The proposed changes are ready for review.",
                "{\"citations\":[],\"resources\":[]}"));
    }

    protected AiAgentProposalDecisionRequest request(Fixture fixture, String decision, AiAgentAction... selected) {
        var run = runs.getRun(workspace.getId(), fixture.runId());
        return new AiAgentProposalDecisionRequest(Long.toString(run.getProposalMembershipRevision()),
                List.of(selected).stream().map(action -> new AiAgentProposalDecisionRequest.Item(action.getActionSlot(),
                        action.getPreparationId(), decision)).toList());
    }

    protected void assertPending(Fixture fixture) {
        for (AiAgentAction action : fixture.selected()) {
            assertEquals("proposed", chatMapper.getMissionToolCall(workspace.getId(), fixture.runId(), action.getId()).getStatus());
        }
    }

    protected record Fixture(int runId, int sessionId, AiAgentExecutionPolicy policy, List<AiAgentAction> selected) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class ProposalClockConfiguration {
        @Bean
        @Primary
        ProposalClock proposalClock() {
            return new ProposalClock(new AtomicReference<>(Instant.now()));
        }
    }

    /** Clock advances only after a test observes the intended real contention boundary. */
    static final class ProposalClock extends Clock {
        private final AtomicReference<Instant> current;

        private ProposalClock(AtomicReference<Instant> current) {
            this.current = current;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current.get();
        }

        void set(Instant value) {
            current.set(value);
        }
    }
}
