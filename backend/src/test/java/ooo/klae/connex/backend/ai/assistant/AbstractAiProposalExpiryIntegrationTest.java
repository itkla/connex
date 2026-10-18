package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.AiChatToolCall;
import ooo.klae.connex.backend.beans.AiChatTurn;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.CustomFieldDefinition;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DocumentTemplate;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.recordcreation.LocalizedTextDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationDefaultSpecDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateCreateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateDefinitionDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateFieldDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateGroupDto;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.DocumentTemplateMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.recordcreation.RecordCreationDefaultKind;
import ooo.klae.connex.backend.recordcreation.RecordCreationRecordType;
import ooo.klae.connex.backend.services.CustomFieldDefinitionService;
import ooo.klae.connex.backend.services.RecordCreationTemplateService;
import ooo.klae.connex.backend.tenant.TenantContext;
import tools.jackson.databind.ObjectMapper;

/** Committed fixtures shared by real HTTP and observed MySQL expiry boundary tests. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(AbstractAiProposalExpiryIntegrationTest.ExpiryClockConfiguration.class)
abstract class AbstractAiProposalExpiryIntegrationTest {
    static final String PASSWORD = "Expiry-Fixture-Pw1!";
    @Autowired AiAssistantWriteToolService writes;
    @Autowired AiAssistantToolCallReadService reads;
    @Autowired AiAssistantWriteToolRegistry registry;
    @Autowired AiRestrictionEpoch restrictionEpoch;
    @Autowired AiChatMapper chats;
    @Autowired CompanyMapper companies;
    @Autowired PersonMapper people;
    @Autowired DealMapper deals;
    @Autowired PipelineMapper pipelines;
    @Autowired TaskMapper tasks;
    @Autowired TagMapper tags;
    @Autowired DocumentTemplateMapper documents;
    @Autowired OrganizationMapper organizations;
    @Autowired WorkspaceMapper workspaces;
    @Autowired UserMapper users;
    @Autowired RecordCreationTemplateService templates;
    @Autowired CustomFieldDefinitionService customFields;
    @Autowired TenantContext tenantContext;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ExpiryClock clock;
    AtomicReference<Instant> now;
    Organization organization;
    Workspace workspace;
    User actor;
    User other;
    Company company;
    Person person;
    Deal deal;
    Pipeline pipeline;
    Stage firstStage;
    Stage nextStage;
    Task task;
    Task sibling;
    Tag tag;
    Tag existingTag;
    DocumentTemplate document;
    Instant originalNow;
    Instant createdAt;
    Instant deadline;
    final Map<String, Integer> templateIds = new LinkedHashMap<>();
    final Map<String, Integer> customFieldIds = new LinkedHashMap<>();

    @BeforeEach
    void seedExpiryFixture() {
        originalNow = Instant.now().truncatedTo(ChronoUnit.MICROS);
        now = clock.current;
        now.set(originalNow);
        createdAt = originalNow.minus(71, ChronoUnit.HOURS);
        deadline = createdAt.plus(72, ChronoUnit.HOURS);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        organization = new Organization();
        organization.setName("Expiry organization " + suffix);
        organization.setSlug("expiry-org-" + suffix);
        organizations.insert(organization);
        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Expiry workspace " + suffix);
        workspace.setSlug("expiry-" + suffix);
        workspaces.insert(workspace);
        actor = member("expiry-actor-" + suffix, "Expiry Actor");
        other = member("expiry-other-" + suffix, "Expiry Owner");
        authenticate(actor);
        company = new Company();
        company.setWorkspaceId(workspace.getId());
        company.setName("Anchor " + suffix);
        company.setOwnerId(actor.getId());
        companies.insert(company);
        person = new Person();
        person.setWorkspaceId(workspace.getId());
        person.setName("Contact " + suffix);
        person.setOwnerId(actor.getId());
        person.setCompany(company);
        people.insert(person);
        pipeline = new Pipeline();
        pipeline.setWorkspaceId(workspace.getId());
        pipeline.setName("Expiry pipeline");
        pipelines.insertPipeline(pipeline);
        firstStage = stage("Discovery", 0);
        nextStage = stage("Negotiation", 1);
        deal = new Deal();
        deal.setWorkspaceId(workspace.getId());
        deal.setName("Opportunity " + suffix);
        deal.setOwnerId(actor.getId());
        deal.setCompanyId(company.getId());
        deal.setPipelineId(pipeline.getId());
        deal.setStageId(firstStage.getId());
        deal.setValue(new BigDecimal("1000.00"));
        deal.setCurrency("USD");
        deals.insert(deal);
        task = task("First assigned task", 0);
        sibling = task("Last board task", 1);
        tag = tag("Review tag");
        existingTag = tag("Existing tag");
        document = new DocumentTemplate();
        document.setWorkspaceId(workspace.getId());
        document.setName("Quote");
        document.setType("quote");
        document.setLocale("en");
        document.setTitle("Quote for {{deal.name}}");
        documents.insert(document);
        jdbc.update("INSERT INTO person_lifecycle_pass (workspace_id, person_id, entered_at, owner_id) VALUES (?, ?, ?, ?)",
                workspace.getId(), person.getId(), dbTime(createdAt.minusSeconds(3600)), actor.getId());
        jdbc.update("INSERT INTO deal_collaborator (workspace_id, deal_id, user_id) VALUES (?, ?, ?)", workspace.getId(), deal.getId(), other.getId());
        jdbc.update("INSERT INTO report_definition (workspace_id, name, cadence, config_json) VALUES (?, 'Existing report', 'monthly', '{}')",
                workspace.getId());
        for (String kind : List.of("person", "company", "deal")) {
            jdbc.update("UPDATE " + kind + " SET updated_at = ? WHERE workspace_id = ?",
                    dbTime(createdAt.minusSeconds(3600)), workspace.getId());
            jdbc.update("INSERT INTO " + kind + "_tag (" + kind + "_id, tag_id) VALUES (?, ?)",
                    targetId(kind), existingTag.getId());
        }
    }

    User member(String username, String displayName) {
        User user = new User();
        user.setUsername(username);
        user.setDisplayName(displayName);
        user.setEmail(username + "@example.com");
        user.setTimezone("UTC");
        user.setPasswordHash(passwords.encode(PASSWORD));
        users.insert(user);
        workspaces.addMember(workspace.getId(), user.getId(), "owner");
        return user;
    }

    private Stage stage(String name, int position) {
        Stage stage = new Stage();
        stage.setWorkspaceId(workspace.getId());
        stage.setPipeline(pipeline);
        stage.setName(name);
        stage.setPosition(position);
        pipelines.insertStage(stage);
        return stage;
    }

    private Task task(String description, int position) {
        Task value = new Task();
        value.setWorkspaceId(workspace.getId());
        value.setDescription(description);
        value.setAssignedTo(actor);
        value.setStatus("todo");
        value.setPosition(position);
        tasks.insert(value);
        return value;
    }

    private Tag tag(String name) {
        Tag value = new Tag();
        value.setWorkspaceId(workspace.getId());
        value.setName(name);
        tags.insert(value);
        return value;
    }

    void createGuidedTemplate(String kind) {
        CustomFieldDefinition field = new CustomFieldDefinition();
        field.setEntityType(kind);
        field.setFieldKey("expiry_marker");
        field.setLabel("Expiry marker");
        field.setFieldType("text");
        field = customFields.create(field, null);
        customFieldIds.put(kind, field.getId());
        List<RecordCreationTemplateFieldDto> fields = new ArrayList<>();
        fields.add(new RecordCreationTemplateFieldDto("name", true, null, null, null));
        fields.add(new RecordCreationTemplateFieldDto("custom:" + field.getId(), false, null, null,
                new RecordCreationDefaultSpecDto(RecordCreationDefaultKind.literal_string,
                        "Reviewed", null, null, null, null, null)));
        fields.add(new RecordCreationTemplateFieldDto("tags", false, null, null,
                new RecordCreationDefaultSpecDto(RecordCreationDefaultKind.literal_references,
                        null, null, null, null, null, List.of(tag.getId()))));
        if ("person".equals(kind)) {
            fields.add(new RecordCreationTemplateFieldDto("leadSource", false, null, null,
                    new RecordCreationDefaultSpecDto(RecordCreationDefaultKind.literal_string,
                            "REFERRAL", null, null, null, null, null)));
            fields.add(new RecordCreationTemplateFieldDto("referrerPerson", false, null, null,
                    new RecordCreationDefaultSpecDto(RecordCreationDefaultKind.literal_reference,
                            null, null, null, null, person.getId(), null)));
        }
        Integer revision = jdbc.queryForObject(
                "SELECT revision FROM record_creation_template_set WHERE workspace_id = ? AND record_type = ?",
                Integer.class, workspace.getId(), kind);
        assertNotNull(revision);
        var template = templates.create(new RecordCreationTemplateCreateRequestDto(
                RecordCreationRecordType.valueOf(kind), new LocalizedTextDto("Reviewed", "確認済み"), null,
                new RecordCreationTemplateDefinitionDto(1, List.of(new RecordCreationTemplateGroupDto(
                        "basics", new LocalizedTextDto("Basics", "基本情報"), null, fields))), true, revision));
        templateIds.put(kind, Integer.parseInt(template.id().substring("workspace:".length())));
    }

    Proposal proposal(String tool, String kind) {
        return proposal(tool, kind, Map.of());
    }

    Proposal proposal(String tool, String kind, Map<String, Object> recordFields) {
        AiChatSession session = new AiChatSession();
        session.setWorkspaceId(workspace.getId());
        session.setCreatedByUserId(actor.getId());
        session.setTitle("Expiry review");
        session.setVisibility("private");
        session.setStatus("active");
        chats.insertSession(session);
        return proposal(tool, kind, session.getId(), 1, recordFields);
    }

    Proposal proposal(String tool, String kind, int sessionId, int sequence) {
        return proposal(tool, kind, sessionId, sequence, Map.of());
    }

    private Proposal proposal(String tool, String kind, int sessionId, int sequence,
            Map<String, Object> recordFields) {
        AiChatResourceRegistry resources = new AiChatResourceRegistry();
        Map<String, Object> arguments = new LinkedHashMap<>();
        if (!"workspace".equals(kind)) {
            arguments.put("handle", resources.register(kind, targetId(kind)));
        }
        switch (tool) {
            case "assign_owner" -> arguments.put("owner", other.getDisplayName());
            case "add_tag" -> arguments.put("tag", tag.getName());
            case "remove_tag" -> arguments.put("tag", existingTag.getName());
            case "reschedule_task" -> arguments.put("due_date", "2030-01-02");
            case "set_response_due" -> arguments.put("due_in_hours", 24);
            case "update_record_fields" -> {
                if (!recordFields.isEmpty()) {
                    arguments.putAll(recordFields);
                } else {
                    switch (kind) {
                        case "person" -> arguments.put("title", "Director");
                        case "company" -> arguments.put("industry", "Manufacturing");
                        case "deal" -> {
                            arguments.put("value", "2000.00");
                            arguments.put("expected_close_date", "2030-01-02");
                        }
                        default -> throw new IllegalArgumentException(kind);
                    }
                }
            }
            case "draft_document" -> arguments.put("template", document.getName());
            case "change_deal_stage" -> arguments.put("stage", nextStage.getName());
            case "create_activity" -> {
                arguments.put("type", "call");
                arguments.put("subject", "Follow up");
                arguments.put("start", originalNow.toString());
            }
            case "create_task" -> arguments.put("description", "Follow up with customer");
            case "create_note" -> arguments.put("content", "Reviewed context");
            case "create_person", "create_company" -> arguments.put("name", "New " + UUID.randomUUID());
            case "create_deal" -> {
                arguments.put("name", "New " + UUID.randomUUID());
                arguments.put("stage", nextStage.getName());
                arguments.put("value", "500.00");
                arguments.put("currency", "USD");
            }
            case "create_report" -> {
                arguments.put("name", "Reviewed report");
                arguments.put("template", "pipeline-health");
            }
            case "complete_task" -> { }
            default -> throw new IllegalArgumentException(tool);
        }
        AiAssistantPreparedWrite write = writes.prepare(tool, json.valueToTree(arguments), resources,
                restrictionEpoch.current(workspace.getId()));
        AiChatMessage message = new AiChatMessage();
        message.setWorkspaceId(workspace.getId());
        message.setSessionId(sessionId);
        message.setSeq(sequence);
        message.setAuthorKind("user");
        message.setAuthorUserId(actor.getId());
        message.setContent("Please review");
        chats.insertMessage(message);
        AiChatTurn turn = new AiChatTurn();
        turn.setWorkspaceId(workspace.getId());
        turn.setSessionId(sessionId);
        turn.setRequestedByUserId(actor.getId());
        turn.setStatus("running");
        chats.insertTurn(turn);
        AiChatToolCall call = new AiChatToolCall();
        call.setWorkspaceId(workspace.getId());
        call.setMessageId(message.getId());
        call.setToolName(tool);
        call.setServerProposal(true);
        call.setStatus("proposed");
        call.setArgumentsJson(write.argumentsJson());
        call.setIdempotencyKey("turn-" + turn.getId() + "-step-1");
        chats.insertToolCall(call);
        jdbc.update("UPDATE ai_chat_tool_call SET created_at = ?, updated_at = ? WHERE workspace_id = ? AND id = ?",
                dbTime(createdAt), dbTime(createdAt), workspace.getId(), call.getId());
        return new Proposal(sessionId, call.getId(), new AiChatQueuedTurn(workspace.getId(), actor.getId(),
                sessionId, turn.getId(), message.getId(), sequence, restrictionEpoch.current(workspace.getId()),
                true, List.of(), List.of()), write.tier() == AiAssistantToolCatalog.ToolTier.AUTO);
    }

    void execute(Proposal proposal) {
        if (proposal.auto()) {
            writes.executeAuto(proposal.turn(), proposal.id(), ignored -> { });
        } else {
            writes.approve(proposal.sessionId(), proposal.id());
        }
    }

    int targetId(String kind) {
        return switch (kind) {
            case "person" -> person.getId();
            case "company" -> company.getId();
            case "deal" -> deal.getId();
            case "task" -> task.getId();
            case "workspace" -> workspace.getId();
            default -> throw new IllegalArgumentException(kind);
        };
    }

    Map<String, List<Map<String, Object>>> domainSnapshot() {
        Map<String, List<Map<String, Object>>> snapshot = new LinkedHashMap<>();
        for (String table : List.of("person", "company", "deal", "task", "note", "activity",
                "report_definition", "deal_document", "person_lifecycle_pass", "custom_field_value")) {
            snapshot.put(table, jdbc.queryForList("SELECT * FROM " + table + " WHERE workspace_id = ? ORDER BY id",
                    workspace.getId()));
        }
        for (String kind : List.of("person", "company", "deal")) {
            snapshot.put(kind + "_tag", jdbc.queryForList("SELECT association.* FROM " + kind + "_tag association"
                    + " JOIN " + kind + " target ON target.id = association." + kind + "_id"
                    + " WHERE target.workspace_id = ? ORDER BY association." + kind + "_id, association.tag_id",
                    workspace.getId()));
        }
        snapshot.put("entity_reference", jdbc.queryForList(
                "SELECT * FROM entity_reference WHERE workspace_id = ? ORDER BY source_type, source_id, ref_type, ref_id",
                workspace.getId()));
        snapshot.put("deal_collaborator", jdbc.queryForList(
                "SELECT c.* FROM deal_collaborator c JOIN deal d ON d.id = c.deal_id WHERE d.workspace_id = ? ORDER BY c.deal_id, c.user_id",
                workspace.getId()));
        snapshot.put("audit_log", jdbc.queryForList("SELECT id, action, entity_type, entity_id FROM audit_log WHERE workspace_id = ? AND outcome = 'success' ORDER BY id",
                workspace.getId()));
        return snapshot;
    }

    void assertPending(Proposal proposal) {
        AiChatToolCall call = chats.getToolCallBySession(workspace.getId(), proposal.sessionId(), proposal.id());
        assertNotNull(call);
        assertEquals("proposed", call.getStatus());
        assertEquals(null, call.getResultJson());
        assertEquals(null, call.getExecutedAt());
    }

    void authenticate(User user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        tenantContext.set(workspace.getId(), organization.getId(), user.getId(), "owner", null);
    }

    void clearAuthentication() {
        SecurityContextHolder.clearContext();
        tenantContext.clear();
    }

    static LocalDateTime dbTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    @AfterEach
    void removeExpiryFixture() {
        clearAuthentication();
        if (workspace == null) {
            return;
        }
        jdbc.update("UPDATE record_creation_template SET current_version_id = NULL WHERE workspace_id = ?", workspace.getId());
        jdbc.update("UPDATE record_creation_template_set SET default_template_id = NULL WHERE workspace_id = ?", workspace.getId());
        for (String table : List.of("ai_run_lease", "ai_chat_tool_call", "ai_chat_turn", "ai_chat_message",
                "ai_chat_session_participant", "ai_chat_session", "ai_workspace_governance", "deal_document",
                "document_template", "report_definition", "record_creation_template_version",
                "record_creation_template", "record_creation_template_set", "custom_field_value",
                "custom_field_definition", "entity_reference", "activity", "note", "task", "task_board_lock",
                "deal", "stage", "pipeline", "person", "company", "tag", "workspace_member")) {
            jdbc.update("DELETE FROM " + table + " WHERE workspace_id = ?", workspace.getId());
        }
        jdbc.update("DELETE FROM workspace_role_permission WHERE workspace_role_id IN (SELECT id FROM workspace_role WHERE workspace_id = ?)", workspace.getId());
        jdbc.update("DELETE FROM workspace_role WHERE workspace_id = ?", workspace.getId());
        jdbc.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        if (actor != null) {
            jdbc.update("DELETE FROM app_user WHERE id = ?", actor.getId());
        }
        if (other != null) {
            jdbc.update("DELETE FROM app_user WHERE id = ?", other.getId());
        }
        jdbc.update("DELETE FROM organization WHERE id = ?", organization.getId());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ExpiryClockConfiguration {
        @Bean
        @Primary
        ExpiryClock expiryClock() {
            return new ExpiryClock(new AtomicReference<>(Instant.now()), ZoneOffset.UTC);
        }
    }

    /** A live clock whose instant changes only after the test observes the desired boundary. */
    static final class ExpiryClock extends Clock {
        private final AtomicReference<Instant> current;
        private final ZoneId zone;

        private ExpiryClock(AtomicReference<Instant> current, ZoneId zone) {
            this.current = current;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new ExpiryClock(current, java.util.Objects.requireNonNull(zone));
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }

    record Proposal(int sessionId, int id, AiChatQueuedTurn turn, boolean auto) {
    }
}
