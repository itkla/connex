package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.AiChatToolCall;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Goldens for the action surface: whole scripted trajectories whose assertions are about controls
 * only the write framework owns.
 *
 * <p>The two goldens here pin issue 1865. A confirm-tier proposal names its stage or its owner by
 * the name the model wrote, and the approval resolves that name again. Each golden changes, between
 * the proposal and the approval, only which row that name resolves to — never the target record
 * itself, which stays backdated, so the freshness refusal cannot be what refuses — and then
 * approves. Without the resolution and principals pinned when the proposal was prepared, both
 * approvals would succeed and write a row the member never reviewed.
 *
 * <p>The replay goldens pin that the pins never break a step's idempotency. A step hook stores the
 * write step's proposal exactly as the loop would, as a worker that stopped right after storing it
 * would leave it, and then moves which row its name resolves to before the loop reaches that step.
 * The stored proposal must win: it is replayed with its original pins and no second row, where
 * resolving the name again would disagree with the stored row and fail the turn on its key.
 */
class AiAssistantScriptedTrajectoryActionSurfaceTest extends AbstractScriptedTrajectoryTest {

    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    /** The tool calls the drift scripts complete before their write: a search and a load. */
    private static final int CALLS_BEFORE_WRITE = 2;

    /** The durable step the drift scripts write at, one step per call before it. */
    private static final int WRITE_STEP = CALLS_BEFORE_WRITE + 1;

    private final List<Integer> extraMembers = new ArrayList<>();

    @AfterEach
    void removeExtraMembers() {
        for (Integer userId : extraMembers) {
            jdbcTemplate.update(
                    "DELETE FROM workspace_member WHERE workspace_id = ? AND user_id = ?",
                    workspaceId(), userId);
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
        }
        extraMembers.clear();
    }

    /**
     * One stage renamed away from the reviewed name and another renamed into it: the name now
     * resolves to a stage the card never named, and the approval refuses rather than moving the
     * deal there.
     */
    @Test
    void approvingAStageProposalWhoseNameNowNamesAnotherStageIsRefused() {
        Company customer = company("Marlowe Shipping");
        Pipeline pipeline = pipeline("Pinned pipeline");
        Stage discovery = stage(pipeline, "Discovery", 0);
        Stage negotiation = stage(pipeline, "Negotiation", 1);
        Stage closing = stage(pipeline, "Closing", 2);
        Deal expansion = deal("Marlowe Expansion", pipeline, discovery, customer);

        Trajectory trajectory = run(
                "connex_script_pinned_stage_drift", "move this one along if you can");

        assertEquals("resolved", trajectory.status(), trajectory.terminalReason());
        AiChatToolCall proposal = proposal(trajectory, "change_deal_stage");
        JsonNode stored = objectMapper.readTree(proposal.getArgumentsJson());
        assertEquals("stage", stored.path("resolution").path("field").asString());
        assertEquals(negotiation.getId(), stored.path("resolution").path("id").asInt(),
                "the proposal must pin the stage its card names");
        assertEquals(0, stored.path("principals").size());

        renameStage(negotiation, "Negotiation (retired)");
        renameStage(closing, "Negotiation");

        authenticate();
        try {
            ConflictException refusal = assertThrows(ConflictException.class,
                    () -> writeToolService().approve(trajectory.sessionId(), proposal.getId()),
                    "a name that now resolves to another stage must refuse rather than move the "
                            + "deal to a stage the member never reviewed");
            assertEquals("Assistant proposal target changed", refusal.getMessage());
        } finally {
            clearAuthentication();
        }
        assertEquals(discovery.getId(), stageOf(expansion.getId()),
                "the refused approval must leave the deal where it was");
        assertEquals("proposed", status(proposal));
    }

    /**
     * Issue 1865 exactly: the card reviewed one member as the owner, that member is offboarded, and
     * another active member takes the same display name. The name now resolves uniquely to an
     * active member and the company is unchanged, so only the pin can refuse.
     */
    @Test
    void approvingAnOwnerProposalWhoseMemberWasReplacedUnderTheSameNameIsRefused() {
        User reviewed = extraMember("Grace Hopper");
        User successor = extraMember("Gregory Hale");
        Company customer = company("Wexley Cartage");

        Trajectory trajectory = run(
                "connex_script_pinned_owner_drift", "hand this company to Grace");

        assertEquals("resolved", trajectory.status(), trajectory.terminalReason());
        AiChatToolCall proposal = proposal(trajectory, "assign_owner");
        JsonNode stored = objectMapper.readTree(proposal.getArgumentsJson());
        assertEquals(
                objectMapper.createArrayNode().add(reviewed.getId()), stored.path("principals"),
                "the proposal must pin the member its card names");
        assertTrue(stored.path("resolution").isMissingNode());

        assertEquals(1, workspaceMapper.removeMember(workspaceId(), reviewed.getId()));
        assertEquals(1, jdbcTemplate.update(
                "UPDATE app_user SET display_name = ? WHERE id = ?",
                "Grace Hopper", successor.getId()));

        authenticate();
        try {
            ConflictException refusal = assertThrows(ConflictException.class,
                    () -> writeToolService().approve(trajectory.sessionId(), proposal.getId()),
                    "a name that now resolves to another member must refuse rather than hand the "
                            + "company to someone the approver never saw");
            assertEquals("Assistant proposal target changed", refusal.getMessage());
        } finally {
            clearAuthentication();
        }
        assertNull(jdbcTemplate.queryForObject(
                        "SELECT owner_id FROM company WHERE workspace_id = ? AND id = ?",
                        Integer.class, workspaceId(), customer.getId()),
                "the refused approval must write no owner at all");
        assertEquals("proposed", status(proposal));
    }

    /**
     * The write step is reached again after its proposal was stored, and meanwhile the reviewed
     * stage was renamed away and another stage renamed into its name. Resolving the name again
     * would pin the other stage and refuse the step as a reused key.
     */
    @Test
    void aStoredStageProposalIsReplayedWithItsPinAfterItsNameMoved() {
        Company customer = company("Marlowe Shipping");
        Pipeline pipeline = pipeline("Pinned pipeline");
        Stage discovery = stage(pipeline, "Discovery", 0);
        Stage negotiation = stage(pipeline, "Negotiation", 1);
        Stage closing = stage(pipeline, "Closing", 2);
        Deal expansion = deal("Marlowe Expansion", pipeline, discovery, customer);
        AtomicInteger stored = new AtomicInteger();
        onScriptedStep((scriptId, completedToolCalls) -> {
            if (completedToolCalls == CALLS_BEFORE_WRITE && stored.get() == 0) {
                stored.set(storeWriteProposal(
                        WRITE_STEP, "change_deal_stage",
                        "{\"handle\":\"r1\",\"stage\":\"Negotiation\"}",
                        "deal", expansion.getId()));
                renameStage(negotiation, "Negotiation (retired)");
                renameStage(closing, "Negotiation");
            }
        });

        Trajectory trajectory = run(
                "connex_script_pinned_stage_drift", "move this one along if you can");

        JsonNode pinned = replayed(trajectory, "change_deal_stage", stored.get());
        assertEquals(negotiation.getId(), pinned.path("resolution").path("id").asInt(),
                "the replayed proposal must keep the stage its card was reviewed against");
        assertEquals(discovery.getId(), stageOf(expansion.getId()));
    }

    /**
     * Issue 1865's drift, between storing a proposal and reaching its step again: the reviewed
     * member is offboarded and another member takes the same name. Resolving the name again would
     * pin the other member and refuse the step as a reused key.
     */
    @Test
    void aStoredOwnerProposalIsReplayedWithItsPinAfterItsMemberWasReplaced() {
        User reviewed = extraMember("Grace Hopper");
        User successor = extraMember("Gregory Hale");
        Company customer = company("Wexley Cartage");
        AtomicInteger stored = new AtomicInteger();
        onScriptedStep((scriptId, completedToolCalls) -> {
            if (completedToolCalls == CALLS_BEFORE_WRITE && stored.get() == 0) {
                stored.set(storeOwnerProposal(customer, "Grace Hopper"));
                offboard(reviewed);
                assertEquals(1, jdbcTemplate.update(
                        "UPDATE app_user SET display_name = ? WHERE id = ?",
                        "Grace Hopper", successor.getId()));
            }
        });

        Trajectory trajectory = run(
                "connex_script_pinned_owner_drift", "hand this company to Grace");

        assertEquals(
                objectMapper.createArrayNode().add(reviewed.getId()),
                replayed(trajectory, "assign_owner", stored.get()).path("principals"),
                "the replayed proposal must keep the member its card was reviewed against");
    }

    /**
     * The reviewed member is offboarded between storing the proposal and reaching its step again,
     * so the name now resolves to nobody. Resolving it again would refuse the call and write the
     * refusal under the key the stored proposal already holds.
     */
    @Test
    void aStoredOwnerProposalIsReplayedRatherThanRefusedAfterItsMemberLeft() {
        User reviewed = extraMember("Grace Hopper");
        Company customer = company("Wexley Cartage");
        AtomicInteger stored = new AtomicInteger();
        onScriptedStep((scriptId, completedToolCalls) -> {
            if (completedToolCalls == CALLS_BEFORE_WRITE && stored.get() == 0) {
                stored.set(storeOwnerProposal(customer, "Grace Hopper"));
                offboard(reviewed);
            }
        });

        Trajectory trajectory = run(
                "connex_script_pinned_owner_drift", "hand this company to Grace");

        assertEquals(
                objectMapper.createArrayNode().add(reviewed.getId()),
                replayed(trajectory, "assign_owner", stored.get()).path("principals"),
                "the replayed proposal must keep the member its card was reviewed against");
    }

    /**
     * Replay compares what the model asked for: a call whose request differs from the proposal
     * already holding its step's key is still refused as a reused key, and the stored proposal is
     * left exactly as it was.
     */
    @Test
    void aStepWhoseRequestDiffersFromItsStoredProposalIsStillRefused() {
        extraMember("Grace Hopper");
        User successor = extraMember("Gregory Hale");
        Company customer = company("Wexley Cartage");
        AtomicInteger stored = new AtomicInteger();
        onScriptedStep((scriptId, completedToolCalls) -> {
            if (completedToolCalls == CALLS_BEFORE_WRITE && stored.get() == 0) {
                stored.set(storeOwnerProposal(customer, "Gregory Hale"));
            }
        });

        Trajectory trajectory = run(
                "connex_script_pinned_owner_drift", "hand this company to Grace");

        assertEquals("failed", trajectory.status());
        assertEquals("internal_error", trajectory.terminalReason(),
                "a reused key is an invariant breach, not a refusal the model may correct");
        assertEquals(List.of("search_records", "find_tools", "assign_owner"),
                trajectory.toolNames(), "the refused step must write no second row");
        AiChatToolCall kept = proposal(trajectory, "assign_owner");
        assertEquals(stored.get(), kept.getId());
        JsonNode arguments = objectMapper.readTree(kept.getArgumentsJson());
        assertEquals("Gregory Hale", arguments.path("request").path("owner").asString());
        assertEquals(
                objectMapper.createArrayNode().add(successor.getId()),
                arguments.path("principals"));
    }

    private int storeOwnerProposal(Company company, String owner) {
        return storeWriteProposal(
                WRITE_STEP, "assign_owner",
                "{\"handle\":\"r1\",\"owner\":\"" + owner + "\"}",
                "company", company.getId());
    }

    private void offboard(User user) {
        assertEquals(1, workspaceMapper.removeMember(workspaceId(), user.getId()));
    }

    /**
     * Asserts that the write step replayed the proposal the hook stored and wrote no row of its
     * own, and reads that proposal's stored arguments.
     */
    private JsonNode replayed(Trajectory trajectory, String tool, int storedId) {
        assertEquals("resolved", trajectory.status(), trajectory.terminalReason());
        assertEquals(List.of("search_records", "find_tools", tool), trajectory.toolNames(),
                "the replayed step must write no second row");
        AiChatToolCall replayed = proposal(trajectory, tool);
        assertEquals(storedId, replayed.getId(),
                "the step must replay the proposal already stored under its key");
        assertEquals("turn-" + trajectory.turnId() + "-step-" + WRITE_STEP,
                replayed.getIdempotencyKey());
        return objectMapper.readTree(replayed.getArgumentsJson());
    }

    private AiChatToolCall proposal(Trajectory trajectory, String tool) {
        AiChatToolCall proposal = trajectory.toolCalls().stream()
                .filter(call -> tool.equals(call.getToolName()))
                .findFirst()
                .orElseThrow();
        assertEquals("proposed", proposal.getStatus(),
                "a confirm-tier write must wait for the member's decision");
        return proposal;
    }

    private String status(AiChatToolCall proposal) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM ai_chat_tool_call WHERE workspace_id = ? AND id = ?",
                String.class, workspaceId(), proposal.getId());
    }

    private void renameStage(Stage stage, String name) {
        assertEquals(1, jdbcTemplate.update(
                "UPDATE stage SET name = ? WHERE workspace_id = ? AND id = ?",
                name, workspaceId(), stage.getId()));
    }

    private User extraMember(String displayName) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("pinned-member-" + unique);
        user.setDisplayName(displayName);
        user.setEmail("pinned-member-" + unique + "@example.com");
        user.setPasswordHash("hash-" + unique);
        user.setTimezone("UTC");
        userMapper.insert(user);
        extraMembers.add(user.getId());
        workspaceMapper.addMember(workspaceId(), user.getId(), "member");
        return user;
    }
}
