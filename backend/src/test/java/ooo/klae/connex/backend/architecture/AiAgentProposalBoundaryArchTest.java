package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.ai.mission.AiAgentSingletonProposalSetPort;
import ooo.klae.connex.backend.tenant.TenantScopeInterceptor;

class AiAgentProposalBoundaryArchTest {
    private static final Path MAIN = Path.of("src/main/java/ooo/klae/connex/backend");

    @Test
    void modelToolsCannotInvokeDecisions() throws Exception {
        for (String source : List.of("AiAssistantToolExecutor.java", "AiAssistantToolCatalog.java", "AiAgentToolSchema.java")) {
            String text = Files.readString(MAIN.resolve("ai/assistant/" + source));
            assertFalse(text.contains("AiAgentProposalDecisionService"), source);
            assertFalse(text.contains("proposal-decisions"), source);
            assertFalse(text.contains("decideLocked("), source);
        }
    }

    @Test
    void missionPublicationUsesMandatoryParticipants() {
        for (var method : AiAgentSingletonProposalSetPort.class.getDeclaredMethods()) {
            if (List.of("allocateSlots", "publishPrepared", "lockInventory", "freeze", "decideLocked", "withdrawPending")
                    .contains(method.getName())) {
                Transactional transaction = method.getAnnotation(Transactional.class);
                assertNotNull(transaction, method.getName());
                assertEquals(Propagation.MANDATORY, transaction.propagation(), method.getName());
            }
        }
    }

    @Test
    void missionProducerDoesNotUseP133Storage() throws Exception {
        try (var paths = Files.walk(MAIN.resolve("ai/mission"))) {
            for (Path path : paths.filter(file -> file.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path);
                assertFalse(source.contains("AiAssistantProposalSetMapper"), path.toString());
                assertFalse(source.contains("ai_assistant_proposal_set"), path.toString());
            }
        }
    }

    @Test
    void missionCanonicalWritesRequireSourceAwarePath() throws Exception {
        String source = Files.readString(MAIN.resolve("ai/mission/AiAgentSingletonProposalSetPort.java"));
        assertTrue(source.contains("insertMissionToolCall"));
        assertFalse(source.contains("insertToolCall("));
        assertFalse(source.contains("proposeTool("));
        assertTrue(TenantScopeInterceptor.SCOPED_NAMESPACES.contains("ooo.klae.connex.backend.mappers.AiAgentActionMapper"));
        String mapper = Files.readString(Path.of("src/main/resources/mappers/AiChatMapper.xml"));
        int start = mapper.indexOf("<insert id=\"insertMissionToolCall\"");
        int end = mapper.indexOf("</insert>", start);
        String insert = mapper.substring(start, end);
        assertTrue(insert.contains("mission_action_id"));
        assertTrue(insert.contains("proposal_origin"));
        assertFalse(insert.contains("proposal_key"));
    }
}
