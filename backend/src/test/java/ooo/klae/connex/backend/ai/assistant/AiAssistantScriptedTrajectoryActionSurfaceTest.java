package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.provider.AiToolDefinition;
import ooo.klae.connex.backend.ai.provider.AiToolExchange;
import ooo.klae.connex.backend.ai.provider.scripted.ScriptedAiRequestJournal;
import ooo.klae.connex.backend.beans.AiChatToolCall;
import ooo.klae.connex.backend.beans.AiChatTurn;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.dto.AiChatPageContextDto;

/**
 * Goldens for the assistant's action surface: what a turn is offered and what it may propose.
 *
 * <p>Each golden asserts a control only the framework owns, so it goes red when that control is
 * removed rather than when a fixture changes what it authored.
 */
class AiAssistantScriptedTrajectoryActionSurfaceTest extends AbstractScriptedTrajectoryTest {

    /** The skill the routed golden expects the deterministic router to select. */
    private static final String RELATIONSHIP_BRIEF = "relationship_brief_v1";

    /**
     * A read-only routed skill is offered no write family, so a model that asks for one anyway is
     * refused recoverably and still answers.
     *
     * <p>Before the offer matched the authority, the directory advertised every write family, the
     * load succeeded, and the first write the model then chose ended the turn as a non-closable
     * {@code tool_outside_skill_authority} with no answer. Removing the offer check turns this red
     * three ways: the directory lists the write family, the load row settles executed, and the
     * next request offers its write tools.
     */
    @Test
    void aReadOnlyRoutedSkillIsRefusedAWriteToolsetAndStillAnswers() {
        Person contact = person(
                "Ottoline Fairweather", "ottoline.fairweather@example.invalid", null);

        Trajectory trajectory = run(
                "connex_script_routed_write_set",
                "catch me up on this contact",
                List.of(new AiChatPageContextDto("person", contact.getId())));

        AiChatTurn settled = turnRow(trajectory);
        assertEquals(RELATIONSHIP_BRIEF, settled.getSkillKey(),
                "the offer is only narrowed on a turn that actually ran under a skill");
        assertEquals("resolved", trajectory.status(), trajectory.terminalReason());
        assertEquals(1, trajectory.answers().size(),
                "a refused load is recoverable, so the turn keeps its answer");
        assertTrue(trajectory.answer().contains("nothing was changed"), trajectory.answer());

        List<AiChatToolCall> loads = trajectory.toolCalls().stream()
                .filter(call -> AiAssistantToolCatalog.FIND_TOOLS.equals(call.getToolName()))
                .toList();
        assertEquals(1, loads.size(), trajectory.toolNames().toString());
        assertEquals("failed", loads.getFirst().getStatus());
        assertTrue(loads.getFirst().getResultJson().contains("toolset_unavailable_for_skill"),
                loads.getFirst().getResultJson());
        assertFalse(trajectory.toolNames().contains("change_deal_stage"),
                "no write tool of the refused family may be proposed: " + trajectory.toolNames());

        List<ScriptedAiRequestJournal.Entry> requests = journal().recorded();
        assertEquals(2, requests.size());
        String directory = requests.getFirst().request().systemPrompt();
        for (AiAssistantToolCatalog.Toolset toolset : AiAssistantToolCatalog.LOADABLE) {
            assertEquals(
                    AiAssistantToolCatalog.writeToolsOf(toolset).isEmpty(),
                    directory.contains(toolset.key() + " - " + toolset.summary() + " - "),
                    "a READ skill's directory lists exactly the read families: " + toolset.key());
        }
        List<AiToolExchange> replayed = requests.getLast().request().nativeTools().exchanges();
        assertEquals(1, replayed.size());
        assertTrue(replayed.getFirst().maskedResult().contains("toolset_unavailable_for_skill"),
                "the model is told why the load was refused, so it can answer instead");
        assertFalse(requests.getLast().request().nativeTools().definitions().stream()
                        .map(AiToolDefinition::name)
                        .anyMatch(name -> AiAssistantToolCatalog.writeToolsOf(
                                AiAssistantToolCatalog.Toolset.WRITE_PIPELINE).contains(name)),
                "a refused load must leave the offered vocabulary unwidened");
    }
}
