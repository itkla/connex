package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.ai.provider.scripted.ScriptedAiProviderProfile;
import ooo.klae.connex.backend.ai.provider.scripted.ScriptedAiRequestJournal;
import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.AiChatToolCall;
import ooo.klae.connex.backend.beans.AiChatTurn;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.AiChatTurnAcceptedDto;
import ooo.klae.connex.backend.dto.AiChatTurnCreateRequest;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantContext;

/**
 * The one scripted-provider Spring context, and the fixtures every trajectory golden runs against.
 *
 * <p>Nothing below the agent loop is mocked. A turn starts through {@link AiAssistantTurnService},
 * runs on the real {@code AiGenerationService} worker pool, reaches the real
 * {@link AiChatAgentLoopService}, the real tool executor and the real write-tool service, and lands
 * in real MySQL. Only the provider is scripted, and only because the profile, the flag and the
 * fixture directory all say so.
 *
 * <p><b>Why this context is not transactional.</b> The loop runs on a worker thread that reloads
 * the initiating identity from committed rows, so a test-managed transaction would hide the
 * workspace, the member and the provider row from the very thread under test. Every fixture is
 * therefore committed and deleted explicitly, in one fresh organization per method.
 *
 * <p><b>Why the provider row is inserted directly.</b> Readiness is shape-only, so an
 * {@code https://scripted.invalid/v1} endpoint satisfies it without any DNS lookup. The controller
 * path is deliberately not used: its save path resolves the host for real and would refuse this
 * endpoint, and configuring a provider through it would need an organization administrator and a
 * recent-authentication window this harness has no reason to hold.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "connex.ai.enabled=true",
                "connex.ai.scripted-provider.enabled=true"
        })
@ActiveProfiles({"test", ScriptedAiProviderProfile.NAME})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
abstract class AbstractScriptedTrajectoryTest {

    /** Longest a scripted turn may take to settle before the harness calls it a failure. */
    private static final Duration TERMINAL_DEADLINE = Duration.ofSeconds(90);

    /** Delay between durable turn reads while a turn is still running. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    private static final List<String> TERMINAL_STATUSES =
            List.of("resolved", "failed", "timed_out");

    /** Capability class every slice-2 golden is authored against. */
    private static final String SCRIPTED_MODEL_ID = "scripted-native";

    @Autowired private AiAssistantTurnService turnService;
    @Autowired private AiAssistantWriteToolService writeToolService;
    @Autowired private ScriptedAiRequestJournal journal;
    @Autowired private AiChatMapper chatMapper;
    @Autowired private CompanyMapper companyMapper;
    @Autowired private DealMapper dealMapper;
    @Autowired private NoteMapper noteMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private PersonMapper personMapper;
    @Autowired private PipelineMapper pipelineMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private TenantContext tenantContext;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Organization organization;
    private Workspace workspace;
    private User member;

    /**
     * Points the loader at the fixture tree that travels with this source set.
     *
     * <p>Resolved from the classpath rather than from a relative path, because the loader reads a
     * directory from the filesystem and the Gradle task's working directory is not the only place
     * this class can run from.
     *
     * @param registry dynamic property registry for the scripted context
     */
    @DynamicPropertySource
    static void scriptedFixtureDirectory(DynamicPropertyRegistry registry) {
        registry.add(
                ScriptedAiProviderProfile.FIXTURE_DIR_PROPERTY,
                AbstractScriptedTrajectoryTest::fixtureDirectory);
    }

    private static String fixtureDirectory() {
        try {
            return Path.of(Objects.requireNonNull(
                            AbstractScriptedTrajectoryTest.class.getResource("/ai/scripted"),
                            "scripted trajectory fixtures are missing from the test classpath")
                    .toURI()).toString();
        } catch (java.net.URISyntaxException exception) {
            throw new IllegalStateException("Scripted fixture directory is unreadable", exception);
        }
    }

    @BeforeEach
    void prepareTenant() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        organization = new Organization();
        organization.setName("Scripted trajectory " + unique);
        organization.setSlug("scripted-trajectory-" + unique);
        organizationMapper.insert(organization);

        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Scripted trajectory " + unique);
        workspace.setSlug("scripted-trajectory-" + unique);
        workspaceMapper.insert(workspace);

        member = new User();
        member.setUsername("scripted-trajectory-" + unique);
        member.setDisplayName("Scripted Member " + unique);
        member.setEmail("scripted-trajectory-" + unique + "@example.com");
        member.setPasswordHash("hash-" + unique);
        member.setTimezone("UTC");
        userMapper.insert(member);
        workspaceMapper.addMember(workspace.getId(), member.getId(), "owner");

        jdbcTemplate.update("""
                INSERT INTO ai_provider_config (
                    org_id, provider, region, endpoint, api_version, deployment, project_id,
                    allow_internal_endpoint, model_id, credential_ref, no_training_attested, enabled)
                VALUES (?, 'openai_compatible', NULL, 'https://scripted.invalid/v1', NULL, NULL,
                    NULL, FALSE, ?, NULL, TRUE, TRUE)
                """, organization.getId(), SCRIPTED_MODEL_ID);
        journal.clear();
    }

    @AfterEach
    void cleanUpTenant() {
        clearAuthentication();
        journal.clear();
        if (workspace != null) {
            jdbcTemplate.update("DELETE FROM ai_chat_tool_call WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM ai_chat_turn WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM ai_chat_message WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM ai_chat_session_participant WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM ai_chat_session WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM ai_workspace_governance WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM task_board_lock WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM task WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM note_reference WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM note WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM deal WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM stage WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM pipeline WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM person WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM company WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        }
        if (member != null) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", member.getId());
        }
        if (organization != null) {
            jdbcTemplate.update(
                    "DELETE FROM ai_provider_config WHERE org_id = ?", organization.getId());
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organization.getId());
        }
    }

    /** @return the fresh workspace this method owns */
    final int workspaceId() {
        return workspace.getId();
    }

    /** @return the fresh organization this method owns */
    final int organizationId() {
        return organization.getId();
    }

    /** @return the single active member of the fresh workspace */
    final User member() {
        return member;
    }

    /** @return the bounded in-JVM record of what the provider was handed */
    final ScriptedAiRequestJournal journal() {
        return journal;
    }

    /** @return the write-tool service, so a golden can undo what its trajectory executed */
    final AiAssistantWriteToolService writeToolService() {
        return writeToolService;
    }

    /**
     * Runs one whole turn under a fresh session and waits for its durable terminal state.
     *
     * @param selector the script selector the member's own words carry
     * @param request the rest of the member's request
     * @return the settled turn
     */
    final Trajectory run(String selector, String request) {
        authenticate();
        try {
            int sessionId = session();
            AiChatTurnAcceptedDto accepted = turnService.start(
                    sessionId, new AiChatTurnCreateRequest(selector + " " + request, List.of()));
            AiChatTurn settled = awaitTerminal(sessionId, accepted.turnId());
            return new Trajectory(
                    sessionId,
                    accepted.turnId(),
                    settled.getStatus(),
                    settled.getTerminalReason(),
                    chatMapper.listToolCallsBySession(workspace.getId(), sessionId, false, 50),
                    chatMapper.listMessages(workspace.getId(), sessionId, 50, 0).stream()
                            .filter(message -> "assistant".equals(message.getAuthorKind()))
                            .toList());
        } finally {
            clearAuthentication();
        }
    }

    /**
     * Counts audit rows the settled turn produced for this workspace.
     *
     * @param action stable audit action key
     * @return how many rows the action wrote for this workspace
     */
    final int auditRows(String action) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE workspace_id = ? AND action = ?",
                Integer.class, workspace.getId(), action);
        return count == null ? 0 : count;
    }

    /**
     * Counts tenant tasks currently linked to one person.
     *
     * @param personId the seeded person
     * @return live task rows
     */
    final int tasksFor(int personId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task WHERE workspace_id = ? AND person_id = ?",
                Integer.class, workspace.getId(), personId);
        return count == null ? 0 : count;
    }

    /**
     * Reads a deal's current stage id straight from the row.
     *
     * @param dealId the seeded deal
     * @return the stage id the row carries now
     */
    final int stageOf(int dealId) {
        Integer stageId = jdbcTemplate.queryForObject(
                "SELECT stage_id FROM deal WHERE workspace_id = ? AND id = ?",
                Integer.class, workspace.getId(), dealId);
        return stageId == null ? 0 : stageId;
    }

    /** Installs the member's identity and tenant placement on the calling thread. */
    final void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(member, null, member.getAuthorities()));
        tenantContext.set(
                workspace.getId(), organization.getId(), member.getId(), "owner", null);
    }

    /** Clears the member's identity and tenant placement from the calling thread. */
    final void clearAuthentication() {
        SecurityContextHolder.clearContext();
        tenantContext.clear();
    }

    /**
     * Seeds one person whose display name is distinctive enough to assert masking against.
     *
     * <p>Backdated because a write proposal refuses a target written in its own second, which would
     * turn a write golden into a staleness test.
     *
     * @param name display name
     * @param email structured contact field, never part of a record tool result
     * @param phone structured contact field, never part of a record tool result
     * @return the committed person
     */
    final Person person(String name, String email, String phone) {
        Person person = new Person();
        person.setWorkspaceId(workspace.getId());
        person.setName(name);
        person.setEmail(email);
        person.setPhone(phone);
        personMapper.insert(person);
        backdate("person", person.getId());
        return person;
    }

    /**
     * Seeds one workspace-visible note on a person.
     *
     * @param person the note's target
     * @param title note title
     * @param content note body, which reaches the prompt as untrusted CRM data
     * @return the committed note
     */
    final Note note(Person person, String title, String content) {
        Note note = new Note();
        note.setWorkspaceId(workspace.getId());
        note.setContent(content);
        note.setTitle(title);
        note.setVisibility("workspace");
        note.setAuthor(member);
        note.setPerson(person);
        noteMapper.insert(note);
        return note;
    }

    /**
     * Seeds one company.
     *
     * @param name display name
     * @return the committed company
     */
    final Company company(String name) {
        Company company = new Company();
        company.setWorkspaceId(workspace.getId());
        company.setName(name);
        companyMapper.insert(company);
        backdate("company", company.getId());
        return company;
    }

    /**
     * Seeds one pipeline.
     *
     * @param name display name
     * @return the committed pipeline
     */
    final Pipeline pipeline(String name) {
        Pipeline pipeline = new Pipeline();
        pipeline.setWorkspaceId(workspace.getId());
        pipeline.setName(name);
        pipelineMapper.insertPipeline(pipeline);
        return pipeline;
    }

    /**
     * Seeds one stage inside a pipeline.
     *
     * @param pipeline owning pipeline
     * @param name display name
     * @param position ordinal position
     * @return the committed stage
     */
    final Stage stage(Pipeline pipeline, String name, int position) {
        Stage stage = new Stage();
        stage.setWorkspaceId(workspace.getId());
        stage.setPipeline(pipeline);
        stage.setName(name);
        stage.setPosition(position);
        pipelineMapper.insertStage(stage);
        return stage;
    }

    /**
     * Seeds one deal owned by the workspace member.
     *
     * @param name display name
     * @param pipeline owning pipeline
     * @param stage current stage
     * @param company linked company
     * @return the committed deal
     */
    final Deal deal(String name, Pipeline pipeline, Stage stage, Company company) {
        Deal deal = new Deal();
        deal.setWorkspaceId(workspace.getId());
        deal.setOwnerId(member.getId());
        deal.setName(name);
        deal.setValue(new BigDecimal("1000.00"));
        deal.setCurrency("USD");
        deal.setPipelineId(pipeline.getId());
        deal.setStageId(stage.getId());
        deal.setCompanyId(company.getId());
        dealMapper.insert(deal);
        backdate("deal", deal.getId());
        return deal;
    }

    private void backdate(String table, int id) {
        jdbcTemplate.update(
                "UPDATE " + table + " SET updated_at = updated_at - INTERVAL 5 SECOND WHERE id = ?",
                id);
    }

    private int session() {
        AiChatSession session = new AiChatSession();
        session.setWorkspaceId(workspace.getId());
        session.setCreatedByUserId(member.getId());
        session.setTitle("Scripted trajectory");
        session.setVisibility("private");
        session.setStatus("active");
        chatMapper.insertSession(session);
        return session.getId();
    }

    private AiChatTurn awaitTerminal(int sessionId, int turnId) {
        Instant deadline = Instant.now().plus(TERMINAL_DEADLINE);
        while (true) {
            AiChatTurn turn = chatMapper.getTurnById(workspace.getId(), sessionId, turnId);
            assertNotNull(turn, "the durable turn row disappeared while the trajectory ran");
            if (TERMINAL_STATUSES.contains(turn.getStatus())) {
                return turn;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException(String.format(
                        Locale.ROOT,
                        "scripted trajectory turn %d never settled; last status was %s",
                        turnId, turn.getStatus()));
            }
            sleep();
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Scripted trajectory wait was interrupted", exception);
        }
    }

    /**
     * One settled trajectory and the durable rows a golden asserts against.
     *
     * @param sessionId owning session
     * @param turnId durable turn identifier
     * @param status durable terminal status
     * @param terminalReason durable stable terminal reason, or null when resolved
     * @param toolCalls every durable tool call of the session, in loop order
     * @param answers assistant messages this turn produced
     */
    record Trajectory(
            int sessionId,
            int turnId,
            String status,
            String terminalReason,
            List<AiChatToolCall> toolCalls,
            List<AiChatMessage> answers) {

        /** @return the durable tool names in the order the loop executed them */
        List<String> toolNames() {
            return toolCalls.stream().map(AiChatToolCall::getToolName).toList();
        }

        /** @return the single assistant answer this turn delivered */
        String answer() {
            if (answers.size() != 1) {
                throw new IllegalStateException(
                        "expected exactly one assistant answer but found " + answers.size());
            }
            return answers.getFirst().getContent();
        }
    }
}
