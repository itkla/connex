package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
 */
class AiAssistantScriptedTrajectoryActionSurfaceTest extends AbstractScriptedTrajectoryTest {

    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

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
