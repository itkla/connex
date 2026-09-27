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
import tools.jackson.databind.JsonNode;

/**
 * Goldens 9-15: the server-owned controls a fixture cannot fake, each run as a whole turn.
 *
 * <p>Every golden here is judged by one question: would it go red if the production guard it names
 * were deleted? So none of them asserts anything a script authored. They assert a terminal reason
 * only a server-side refusal produces, a durable row a guard refused to write, or — where the
 * refusal happens above the provider — that the request journal proves nothing left at all.
 *
 * <p>The special-care invariant is pinned twice, once buffered and once streamed, because the
 * screen runs in two places and a single golden would leave whichever channel it skipped pinned by
 * nothing.
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

    /**
     * The escape the no-progress guard exists for: a model that keeps repeating one call is handed
     * a closing step, and a model that honours it still turns the evidence it gathered into an
     * answer instead of losing the turn.
     */
    @Test
    void aModelThatStopsMakingProgressStillAnswersThroughTheClosingStep() {
        person("Silas Thornbury", "silas.thornbury@example.invalid", null);

        Trajectory trajectory = run(
                "connex_script_closing_answer", "look this contact up");

        assertEquals("resolved", trajectory.status(), trajectory.terminalReason());
        assertEquals(List.of("search_records"), trajectory.toolNames(),
                "a replayed call is answered from the turn's own cache, so it never becomes a "
                        + "second durable tool call: " + trajectory.toolNames());
        assertTrue(trajectory.answer().contains("repeating the same search gave me nothing new"),
                "the closing step's answer is what the member receives: " + trajectory.answer());
    }

    /**
     * The fallback behind that escape: a model that ignores the closing directive and asks for the
     * same tool again has produced nothing to deliver, so the turn fails on the guard's own reason
     * rather than running the call or inventing an answer.
     */
    @Test
    void aModelThatIgnoresTheClosingDirectiveFailsOnTheNoProgressGuard() {
        person("Silas Thornbury", "silas.thornbury@example.invalid", null);

        Trajectory trajectory = run(
                "connex_script_no_progress", "look this contact up");

        assertEquals("failed", trajectory.status());
        assertEquals("no_progress", trajectory.terminalReason());
        assertEquals(List.of("search_records"), trajectory.toolNames(),
                "a replayed call is answered from the turn's own cache, so it never becomes a "
                        + "second durable tool call: " + trajectory.toolNames());
        assertEquals(List.of(), trajectory.answers(),
                "a closing step that still asks for a tool has produced no answer to deliver");
    }

    /**
     * A streamed answer reaches the transcript in the domain its requester read it in.
     *
     * <p>The fixture answers with a masking placeholder, because the streamed path demasks every
     * batch itself and then compares those batches against the settled answer. Take that
     * per-batch demasking away and the two domains disagree, the comparison refuses, and the turn
     * ends {@code malformed_output} instead of resolving — which is why settling is the
     * assertion here rather than anything read back off the row.
     *
     * <p><b>Scoped to what a native streamed fixture can actually reach.</b> The terminal-text
     * projector confirms a {@code NATIVE_FINAL} answer only once the whole final object parses,
     * so however many fragments the provider writes, the batcher is handed the answer once. Delta
     * ordering and the projector's half-placeholder withholding therefore have no observable
     * effect on this path and are not claimed here; they stay owned by their own unit tests.
     *
     * <p><b>Two further deliberate omissions.</b> Reading {@code partial_content} back and
     * comparing it with the answer would prove nothing — {@code resolve} rewrites that column
     * with the settled answer for every streamed turn, so the comparison restates the rewrite.
     * And the requester-only channels the plan names for this golden are unreachable: narration
     * is the text a provider returns beside a native tool call, a scripted tool-call emission
     * carries no text, and giving it one is production code this test-only slice must not add.
     * Those channels stay owned by {@code AiChatTranscriptProjectionTest}.
     */
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
                        + "batch comparison this golden is about was never exercised");
        assertEquals(List.of("search_records", "get_record"), trajectory.toolNames());
        assertTrue(trajectory.answer().contains(contact.getName()),
                "the streamed placeholder resolves to the real value in both domains, or the "
                        + "batches and the settled answer would have disagreed: "
                        + trajectory.answer());
        assertFalse(trajectory.answer().contains("{{P1}}"),
                "no request-local placeholder may survive into the streamed transcript: "
                        + trajectory.answer());
        assertTrue(trajectory.answer().contains("](person:" + contact.getId() + ")"),
                "the settled answer carries a durable record link: " + trajectory.answer());
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

        JsonNode structured = structuredAnswer(trajectory);
        assertTrue(structured.get("citations").isEmpty(),
                "an excluded answer keeps no citation chip: a resolved chip names the kind and id "
                        + "of the very record the withheld sentence was about: " + structured);
        assertTrue(structured.get("suggestions").isEmpty(),
                "the follow-up suggestions are model prose the screen never sees, so they are "
                        + "dropped with the answer rather than published beside it: " + structured);
        assertEquals(UNTITLED_SESSION, sessionTitle(trajectory.sessionId()),
                "the model-authored session title is unscreened free text, so an excluded answer "
                        + "must not rename the session with it");
    }

    /**
     * The same exclusion on the channel a member reads live.
     *
     * <p>Kept separate from the buffered golden rather than replacing it: the two run through
     * different code. A buffered turn screens the text it is about to persist; a streamed turn
     * screens the running projection and screens again as the stream settles, and neither screen
     * is reached on the other's path. The streamed pair is pinned together — either one alone
     * still excludes this answer — so this golden turns red when the streamed path stops
     * screening, not when one of the two moves.
     */
    @Test
    void aStreamedAnswerCarryingSpecialCareContentIsExcludedFromBothChannels() {
        person("Delphine Hollingsworth", "delphine.hollingsworth@example.invalid", null);
        useCapabilityClass("scripted-native-stream");

        Trajectory trajectory = run(
                "connex_script_medical_stream", "check this contact for me");

        AiChatTurn settled = turnRow(trajectory);
        assertEquals("resolved", trajectory.status(), trajectory.terminalReason());
        assertTrue(settled.isStreamed(),
                "the streamed exclusion only exists on a streamed turn");
        assertEquals(MaskingEngine.OMITTED_BY_POLICY, trajectory.answer(),
                "the durable answer a second reader loads carries nothing of the screened text");
        assertEquals(MaskingEngine.OMITTED_BY_POLICY, settled.getPartialContent(),
                "settlement overwrites the durable partial with the settled answer, so this "
                        + "cannot show what the requester read live; it pins that no streamed "
                        + "prefix of the screened sentence survives in the durable row a later "
                        + "reader or a resumed stream would load");
    }

    /**
     * A restriction that lands between two model steps refuses the next egress.
     *
     * <p>Scoped deliberately. This pins the fence, its dispatch accounting and its audit row; it
     * does <em>not</em> pin the durable-partial purge, which only has something to purge on a
     * streamed turn whose partial is already durable. The epoch would have to advance between the
     * stream's last batch and the turn's terminal write, and nothing this harness can reach runs
     * there — the step hook fires before the emission, so the very step it arms is the step the
     * fence refuses. Reaching it would need a post-emission hook, which is production code this
     * test-only slice must not add; the purge is covered at the service layer by
     * {@code AiChatTurnPersistenceServiceTest}, and closing the end-to-end gap is a tracked
     * residual rather than something this class silently claims.
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
        assertEquals(1, auditRows("ai.llm.call", "blocked", "restriction_epoch"),
                "a refused egress leaves one durable blocked row naming the epoch; the terminal "
                        + "reason is derived from a re-read of the epoch, so without this the "
                        + "audit trail for every refused send could disappear and the turn would "
                        + "still be named restrictions_changed");
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
        Deal renewal = deal("Halyard Renewal", pipeline, discovery, customer);

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
            ConflictException refusal = assertThrows(ConflictException.class,
                    () -> writeToolService().approve(trajectory.sessionId(), proposal.getId()),
                    "a proposal whose target was written after it was recorded must refuse rather "
                            + "than overwrite the values the member never saw");
            assertEquals("Assistant proposal target changed", refusal.getMessage(),
                    "approval refuses for several reasons and only one of them is freshness; "
                            + "pinning the message is what keeps this golden from passing on a "
                            + "permission or restriction refusal instead");
        } finally {
            clearAuthentication();
        }
        assertEquals(discovery.getId(), stageOf(renewal.getId()),
                "the refused approval must leave the deal exactly where the edit left it");
    }
}
