package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.services.ScoringService;
import ooo.klae.connex.backend.dto.RelationshipTemperatureDto;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.EntityReference;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.DataSubjectDisclosureMapper;
import ooo.klae.connex.backend.mappers.EntityReferenceMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.ShareMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;
import ooo.klae.connex.backend.notifications.RealtimeNotificationPayload;
import ooo.klae.connex.backend.notifications.RealtimeRoutingIdentityResolver;
import ooo.klae.connex.backend.services.RuleTriggerPublisher;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.DataSubjectDisclosureReadTransaction;
import ooo.klae.connex.backend.services.NoteService;
import ooo.klae.connex.backend.tenant.TenantContext;

/** Exercises linked-record, note privacy/read, and reply-label boundaries with committed fixtures. */
@SpringBootTest
@Import(CoreRecordSecurityIntegrationTest.StatementProbeConfiguration.class)
class CoreRecordSecurityIntegrationTest {
    private static final String PASSWORD = "Core-record-boundary-Pw1!";

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private UserMapper userMapper;
    @MockitoSpyBean private PersonMapper personMapper;
    @Autowired private DealMapper dealMapper;
    @Autowired private CompanyMapper companyMapper;
    @Autowired private ScoringService scoringService;
    @Autowired private StatementProbe statementProbe;
    @Autowired private DataSubjectDisclosureReadTransaction disclosureReadTransaction;
    @Autowired private DataSubjectDisclosureMapper disclosureMapper;
    @Autowired private PipelineMapper pipelineMapper;
    @MockitoSpyBean private TaskMapper taskMapper;
    @Autowired private ActivityMapper activityMapper;
    @Autowired private ShareMapper shareMapper;
    @Autowired private EntityReferenceMapper entityReferenceMapper;
    @Autowired private AttachmentMapper attachmentMapper;
    @Autowired private NotificationMapper notificationMapper;
    @Autowired private RealtimeRoutingIdentityResolver routingIdentities;
    @Autowired private NoteService noteService;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private TenantContext tenantContext;
    @MockitoSpyBean private NoteMapper noteMapper;
    @MockitoBean private AuditService auditService;
    @MockitoBean private RuleTriggerPublisher ruleTriggers;
    @MockitoBean private NotificationChangePublisher notificationChanges;
    @MockitoBean private SimpMessagingTemplate messagingTemplate;

    private MockMvc mockMvc;
    private Workspace workspace;
    private User member;
    private MockHttpSession memberSession;

    @BeforeEach
    void setUp() throws Exception {
        clearContext();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        workspace = newWorkspace(newOrganization());
        member = newMember(workspace);
        memberSession = login(member);
    }

    @AfterEach
    void clearContext() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
        tenantContext.clear();
    }

    static Stream<Arguments> linkedWrites() {
        return Stream.of("tasks:person", "activities:person", "activities:deal")
            .flatMap(link -> Stream.of("POST", "PUT")
                .flatMap(method -> Stream.of(false, true)
                    .map(embedded -> Arguments.of(link, method, embedded))));
    }

    @Test
    void disclosureIncludesAllTwoHundredFiftyNotesWithOneBodyQueryPerPage() {
        Person subject = newPerson(workspace);
        Workspace sibling = newWorkspace(workspace.getOrgId());
        List<Integer> workspaceIds = List.of(workspace.getId(), sibling.getId());
        List<Integer> noteIds = new ArrayList<>();
        for (int index = 0; index < 250; index++) {
            Note note = newNote(index % 2 == 0 ? workspace : sibling, member, subject, null,
                index % 3 == 0 ? "private" : "workspace", "Disclosure \"内容\"\n" + index);
            noteIds.add(note.getId());
        }
        Workspace foreignWorkspace = newWorkspace(newOrganization());
        Note foreignNote = newNote(foreignWorkspace, member, subject, null, "workspace", "Foreign note");
        Note unrelatedNote = newNote(workspace, member, newPerson(workspace), null, "workspace", "Other subject");
        authenticate(member);
        statementProbe.reset();

        JsonNode notes = objectMapper.readTree(objectMapper.writeValueAsString(disclosureReadTransaction.assemble(
            workspace.getId(), subject.getId(), workspaceIds))).path("notes");

        assertTrue(notes.isArray());
        assertEquals(250, notes.size());
        for (int index = 0; index < 250; index++) {
            JsonNode note = notes.get(index);
            assertEquals(noteIds.get(index).intValue(), note.path("id").asInt());
            assertEquals("Disclosure \"内容\"\n" + index, note.path("content").asString());
            assertEquals(index % 3 == 0 ? "private" : "workspace", note.path("visibility").asString());
            assertEquals(index % 2 == 0 ? workspace.getId() : sibling.getId(), note.path("workspaceId").asInt());
        }
        String namespace = DataSubjectDisclosureMapper.class.getName() + ".";
        assertEquals(List.of(250), statementProbe.rowCounts.get(namespace + "findNoteIds"));
        assertEquals(List.of(100, 100, 50), statementProbe.rowCounts.get(namespace + "findNotePage"));
        assertEquals(List.of(false, false), statementProbe.previousDisclosurePageCached);

        var scopedPage = disclosureMapper.findNotePage(workspace.getId(), subject.getId(), workspaceIds,
            List.of(noteIds.getFirst(), foreignNote.getId(), unrelatedNote.getId()));
        assertEquals(List.of(noteIds.getFirst()), scopedPage.stream().map(note -> note.getId()).toList());
    }

    @ParameterizedTest
    @MethodSource("linkedWrites")
    void linkedWritesRefuseInvisibleAndMissingRecordsWithoutMutation(
            String link, String method, boolean embedded) throws Exception {
        String[] parts = link.split(":");
        String resource = parts[0];
        String field = parts[1];
        Workspace sibling = newWorkspace(workspace.getOrgId());
        Workspace foreign = newWorkspace(newOrganization());
        int siblingId = linkedId(field, sibling);
        int foreignId = linkedId(field, foreign);
        int targetId = "PUT".equals(method) ? createTarget(resource) : 0;
        String uri = "/api/" + resource + (targetId == 0 ? "" : "/" + targetId);
        int beforeCount = targetCount(resource);
        for (int deniedId : List.of(siblingId, foreignId, Integer.MAX_VALUE, 0, -1)) {
            mockMvc.perform(request(HttpMethod.valueOf(method), uri)
                    .session(memberSession).with(csrf().asHeader())
                    .header("X-Workspace-Id", workspace.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(writeBody(resource, field, deniedId, embedded))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("person".equals(field) ? "Contact not found" : "Deal not found"));
            assertEquals(beforeCount, targetCount(resource));
            if (targetId != 0) {
                assertUnchanged(resource, targetId);
            }
        }
        int allowedId;
        if ("person".equals(field)) {
            assertEquals(1, shareMapper.sharePerson(siblingId, sibling.getId(), workspace.getId(), member.getId(), false));
            allowedId = siblingId;
        } else {
            allowedId = linkedId(field, workspace);
        }
        MvcResult accepted = mockMvc.perform(request(HttpMethod.valueOf(method), uri)
                .session(memberSession).with(csrf().asHeader())
                .header("X-Workspace-Id", workspace.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(writeBody(resource, field, allowedId, embedded))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$." + field + "Id").value(allowedId))
            .andReturn();
        assertTrue(objectMapper.readTree(accepted.getResponse().getContentAsString()).path("id").asInt() > 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"authorId", "personId", "dealId"})
    void filteredNotesAreLimitedInSqlBeforeHydrationAndPreserveVisibility(String filter) throws Exception {
        Person person = newPerson(workspace);
        Deal deal = newDeal(workspace);
        User other = newMember(workspace);
        String body = "x".repeat(50_000);
        Note oldest = newNote(workspace, member, person, deal, "private", body);
        for (int index = 1; index < 105; index++) {
            newNote(workspace, member, person, deal, "private", body);
        }
        Note invisible = newNote(workspace, other, person, deal, "private", "Other private content");
        Workspace sibling = newWorkspace(workspace.getOrgId());
        Note foreign = newNote(sibling, member, person, deal, "workspace", "Foreign content");
        Integer personId = "personId".equals(filter) ? person.getId() : null;
        Integer dealId = "dealId".equals(filter) ? deal.getId() : null;
        Integer authorId = "authorId".equals(filter) ? member.getId() : null;
        int filterId = personId != null ? personId : dealId != null ? dealId : member.getId();
        MvcResult first = mockMvc.perform(get("/api/notes").session(memberSession)
                .header("X-Workspace-Id", workspace.getId()).param(filter, Integer.toString(filterId))
                .param("size", "10000"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(100)).andReturn();
        JsonNode items = objectMapper.readTree(first.getResponse().getContentAsString());
        for (JsonNode item : items) {
            assertEquals(500, item.path("content").asText().length());
            assertTrue(item.path("id").asInt() != invisible.getId());
            assertTrue(item.path("id").asInt() != foreign.getId());
        }
        verify(noteMapper).getVisibleNotesFilteredPage(workspace.getId(), member.getId(),
            personId, dealId, authorId, 100, 0);
        mockMvc.perform(get("/api/notes").session(memberSession)
                .header("X-Workspace-Id", workspace.getId()).param(filter, Integer.toString(filterId))
                .param("page", "2").param("size", "100"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(5));
        mockMvc.perform(get("/api/notes").session(memberSession)
                .header("X-Workspace-Id", workspace.getId()).param(filter, Integer.toString(filterId)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(25));
        mockMvc.perform(get("/api/notes").session(memberSession)
                .header("X-Workspace-Id", workspace.getId()).param(filter, Integer.toString(filterId))
                .param("page", "2147483647"))
            .andExpect(status().isBadRequest());
        String alias = switch (filter) {
            case "authorId" -> "/api/users/" + filterId + "/notes";
            case "personId" -> "/api/persons/" + filterId + "/notes";
            case "dealId" -> "/api/deals/" + filterId + "/notes";
            default -> throw new IllegalArgumentException("Unexpected note filter");
        };
        mockMvc.perform(get(alias).session(memberSession)
                .header("X-Workspace-Id", workspace.getId()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(25))
            .andExpect(jsonPath("$[0].content").value(body.substring(0, 500)));
        mockMvc.perform(get(alias).session(memberSession)
                .header("X-Workspace-Id", workspace.getId()).param("page", "2").param("size", "100"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(5))
            .andExpect(jsonPath("$[4].id").value(oldest.getId()));
        if ("authorId".equals(filter)) {
            mockMvc.perform(get(alias + "/page").session(memberSession)
                    .header("X-Workspace-Id", workspace.getId()).param("size", "10000"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(100))
                .andExpect(jsonPath("$.total").value(105));
            mockMvc.perform(get(alias + "/page").session(memberSession)
                    .header("X-Workspace-Id", workspace.getId()).param("page", "2").param("size", "100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.items[4].id").value(oldest.getId()))
                .andExpect(jsonPath("$.total").value(105));
            for (String route : List.of(alias, alias + "/page")) {
                mockMvc.perform(get(route).session(memberSession)
                        .header("X-Workspace-Id", workspace.getId()).param("page", "2147483647"))
                    .andExpect(status().isBadRequest());
            }
        }
        if ("personId".equals(filter)) {
            mockMvc.perform(get("/api/persons/{id}", person.getId()).session(memberSession)
                    .header("X-Workspace-Id", workspace.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.notes.length()").value(25))
                .andExpect(jsonPath("$.notes[0].content").value(body.substring(0, 500)));
        }
        assertEquals(100, sqlSessionTemplate.getMapper(NoteMapper.class).getVisibleNotesFilteredPage(
            workspace.getId(), member.getId(), personId, dealId, authorId, 100, 0).size());
        mockMvc.perform(get("/api/notes/{id}", items.get(0).path("id").asInt()).session(memberSession)
                .header("X-Workspace-Id", workspace.getId()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content").value(body));
    }

    @Test
    void coolingAndIdScoresUseBodyFreeAggregatesForTheEntireCorpus() throws Exception {
        Person cooling = newPerson(workspace);
        Company coolingCompany = newCompany(cooling);
        Person aged = newPerson(workspace);
        Company agedCompany = newCompany(aged);
        Person recent = newPerson(workspace);
        Company recentCompany = newCompany(recent);
        Deal deal = newDeal(workspace);
        deal.setCompanyId(coolingCompany.getId());
        dealMapper.update(deal);
        User other = newMember(workspace);
        String body = "x".repeat(50_000);
        for (int index = 0; index < 2000; index++) {
            newNote(workspace, other, cooling, deal, "private", body);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1);
        for (int index = 0; index < 200; index++) {
            newDatedNote(cooling, deal, body, now.minusDays(60));
            newDatedNote(aged, null, body, now.minusDays(180));
        }
        for (int index = 0; index < 105; index++) {
            newDatedNote(recent, null, body, now.minusDays(1));
        }
        Workspace foreign = newWorkspace(newOrganization());
        newNote(foreign, member, cooling, deal, "workspace", body);
        newDatedNote(cooling, deal, body, now.plusDays(30));

        for (String type : List.of("contacts", "companies")) {
            int id = "contacts".equals(type) ? cooling.getId() : coolingCompany.getId();
            statementProbe.reset();
            JsonNode coolingResult = assertTimeout(Duration.ofSeconds(15), () -> readJson(
                "/api/scoring/" + type + "/cooling?limit=1"));
            assertEquals(1, coolingResult.size());
            assertEquals(id, coolingResult.get(0).path("id").asInt());
            assertEquals("cooling", coolingResult.get(0).path("trend").asText());
            assertNoNoteBodyRead();
            assertTrue(statementProbe.ids.stream().anyMatch(statement -> statement.endsWith(".getRelationshipScoreAggregates")));

            List<Integer> ids = "contacts".equals(type)
                ? List.of(cooling.getId(), aged.getId(), recent.getId())
                : List.of(coolingCompany.getId(), agedCompany.getId(), recentCompany.getId());
            statementProbe.reset();
            JsonNode scores = assertTimeout(Duration.ofSeconds(15), () -> readJson(
                "/api/scoring/" + type + "?ids=" + ids.stream().map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining(","))));
            assertEquals(3, scores.size());
            assertEquals(71, scores.get(1).path("score").asInt());
            assertEquals("hot", scores.get(1).path("band").asText());
            assertEquals(105, scores.get(2).path("touchCount").asInt());
            assertNoNoteBodyRead();
            assertTrue(statementProbe.ids.stream().anyMatch(statement -> statement.endsWith(".getRelationshipScoreAggregatesByIds")));

            ScoringService.WorkspaceScores aggregate = scoringService.scoreWorkspace(workspace.getId());
            List<RelationshipTemperatureDto> temperatures = "contacts".equals(type) ? aggregate.contacts() : aggregate.companies();
            for (int index = 0; index < ids.size(); index++) {
                int subjectId = ids.get(index);
                JsonNode evidence = readJson("/api/scoring/" + type + "/" + subjectId + "/evidence");
                assertEquals(index == 2 ? 105 : 200, evidence.path("totals").path("sourceCounts").path("notes").asInt());
                JsonNode aggregateScore = objectMapper.valueToTree(temperatures.stream()
                    .filter(score -> score.getId() == subjectId).findFirst().orElseThrow());
                for (String field : List.of("score", "band", "trend", "touchCount", "daysSinceTouch", "daysUntilCold", "lastTouchAt")) {
                    assertEquals(scores.get(index).path(field), evidence.path("temperature").path(field), field);
                    assertEquals(scores.get(index).path(field), aggregateScore.path(field), field);
                }
            }
        }
        statementProbe.reset();
        JsonNode risk = assertTimeout(Duration.ofSeconds(15), () -> readJson("/api/deals/" + deal.getId() + "/risk"));
        assertTrue(risk.isObject());
        assertNoNoteBodyRead();
        assertTrue(statementProbe.ids.contains(DealMapper.class.getName() + ".getLatestDealTouches"));
    }

    @Test
    void editingTheOldestNoteMovesItToTheFirstPageAndCursorContinuationStaysComplete() throws Exception {
        Person person = newPerson(workspace);
        Deal deal = newDeal(workspace);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).minusDays(1);
        Note oldest = newDatedNote(person, deal, "Oldest note", now.minusDays(180));
        Set<Integer> expected = new HashSet<>();
        expected.add(oldest.getId());
        for (int index = 0; index < 25; index++) {
            expected.add(newDatedNote(person, deal, "Newer note " + index, now.minusDays(25 - index)).getId());
        }
        List<String> routes = List.of("/api/persons/" + person.getId() + "/notes",
            "/api/deals/" + deal.getId() + "/notes", "/api/users/" + member.getId() + "/notes");
        for (String route : routes) {
            JsonNode initial = readJson(route);
            assertEquals(25, initial.size());
            for (JsonNode note : initial) assertTrue(note.path("id").asInt() != oldest.getId());
        }
        TimeUnit.MILLISECONDS.sleep(1100);
        mockMvc.perform(request(HttpMethod.PUT, "/api/notes/{id}", oldest.getId())
                .session(memberSession).with(csrf().asHeader()).header("X-Workspace-Id", workspace.getId())
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of(
                    "content", "Edited oldest note", "person", person.getId(), "deal", deal.getId()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.person").value(person.getId()))
            .andExpect(jsonPath("$.deal").value(deal.getId()));
        for (String route : routes) {
            JsonNode first = readJson(route);
            assertEquals(oldest.getId(), first.get(0).path("id").asInt());
            assertEquals("Edited oldest note", first.get(0).path("content").asText());
            JsonNode boundary = first.get(24);
            MvcResult result = mockMvc.perform(get(route).session(memberSession)
                    .header("X-Workspace-Id", workspace.getId()).param("page", "2")
                    .param("beforeAt", boundary.path("updatedAt").asText())
                    .param("beforeId", boundary.path("id").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andReturn();
            Set<Integer> actual = new HashSet<>();
            for (JsonNode note : first) assertTrue(actual.add(note.path("id").asInt()));
            for (JsonNode note : objectMapper.readTree(result.getResponse().getContentAsString())) {
                assertTrue(actual.add(note.path("id").asInt()));
            }
            assertEquals(expected, actual);
            assertEquals(oldest.getId(), readJson(route).get(0).path("id").asInt());
            mockMvc.perform(get(route).session(memberSession).header("X-Workspace-Id", workspace.getId())
                    .param("beforeAt", "invalid").param("beforeId", "1"))
                .andExpect(status().isBadRequest());
            mockMvc.perform(get(route).session(memberSession).header("X-Workspace-Id", workspace.getId())
                    .param("beforeId", "1"))
                .andExpect(status().isBadRequest());
        }
    }

    @Test
    void ownedPersonShareLockExcludesArchivedAndForeignContacts() {
        Person active = newPerson(workspace);
        Person archived = newPerson(workspace);
        Person foreign = newPerson(newWorkspace(newOrganization()));
        assertEquals(1, personMapper.archive(workspace.getId(), archived.getId()));

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Person locked = personMapper.getOwnedPersonByIdForShare(workspace.getId(), active.getId());
            assertNotNull(locked);
            assertEquals(active.getId(), locked.getId());
            assertNull(personMapper.getOwnedPersonByIdForShare(workspace.getId(), archived.getId()));
            assertNull(personMapper.getOwnedPersonByIdForShare(workspace.getId(), foreign.getId()));
        });
    }

    private JsonNode readJson(String route) throws Exception {
        MvcResult response = mockMvc.perform(get(route).session(memberSession)
                .header("X-Workspace-Id", workspace.getId()))
            .andExpect(status().isOk()).andReturn();
        assertTrue(response.getResponse().getContentAsByteArray().length < 100_000);
        return objectMapper.readTree(response.getResponse().getContentAsString());
    }

    private void assertNoNoteBodyRead() {
        assertTrue(statementProbe.ids.stream().noneMatch(id -> id.startsWith(NoteMapper.class.getName() + ".")),
            statementProbe.ids.toString());
    }

    private Company newCompany(Person person) {
        Company company = new Company();
        company.setWorkspaceId(workspace.getId());
        company.setName("Scoring company " + unique());
        companyMapper.insert(company);
        person.setCompany(company);
        personMapper.update(person);
        return company;
    }

    private Note newDatedNote(Person person, Deal deal, String body, LocalDateTime createdAt) {
        Note note = new Note();
        note.setWorkspaceId(workspace.getId());
        note.setAuthor(member);
        note.setPerson(person);
        note.setDeal(deal);
        note.setVisibility("workspace");
        note.setContent(body);
        note.setCreatedAt(createdAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        noteMapper.insert(note);
        return note;
    }

    @Test
    void authoredNoteTotalsRespectVisibilityAndWorkspaceMembership() throws Exception {
        User other = newMember(workspace);
        newNote(workspace, other, null, null, "workspace", "Visible note");
        newNote(workspace, other, null, null, "private", "Private note");
        Workspace foreign = newWorkspace(newOrganization());
        newNote(foreign, other, null, null, "workspace", "Foreign note");
        mockMvc.perform(get("/api/users/{id}/notes/page", other.getId()).session(memberSession)
                .header("X-Workspace-Id", workspace.getId()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items.length()").value(1));
        mockMvc.perform(get("/api/users/{id}/notes/page", other.getId()).session(login(other))
                .header("X-Workspace-Id", workspace.getId()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2));
        User foreignMember = newMember(foreign);
        mockMvc.perform(get("/api/users/{id}/notes/page", foreignMember.getId()).session(memberSession)
                .header("X-Workspace-Id", workspace.getId()))
            .andExpect(status().isNotFound());
    }

    @Test
    void recordNotePagesBoundHydrationAndReachTheTwoThousandAndFirstNote() throws Exception {
        Person person = newPerson(workspace);
        Deal deal = newDeal(workspace);
        User other = newMember(workspace);
        Note secret = newNote(workspace, other, null, null, "private", "Private target");
        String prefix = "[Hidden label](note:" + secret.getId() + ") ";
        String body = prefix + "x".repeat(50_000 - prefix.length());
        Note oldest = newNote(workspace, member, person, deal, "private", body);
        for (int index = 1; index < 2001; index++) {
            newNote(workspace, member, person, deal, "private", body);
        }
        newNote(workspace, other, person, deal, "private", "Other private note");
        for (String route : List.of("/api/persons/" + person.getId() + "/notes",
                "/api/deals/" + deal.getId() + "/notes")) {
            for (int page : List.of(1, 2, 21)) {
                MvcResult result = mockMvc.perform(get(route).session(memberSession)
                        .header("X-Workspace-Id", workspace.getId())
                        .param("page", Integer.toString(page)).param("size", "10000"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(page == 21 ? 1 : 100)).andReturn();
                JsonNode notes = objectMapper.readTree(result.getResponse().getContentAsString());
                for (JsonNode note : notes) {
                    assertEquals(500, note.path("content").asText().length());
                    assertFalse(note.path("content").asText().contains("Hidden label"));
                    assertTrue(note.path("content").asText().contains("(private note)"));
                }
                if (page == 21) {
                    assertEquals(oldest.getId(), notes.get(0).path("id").asInt());
                }
            }
            mockMvc.perform(get(route).session(memberSession)
                    .header("X-Workspace-Id", workspace.getId()).param("page", "2147483647"))
                .andExpect(status().isBadRequest());
        }
        verify(noteMapper).getVisibleNotesByPersonId(workspace.getId(), person.getId(), member.getId(), 100, 2000, null);
        verify(noteMapper).getVisibleNotesByDealId(workspace.getId(), deal.getId(), member.getId(), 100, 2000, null);
        MvcResult embedded = mockMvc.perform(get("/api/persons/{id}", person.getId()).session(memberSession)
                .header("X-Workspace-Id", workspace.getId()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.notes.length()").value(25)).andReturn();
        assertFalse(embedded.getResponse().getContentAsString().contains("Hidden label"));
        assertEquals(500, objectMapper.readTree(embedded.getResponse().getContentAsString())
            .path("notes").get(0).path("content").asText().length());
        NoteMapper realMapper = sqlSessionTemplate.getMapper(NoteMapper.class);
        assertEquals(25, realMapper.getVisibleNotesByPersonId(workspace.getId(), person.getId(), member.getId(), 25, 0).size());
        assertEquals(25, realMapper.getVisibleNotesByDealId(workspace.getId(), deal.getId(), member.getId(), 25, 0).size());
    }

    @Test
    void backlinkNotesAreLimitedBeforeHydrationWithRedactedPreviewsAndVisibilityExclusions() throws Exception {
        Person person = newPerson(workspace);
        User other = newMember(workspace);
        Note privateTarget = newNote(workspace, other, null, null, "private", "Secret target");
        String prefix = "[Secret label](note:" + privateTarget.getId() + ") [Contact](person:" + person.getId() + ") ";
        String content = prefix + "x".repeat(50_000 - prefix.length());
        for (int index = 0; index < 105; index++) {
            newBacklinkNote(workspace, member, person, "private", content);
        }
        Note invisible = newBacklinkNote(workspace, other, person, "private", "Other private content");
        Workspace foreignWorkspace = newWorkspace(newOrganization());
        Note foreign = newBacklinkNote(foreignWorkspace, member, person, "workspace", "Foreign content");
        MvcResult first = mockMvc.perform(get("/api/notes/referencing").session(memberSession)
                .header("X-Workspace-Id", workspace.getId()).param("refType", "person")
                .param("refId", Integer.toString(person.getId())).param("size", "10000"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(100)).andReturn();
        JsonNode items = objectMapper.readTree(first.getResponse().getContentAsString());
        for (JsonNode item : items) {
            assertEquals(500, item.path("content").asText().length());
            assertTrue(item.path("content").asText().contains("(private note)"));
            assertFalse(item.path("content").asText().contains("Secret label"));
            assertTrue(item.path("id").asInt() != invisible.getId());
            assertTrue(item.path("id").asInt() != foreign.getId());
        }
        verify(noteMapper).getNotesReferencing(workspace.getId(), "person", person.getId(), member.getId(), 100, 0);
        assertEquals(100, sqlSessionTemplate.getMapper(NoteMapper.class).getNotesReferencing(
            workspace.getId(), "person", person.getId(), member.getId(), 100, 0).size());
        MvcResult second = mockMvc.perform(get("/api/notes/referencing").session(memberSession)
                .header("X-Workspace-Id", workspace.getId()).param("refType", "person")
                .param("refId", Integer.toString(person.getId())).param("size", "100").param("page", "2"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(5)).andReturn();
        for (JsonNode item : objectMapper.readTree(second.getResponse().getContentAsString())) {
            assertTrue(item.path("id").asInt() != invisible.getId());
            assertTrue(item.path("id").asInt() != foreign.getId());
            for (JsonNode firstItem : items) {
                assertTrue(item.path("id").asInt() != firstItem.path("id").asInt());
            }
        }
        mockMvc.perform(get("/api/notes/referencing").session(memberSession)
                .header("X-Workspace-Id", workspace.getId()).param("refType", "person")
                .param("refId", Integer.toString(person.getId())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(25));
        mockMvc.perform(get("/api/notes/referencing").session(memberSession)
                .header("X-Workspace-Id", workspace.getId()).param("refType", "person")
                .param("refId", Integer.toString(person.getId())).param("page", "2147483647"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/notes/referencing").session(memberSession)
                .header("X-Workspace-Id", workspace.getId()).param("refType", "note")
                .param("refId", Integer.toString(privateTarget.getId())))
            .andExpect(status().isNotFound());
    }

    @Test
    void taskHistoryImportAndUpdateCompleteWithBoardBeforePersonLocks() throws Exception {
        Person person = newPerson(workspace);
        int taskId = createTarget("tasks");
        User importer = newMember(workspace);
        MockHttpSession importerSession = login(importer);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rows", List.of(Map.of("when", "2026-01-04T05:06:07Z",
            "email", "history-" + unique() + "@example.test", "description", "Historical task",
            "source", unique(), "completed", "true")));
        body.put("mapping", List.of(Map.of("column", "when", "field", "occurredAt"),
            Map.of("column", "email", "field", "participantEmail"),
            Map.of("column", "description", "field", "description"),
            Map.of("column", "source", "field", "sourceId"),
            Map.of("column", "completed", "field", "completed")));
        body.put("links", Map.of("0", person.getId()));
        JsonNode preview = postJson("/api/imports/history/tasks/preview", importerSession, body);
        assertEquals(1, preview.path("toCreate").asInt());
        body.put("duplicateReviewProof", preview.path("duplicateReviewProof").asText());
        CountDownLatch updateHoldsBoard = new CountDownLatch(1);
        CountDownLatch importAtBoard = new CountDownLatch(1);
        CountDownLatch importAcquiredBoard = new CountDownLatch(1);
        CountDownLatch releaseUpdate = new CountDownLatch(1);
        AtomicInteger boardCalls = new AtomicInteger();
        TaskMapper realMapper = sqlSessionTemplate.getMapper(TaskMapper.class);
        doAnswer(invocation -> {
            if (boardCalls.incrementAndGet() == 1) {
                realMapper.lockTaskBoard(workspace.getId());
                updateHoldsBoard.countDown();
                await(releaseUpdate);
            } else {
                importAtBoard.countDown();
                realMapper.lockTaskBoard(workspace.getId());
                importAcquiredBoard.countDown();
            }
            return null;
        }).when(taskMapper).lockTaskBoard(workspace.getId());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var update = executor.submit(() -> writePersonLink("tasks", "PUT", taskId, person.getId()));
            try {
                await(updateHoldsBoard);
                var imported = executor.submit(() -> postJson("/api/imports/history/tasks", importerSession, body));
                await(importAtBoard);
                assertEquals(1, importAcquiredBoard.getCount());
                assertThrows(TimeoutException.class, () -> imported.get(250, TimeUnit.MILLISECONDS));
                releaseUpdate.countDown();
                assertEquals(200, update.get(30, TimeUnit.SECONDS).getResponse().getStatus());
                assertEquals(1, imported.get(30, TimeUnit.SECONDS).path("created").asInt());
            } finally {
                releaseUpdate.countDown();
            }
        }
        Task updated = realMapper.getTaskById(workspace.getId(), taskId);
        assertNotNull(updated);
        assertNotNull(updated.getPerson());
        assertEquals("Changed", updated.getDescription());
        assertEquals(person.getId(), updated.getPerson().getId());
        assertEquals(0, updated.getPosition());
        assertEquals(2, targetCount("tasks"));
        org.mockito.InOrder locks = org.mockito.Mockito.inOrder(taskMapper, personMapper);
        locks.verify(taskMapper, org.mockito.Mockito.times(2)).lockTaskBoard(workspace.getId());
        locks.verify(personMapper).getOwnedPersonByIdForShare(workspace.getId(), person.getId());
    }

    @Test
    void shareRevocationDefeatsTaskCreationWaitingOnBoard() throws Exception {
        Workspace owner = newWorkspace(workspace.getOrgId());
        Person person = newPerson(owner);
        assertEquals(1, shareMapper.sharePerson(person.getId(), owner.getId(), workspace.getId(), member.getId(), false));
        CountDownLatch boardLocked = new CountDownLatch(1);
        CountDownLatch writeAtBoard = new CountDownLatch(1);
        CountDownLatch releaseRevocation = new CountDownLatch(1);
        TaskMapper realMapper = sqlSessionTemplate.getMapper(TaskMapper.class);
        doAnswer(invocation -> {
            writeAtBoard.countDown();
            realMapper.lockTaskBoard(workspace.getId());
            return null;
        }).when(taskMapper).lockTaskBoard(workspace.getId());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var revocation = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                realMapper.lockTaskBoard(workspace.getId());
                boardLocked.countDown();
                await(releaseRevocation);
                assertEquals(1, shareMapper.unsharePerson(person.getId(), owner.getId(), workspace.getId()));
            }));
            try {
                await(boardLocked);
                var write = executor.submit(() -> writePersonLink("tasks", "POST", 0, person.getId()));
                await(writeAtBoard);
                assertThrows(TimeoutException.class, () -> write.get(250, TimeUnit.MILLISECONDS));
                releaseRevocation.countDown();
                revocation.get(30, TimeUnit.SECONDS);
                assertEquals(404, write.get(30, TimeUnit.SECONDS).getResponse().getStatus());
            } finally {
                releaseRevocation.countDown();
            }
        }
        assertEquals(0, targetCount("tasks"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"tasks:POST", "tasks:PUT", "activities:POST", "activities:PUT"})
    void shareRevocationDefeatsLinkedWritesWaitingOnPerson(String operation) throws Exception {
        String[] parts = operation.split(":");
        String resource = parts[0];
        String method = parts[1];
        int targetId = "PUT".equals(method) ? createTarget(resource) : 0;
        int beforeCount = targetCount(resource);
        Workspace owner = newWorkspace(workspace.getOrgId());
        Person person = newPerson(owner);
        assertEquals(1, shareMapper.sharePerson(person.getId(), owner.getId(), workspace.getId(), member.getId(), false));
        CountDownLatch personLocked = new CountDownLatch(1);
        CountDownLatch writeAtPerson = new CountDownLatch(1);
        CountDownLatch releaseRevocation = new CountDownLatch(1);
        PersonMapper realMapper = sqlSessionTemplate.getMapper(PersonMapper.class);
        if ("activities:POST".equals(operation)) {
            doAnswer(invocation -> {
                writeAtPerson.countDown();
                return realMapper.getVisiblePersonByIdForUpdate(workspace.getId(), person.getId());
            }).when(personMapper).getVisiblePersonByIdForUpdate(workspace.getId(), person.getId());
        } else {
            doAnswer(invocation -> {
                writeAtPerson.countDown();
                return realMapper.getVisiblePersonByIdForShare(workspace.getId(), person.getId());
            }).when(personMapper).getVisiblePersonByIdForShare(workspace.getId(), person.getId());
        }
        doAnswer(invocation -> {
            boolean visible = realMapper.exists(workspace.getId(), person.getId());
            writeAtPerson.countDown();
            return visible;
        }).when(personMapper).exists(workspace.getId(), person.getId());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var revocation = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                assertNotNull(realMapper.getOwnedPersonByIdForUpdate(owner.getId(), person.getId()));
                personLocked.countDown();
                await(releaseRevocation);
                assertEquals(1, shareMapper.unsharePerson(person.getId(), owner.getId(), workspace.getId()));
            }));
            try {
                await(personLocked);
                var write = executor.submit(() -> writePersonLink(resource, method, targetId, person.getId()));
                await(writeAtPerson);
                assertThrows(TimeoutException.class, () -> write.get(250, TimeUnit.MILLISECONDS));
                releaseRevocation.countDown();
                revocation.get(30, TimeUnit.SECONDS);
                assertEquals(404, write.get(30, TimeUnit.SECONDS).getResponse().getStatus());
            } finally {
                releaseRevocation.countDown();
            }
        }
        assertEquals(beforeCount, targetCount(resource));
        if (targetId != 0) {
            assertUnchanged(resource, targetId);
        }
    }

    private MvcResult writePersonLink(String resource, String method, int targetId, int personId) throws Exception {
        String uri = "/api/" + resource + (targetId == 0 ? "" : "/" + targetId);
        return mockMvc.perform(request(HttpMethod.valueOf(method), uri).session(memberSession)
            .with(csrf().asHeader()).header("X-Workspace-Id", workspace.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(writeBody(resource, "person", personId, false))))
            .andReturn();
    }

    private Note newBacklinkNote(Workspace owner, User author, Person target, String visibility, String content) {
        Note note = newNote(owner, author, null, null, visibility, content);
        EntityReference reference = new EntityReference();
        reference.setWorkspaceId(owner.getId());
        reference.setSourceType("note");
        reference.setSourceId(note.getId());
        reference.setRefType("person");
        reference.setRefId(target.getId());
        reference.setLabel("Contact");
        entityReferenceMapper.insert(reference);
        return note;
    }

    @Test
    void replyFileLabelsAreAbsentFromStorageInboxAndStompWhileThreadRedacts() throws Exception {
        Person person = newPerson(workspace);
        User author = newMember(workspace);
        MockHttpSession authorSession = login(author);
        Note privateNote = newNote(workspace, author, null, null, "private", "Private note");
        String filename = "Acquisition-Target-Secret.pdf";
        Attachment attachment = new Attachment();
        attachment.setWorkspaceId(workspace.getId());
        attachment.setEntityType("note");
        attachment.setEntityId(privateNote.getId());
        attachment.setFileName(filename);
        attachment.setUrl("https://files.example.com/" + unique());
        attachment.setContentType("application/pdf");
        attachment.setSize(20L);
        attachmentMapper.insert(attachment);
        long threadId = postJson("/api/comment-threads", memberSession, Map.of(
            "targetType", "person", "targetId", person.getId(), "content", "Root", "clientToken", unique()))
            .path("id").asLong();
        String content = "[" + filename + "](file:" + attachment.getId() + ")";
        long replyId = postJson("/api/comment-threads/" + threadId + "/comments", authorSession,
            Map.of("content", content, "clientToken", unique())).path("id").asLong();
        mockMvc.perform(get("/api/attachments/{id}", attachment.getId()).session(memberSession)
                .header("X-Workspace-Id", workspace.getId()))
            .andExpect(status().isNotFound());
        MvcResult thread = mockMvc.perform(get("/api/comment-threads/{id}", threadId).session(memberSession)
                .header("X-Workspace-Id", workspace.getId()))
            .andExpect(status().isOk()).andReturn();
        assertFalse(thread.getResponse().getContentAsString().contains(filename));
        assertTrue(thread.getResponse().getContentAsString().contains("(unavailable reference)"));
        Notification stored = notificationMapper.findByDedupe(workspace.getId(), member.getId(),
            "comment.reply:" + replyId + ":" + member.getId());
        assertNotNull(stored);
        assertNull(stored.getSourceLabel());
        MvcResult inbox = mockMvc.perform(get("/api/notifications").session(memberSession)
                .param("type", "comment.reply"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1)).andReturn();
        assertFalse(inbox.getResponse().getContentAsString().contains(filename));
        ArgumentCaptor<RealtimeNotificationPayload> payload = ArgumentCaptor.forClass(RealtimeNotificationPayload.class);
        verify(messagingTemplate, timeout(10_000)).convertAndSendToUser(
            eq(routingIdentities.destinationFor(member.getId())), eq("/queue/notifications"), payload.capture());
        assertNotNull(payload.getValue().notification());
        assertEquals("comment.reply", payload.getValue().notification().getType());
        assertNull(payload.getValue().notification().getSourceLabel());
        assertFalse(objectMapper.writeValueAsString(payload.getValue()).contains(filename));

        stored.setSourceLabel("@" + filename);
        notificationMapper.upsert(stored);
        Notification legacy = notificationMapper.findByDedupe(workspace.getId(), member.getId(),
            "comment.reply:" + replyId + ":" + member.getId());
        assertNotNull(legacy);
        assertEquals("@" + filename, legacy.getSourceLabel());
        MvcResult legacyInbox = mockMvc.perform(get("/api/notifications").session(memberSession)
                .param("type", "comment.reply"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1)).andReturn();
        assertFalse(legacyInbox.getResponse().getContentAsString().contains(filename));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "DELETE"})
    void privacyCommitDefeatsWaitingNonAuthorMutation(String method) throws Exception {
        User author = newMember(workspace);
        Note note = newNote(workspace, author, null, null, "workspace", "Visible content");
        CountDownLatch privateWritten = new CountDownLatch(1);
        CountDownLatch releaseAuthor = new CountDownLatch(1);
        CountDownLatch attackerReading = new CountDownLatch(1);
        NoteMapper realMapper = sqlSessionTemplate.getMapper(NoteMapper.class);
        doAnswer(invocation -> {
            attackerReading.countDown();
            return realMapper.getVisibleNoteByIdForUpdate(workspace.getId(), note.getId(), member.getId());
        }).when(noteMapper).getVisibleNoteByIdForUpdate(workspace.getId(), note.getId(), member.getId());
        doAnswer(invocation -> {
            Note visible = realMapper.getVisibleNoteById(workspace.getId(), note.getId(), member.getId());
            attackerReading.countDown();
            return visible;
        }).when(noteMapper).getVisibleNoteById(workspace.getId(), note.getId(), member.getId());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var privacy = executor.submit(() -> {
                authenticate(author);
                try {
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        Note update = new Note();
                        update.setContent("New confidential");
                        update.setVisibility("private");
                        noteService.update(note.getId(), update);
                        privateWritten.countDown();
                        await(releaseAuthor);
                    });
                } finally {
                    clearContext();
                }
            });
            try {
                assertTrue(privateWritten.await(10, TimeUnit.SECONDS));
                var attack = executor.submit(() -> mutateNote(method, note.getId(), memberSession, "Attacker overwrite"));
                assertTrue(attackerReading.await(10, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> attack.get(1, TimeUnit.SECONDS));
                releaseAuthor.countDown();
                privacy.get(15, TimeUnit.SECONDS);
                assertEquals(404, attack.get(15, TimeUnit.SECONDS).getResponse().getStatus());
            } finally {
                releaseAuthor.countDown();
            }
        }
        Note preserved = noteMapper.getNoteById(workspace.getId(), note.getId());
        assertNotNull(preserved);
        assertEquals("private", preserved.getVisibility());
        assertEquals("New confidential", preserved.getContent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "DELETE"})
    void earlierVisibleMutationHoldsNoteLockUntilCommit(String method) throws Exception {
        User author = newMember(workspace);
        MockHttpSession authorSession = login(author);
        Note note = newNote(workspace, author, null, null, "workspace", "Visible content");
        CountDownLatch attackerLocked = new CountDownLatch(1);
        CountDownLatch releaseAttacker = new CountDownLatch(1);
        CountDownLatch authorReading = new CountDownLatch(1);
        NoteMapper realMapper = sqlSessionTemplate.getMapper(NoteMapper.class);
        doAnswer(invocation -> {
            Note locked = realMapper.getVisibleNoteByIdForUpdate(workspace.getId(), note.getId(), member.getId());
            attackerLocked.countDown();
            await(releaseAttacker);
            return locked;
        }).when(noteMapper).getVisibleNoteByIdForUpdate(workspace.getId(), note.getId(), member.getId());
        doAnswer(invocation -> {
            authorReading.countDown();
            return realMapper.getVisibleNoteByIdForUpdate(workspace.getId(), note.getId(), author.getId());
        }).when(noteMapper).getVisibleNoteByIdForUpdate(workspace.getId(), note.getId(), author.getId());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var attack = executor.submit(() -> mutateNote(method, note.getId(), memberSession, "Attacker overwrite"));
            try {
                assertTrue(attackerLocked.await(10, TimeUnit.SECONDS));
                var privacy = executor.submit(() -> mutateNote("PUT", note.getId(), authorSession, "New confidential"));
                assertTrue(authorReading.await(10, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> privacy.get(1, TimeUnit.SECONDS));
                releaseAttacker.countDown();
                assertEquals(200, attack.get(15, TimeUnit.SECONDS).getResponse().getStatus());
                assertEquals("DELETE".equals(method) ? 404 : 200,
                    privacy.get(15, TimeUnit.SECONDS).getResponse().getStatus());
            } finally {
                releaseAttacker.countDown();
            }
        }
        Note stored = noteMapper.getNoteById(workspace.getId(), note.getId());
        if ("DELETE".equals(method)) {
            assertNull(stored);
        } else {
            assertNotNull(stored);
            assertEquals("private", stored.getVisibility());
            assertEquals("New confidential", stored.getContent());
        }
    }

    private MvcResult mutateNote(String method, int noteId, MockHttpSession session, String content)
            throws Exception {
        return mockMvc.perform(request(HttpMethod.valueOf(method), "/api/notes/" + noteId)
            .session(session).with(csrf().asHeader()).header("X-Workspace-Id", workspace.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("content", content, "visibility", "private"))))
            .andReturn();
    }

    private void authenticate(User user) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        tenantContext.set(workspace.getId(), workspace.getOrgId(), user.getId(), "member", null);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(30, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private JsonNode postJson(String uri, MockHttpSession session, Map<String, Object> body) throws Exception {
        MvcResult result = mockMvc.perform(post(uri).session(session).with(csrf().asHeader())
                .header("X-Workspace-Id", workspace.getId()).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private Map<String, Object> writeBody(String resource, String field, int id, boolean embedded) {
        Map<String, Object> body = new LinkedHashMap<>();
        if ("tasks".equals(resource)) {
            body.put("description", "Changed");
            body.put("assignedToId", member.getId());
        } else {
            body.put("type", "call");
            body.put("subject", "Changed");
        }
        body.put(embedded ? field : field + "Id", embedded ? Map.of("id", id) : id);
        return body;
    }

    private int createTarget(String resource) {
        if ("tasks".equals(resource)) {
            Task task = new Task();
            task.setWorkspaceId(workspace.getId());
            task.setDescription("Original");
            task.setAssignedTo(member);
            task.setStatus("todo");
            taskMapper.insert(task);
            return task.getId();
        }
        Activity activity = new Activity();
        activity.setWorkspaceId(workspace.getId());
        activity.setCreatedBy(member);
        activity.setType("call");
        activity.setSubject("Original");
        activity.setTimestamp("2026-09-01 12:00:00");
        activityMapper.insert(activity);
        return activity.getId();
    }

    private int targetCount(String resource) {
        return "tasks".equals(resource) ? taskMapper.getAllTasks(workspace.getId()).size()
            : activityMapper.getAllActivities(workspace.getId()).size();
    }

    private void assertUnchanged(String resource, int id) {
        if ("tasks".equals(resource)) {
            Task task = taskMapper.getTaskById(workspace.getId(), id);
            assertNotNull(task);
            assertEquals("Original", task.getDescription());
            assertNull(task.getPerson());
            assertNull(task.getDeal());
        } else {
            Activity activity = activityMapper.getActivityById(workspace.getId(), id);
            assertNotNull(activity);
            assertEquals("Original", activity.getSubject());
            assertNull(activity.getPerson());
            assertNull(activity.getDeal());
        }
    }

    private int linkedId(String field, Workspace owner) {
        return "person".equals(field) ? newPerson(owner).getId() : newDeal(owner).getId();
    }

    private Person newPerson(Workspace owner) {
        Person person = new Person();
        person.setWorkspaceId(owner.getId());
        person.setName("Contact " + unique());
        personMapper.insert(person);
        return person;
    }

    private Deal newDeal(Workspace owner) {
        Pipeline pipeline = new Pipeline();
        pipeline.setWorkspaceId(owner.getId());
        pipeline.setName("Pipeline " + unique());
        pipelineMapper.insertPipeline(pipeline);
        Stage stage = new Stage();
        stage.setWorkspaceId(owner.getId());
        stage.setPipeline(pipeline);
        stage.setName("Stage " + unique());
        pipelineMapper.insertStage(stage);
        Deal deal = new Deal();
        deal.setPipelineId(pipeline.getId());
        deal.setStageId(stage.getId());
        deal.setWorkspaceId(owner.getId());
        deal.setName("Deal " + unique());
        deal.setCurrency("JPY");
        dealMapper.insert(deal);
        return deal;
    }

    private Note newNote(Workspace owner, User author, Person person, Deal deal, String visibility, String content) {
        Note note = new Note();
        note.setWorkspaceId(owner.getId());
        note.setAuthor(author);
        note.setPerson(person);
        note.setDeal(deal);
        note.setVisibility(visibility);
        note.setContent(content);
        noteMapper.insert(note);
        return note;
    }

    private int newOrganization() {
        Organization organization = new Organization();
        organization.setName("Record boundary " + unique());
        organization.setSlug("record-boundary-" + unique());
        organizationMapper.insert(organization);
        return organization.getId();
    }

    private Workspace newWorkspace(int orgId) {
        Workspace created = new Workspace();
        created.setOrgId(orgId);
        created.setName("Record boundary " + unique());
        created.setSlug("record-boundary-" + unique());
        workspaceMapper.insert(created);
        return created;
    }

    private User newMember(Workspace owner) {
        User user = new User();
        user.setUsername("record_boundary_" + unique());
        user.setDisplayName("Record boundary member");
        user.setEmail(unique() + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        workspaceMapper.addMember(owner.getId(), user.getId(), "member");
        return user;
    }

    private MockHttpSession login(User user) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("username", user.getUsername(), "password", PASSWORD))))
            .andExpect(status().isOk()).andReturn();
        if (!(result.getRequest().getSession(false) instanceof MockHttpSession session)) {
            throw new IllegalStateException("Login did not establish a session");
        }
        return session;
    }

    @Intercepts({
        @Signature(type = Executor.class, method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class, CacheKey.class, BoundSql.class})
    })
    static final class StatementProbe implements Interceptor {
        private final Set<String> ids = ConcurrentHashMap.newKeySet();
        private final Map<String, List<Integer>> rowCounts = new ConcurrentHashMap<>();
        private final List<Boolean> previousDisclosurePageCached = new CopyOnWriteArrayList<>();
        private CacheKey previousDisclosurePage;

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            Object result = invocation.proceed();
            if (invocation.getArgs()[0] instanceof MappedStatement statement) {
                ids.add(statement.getId());
                if (result instanceof List<?> rows) {
                    rowCounts.computeIfAbsent(statement.getId(), key -> new CopyOnWriteArrayList<>()).add(rows.size());
                }
                if (statement.getId().equals(DataSubjectDisclosureMapper.class.getName() + ".findNotePage")
                        && invocation.getTarget() instanceof Executor executor
                        && invocation.getArgs()[2] instanceof RowBounds bounds) {
                    if (previousDisclosurePage != null) {
                        previousDisclosurePageCached.add(executor.isCached(statement, previousDisclosurePage));
                    }
                    Object parameters = invocation.getArgs()[1];
                    previousDisclosurePage = executor.createCacheKey(statement, parameters, bounds,
                        statement.getBoundSql(parameters));
                }
            }
            return result;
        }

        void reset() {
            ids.clear();
            rowCounts.clear();
            previousDisclosurePageCached.clear();
            previousDisclosurePage = null;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StatementProbeConfiguration {
        @Bean
        StatementProbe statementProbe() {
            return new StatementProbe();
        }
    }

    private static String unique() {
        return UUID.randomUUID().toString();
    }
}
