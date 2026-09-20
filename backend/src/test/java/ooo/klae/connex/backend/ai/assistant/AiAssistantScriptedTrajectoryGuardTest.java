package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.masking.MaskingEngine;
import ooo.klae.connex.backend.beans.AiChatToolCall;
import ooo.klae.connex.backend.beans.AiChatTurn;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.dto.AiChatPageContextDto;
import ooo.klae.connex.backend.exceptions.ConflictException;

/**
 * Goldens 9-15: the server-owned controls a fixture cannot fake, each run as a whole turn.
 *
 * <p>Every golden here is judged by one question: would it go red if the production guard it names
 * were deleted? So none of them asserts anything a script authored. They assert a terminal reason
 * only a server-side refusal produces, a durable row a guard refused to write, or — where the
 * refusal happens above the provider — that the request journal proves nothing left at all.
 *
 * <p><b>Two goldens move state the fixture has no way to reach.</b> The routed-skill golden anchors
 * its turn to a page-context record so the deterministic router selects a skill, and the
 * restriction-epoch golden advances the workspace epoch from the loop thread between two model
 * steps. Both are deliberate: the guards under test only exist on those paths, and reaching them
 * any other way would rehearse different code.
 */
class AiAssistantScriptedTrajectoryGuardTest extends AbstractScriptedTrajectoryTest {

    /** The skill the routed golden expects the deterministic router to select. */
    private static final String RELATIONSHIP_BRIEF = "relationship_brief_v1";

    @Test
    void aRoutedTurnCannotCallAWriteToolItsSkillNeverDeclared() {
        Person contact = person("Marisol Ardenne", "marisol.ardenne@example.invalid", null);

        Trajectory trajectory = run(
                "connex_script_skill_authority",
                "catch me up on this contact",
                List.of(new AiChatPageContextDto("person", contact.getId())));

        AiChatTurn settled = turnRow(trajectory);
        assertEquals(RELATIONSHIP_BRIEF, settled.getSkillKey(),
                "this golden is only about skill authority while the turn actually ran under a "
                        + "skill; an unrouted turn would pass the write through and prove nothing");
        assertEquals("failed", trajectory.status());
        assertEquals("tool_outside_skill_authority", trajectory.terminalReason());
        assertTrue(trajectory.toolNames().contains("find_tools"),
                "the turn had to widen its own vocabulary before it could name the write tool at "
                        + "all: " + trajectory.toolNames());
        assertFalse(trajectory.toolNames().contains("create_task"),
                "the refusal must land before the write is even proposed: "
                        + trajectory.toolNames());
        assertEquals(0, tasksFor(contact.getId()),
                "a skill whose authority is READ must leave no write behind");
        assertEquals(List.of(), trajectory.answers(),
                "a turn refused for exceeding its skill's authority delivers no answer");
    }

    @Test
    void aModelBelowTheContextFloorIsRefusedBeforeAnythingLeaves() {
        person("Corbin Aldwyn", "corbin.aldwyn@example.invalid", null);
        useCapabilityClass("scripted-small-context");

        Trajectory trajectory = run(
                "connex_script_small_context", "check this contact before I call them");

        assertEquals("failed", trajectory.status());
        assertEquals("context_window_too_small", trajectory.terminalReason());
        assertTrue(journal().recorded().isEmpty(),
                "the floor is enforced above the provider, so a turn refused for it must not have "
                        + "handed the provider anything — and this fixture would have answered");
        assertEquals(List.of(), trajectory.toolNames());
        assertEquals(List.of(), trajectory.answers());
    }

    @Test
    void aClientErrorOnTheFirstNativeAttemptDegradesTheTurnToTheJsonProtocol() {
        Person contact = person("Odessa Bellweather", "odessa.bellweather@example.invalid", null);

        Trajectory trajectory = run(
                "connex_script_native_degradation", "look this contact up");

        assertEquals("resolved", trajectory.status(), trajectory.terminalReason());
        assertNotNull(journal().recorded().getFirst().request().nativeTools(),
                "the turn must really have opened on the native protocol, or the degradation it "
                        + "claims to pin never happened");
        assertNull(journal().recorded().get(1).request().nativeTools(),
                "a client-error rejection on the first native attempt clears the native state and "
                        + "retries the same position through the JSON protocol");
        assertEquals(List.of("search_records"), trajectory.toolNames(),
                "the degraded turn still runs its tool step and settles, rather than failing");
        assertTrue(trajectory.answer().contains("](person:" + contact.getId() + ")"),
                "the JSON-protocol answer is rewritten into a durable record link like any other: "
                        + trajectory.answer());
    }

    @Test
    void repeatingOneToolCallEndsTheTurnOnTheNoProgressGuard() {
        person("Silas Thornbury", "silas.thornbury@example.invalid", null);

        Trajectory trajectory = run(
                "connex_script_no_progress", "look this contact up");

        assertEquals("failed", trajectory.status());
        assertEquals("no_progress", trajectory.terminalReason());
        assertEquals(List.of("search_records"), trajectory.toolNames(),
                "a replayed call is answered from the turn's own cache, so it never becomes a "
                        + "second durable tool call: " + trajectory.toolNames());
        assertEquals(List.of(), trajectory.answers(),
                "a turn that made no progress must not deliver an answer anyway");
    }

    @Test
    void aStreamedAnswerReachesTheTranscriptThroughItsOwnDeltas() {
        Person contact = person("Verity Ashcombe", "verity.ashcombe@example.invalid", null);
        useCapabilityClass("scripted-native-stream");

        Trajectory trajectory = run(
                "connex_script_streamed_answer", "check where this one stands");

        AiChatTurn settled = turnRow(trajectory);
        assertEquals("resolved", trajectory.status(), trajectory.terminalReason());
        assertTrue(settled.isStreamed(),
                "a streaming-capable provider must give the turn a streamed transcript, or the "
                        + "delta ordering this golden is about was never exercised");
        assertEquals(List.of("search_records"), trajectory.toolNames());
        assertTrue(trajectory.answer().contains("](person:" + contact.getId() + ")"),
                "the settled answer carries a durable record link: " + trajectory.answer());
        assertTrue(trajectory.answer().endsWith("nothing is waiting on you."),
                "the settled answer is the whole projection of the ordered deltas, not a prefix: "
                        + trajectory.answer());
        assertEquals(trajectory.answer(), settled.getPartialContent(),
                "the stream a member read and the answer the transcript kept must be the same "
                        + "text; a projection that disagreed with the settled answer is the "
                        + "failure the streamed path's own comparison exists to catch");
    }

    @Test
    void anAnswerCarryingSpecialCareContentIsExcludedFromTheTranscript() {
        Person contact = person("Rosalind Pemberly", "rosalind.pemberly@example.invalid", null);

        Trajectory trajectory = run(
                "connex_script_special_care", "check this contact for me");

        assertEquals("resolved", trajectory.status(), trajectory.terminalReason());
        assertEquals(List.of("search_records"), trajectory.toolNames(),
                "the turn ran its read and produced an answer; the exclusion is the screen's "
                        + "doing, not an early failure");
        assertEquals(MaskingEngine.OMITTED_BY_POLICY, trajectory.answer(),
                "suspected special-care free text is excluded from the durable answer rather than "
                        + "masked into it, so the record label the model wrote never lands either");
    }

    /**
     * A restriction that lands between two model steps refuses the next egress.
     *
     * <p>Scoped deliberately. This pins the fence and its dispatch accounting; it does not pin the
     * durable-partial purge, which only has something to purge on a streamed turn whose partial is
     * already durable. The epoch would have to advance between the stream's last batch and the
     * turn's terminal write, and nothing this harness can reach runs there — the step hook fires
     * before the emission, so the very step it arms is the step the fence refuses.
     */
    @Test
    void anEpochAdvancingBetweenStepsRefusesTheNextEgress() {
        person("Juniper Calloway", "juniper.calloway@example.invalid", null);
        onScriptedStep((scriptId, completedToolCalls) -> {
            if (completedToolCalls == 1) {
                advanceRestrictionEpoch();
            }
        });

        Trajectory trajectory = run(
                "connex_script_restriction_epoch", "look this contact up");

        assertEquals("failed", trajectory.status());
        assertEquals("restrictions_changed", trajectory.terminalReason());
        assertEquals(List.of("search_records"), trajectory.toolNames(),
                "the step that ran before the restriction stays durable");
        assertEquals(2, journal().recorded().size(),
                "the provider was handed both steps; the second is the one the fence refused");
        assertEquals(1, journal().dispatched().size(),
                "the epoch fence sits above the send point, so the refused step never dispatched");
        assertEquals(List.of(), trajectory.answers(),
                "an answer assembled from inputs the workspace has since restricted is not "
                        + "delivered");
    }

    @Test
    void approvingAProposalWhoseRecordMovedIsRefused() {
        Company customer = company("Wexford Milling");
        Pipeline pipeline = pipeline("Guard pipeline");
        Stage discovery = stage(pipeline, "Discovery", 0);
        stage(pipeline, "Negotiation", 1);
        Deal renewal = deal("Wexford Renewal", pipeline, discovery, customer);

        Trajectory trajectory = run(
                "connex_script_confirm_proposal", "move this one along if you can");

        assertEquals("resolved", trajectory.status(), trajectory.terminalReason());
        AiChatToolCall proposal = trajectory.toolCalls().stream()
                .filter(call -> "change_deal_stage".equals(call.getToolName()))
                .findFirst()
                .orElseThrow();
        assertEquals("proposed", proposal.getStatus());

        touch("deal", renewal.getId());

        authenticate();
        try {
            assertThrows(ConflictException.class,
                    () -> writeToolService().approve(trajectory.sessionId(), proposal.getId()),
                    "a proposal whose target was written after it was recorded must refuse rather "
                            + "than overwrite the values the member never saw");
        } finally {
            clearAuthentication();
        }
        assertEquals(discovery.getId(), stageOf(renewal.getId()),
                "the refused approval must leave the deal exactly where the edit left it");
    }
}
