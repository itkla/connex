package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;

import ooo.klae.connex.backend.ai.report.AiReportNarrativeService;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.dto.ReportNarrativeClaimDto;
import ooo.klae.connex.backend.dto.ReportNarrativeDto;
import ooo.klae.connex.backend.dto.ReportNarrativeSectionDto;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import tools.jackson.databind.ObjectMapper;

/**
 * Full-stack HTTP coverage for workspace-shared reports, RBAC, deterministic generation, and
 * immutable snapshot restore.
 */
@SpringBootTest
@Transactional
class ReportIntegrationTest {

    private static final String PASSWORD = "Report-Test-Pw1!";
    private static final String REPORT_BODY = """
        {
          "name": "January Activity",
          "description": "Monthly activity review",
          "cadence": "custom",
          "templateKey": null,
          "config": {
            "widgets": [{
              "id": "activity-total",
              "title": "Activity total",
              "dataSource": "activities",
              "measure": "count",
              "groupBy": "none",
              "chartType": "kpi"
            }],
            "filters": {
              "pipelineIds": null,
              "ownerIds": null,
              "statuses": null,
              "tagIds": null,
              "warmthBands": null
            },
            "range": {"start": "2026-01-01", "end": "2026-01-31"},
            "bucket": "day",
            "layout": [{"widgetId": "activity-total", "x": 0, "y": 0, "width": 6, "height": 4}]
          }
        }
        """;

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private RoleMapper roleMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private AiReportNarrativeService aiReportNarrativeService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
        when(aiReportNarrativeService.generate(
                anyInt(), anyString(), any(LocalDate.class), any(LocalDate.class), anyList()))
                .thenReturn(new ReportNarrativeDto(
                        true,
                        List.of(new ReportNarrativeSectionDto(
                                "Executive Summary",
                                List.of(new ReportNarrativeClaimDto(
                                        "Activity volume is supported by the appendix.",
                                        List.of("metric.0.0"))))),
                        List.of(),
                        null,
                        "2026-02-01T00:00:00Z",
                        0));
    }

    @Test
    void authenticatedCrudGenerationAndSnapshotFreezeRestore() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspace();
        User member = newMember(workspace, "member");
        MockHttpSession session = login(member.getUsername());

        int reportId = createReport(session, workspace);

        mockMvc.perform(get("/api/reports/{id}", reportId)
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("January Activity"));

        mockMvc.perform(get("/api/reports")
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(reportId));

        String updated = REPORT_BODY.replace("January Activity", "January Activity Review");
        mockMvc.perform(put("/api/reports/{id}", reportId)
                .header("X-Workspace-Id", workspace.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(updated)
                .session(session)
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("January Activity Review"));

        insertActivity(workspace.getId(), member.getId(), "2026-01-05 09:00:00");
        insertActivity(workspace.getId(), member.getId(), "2026-01-20 09:00:00");
        insertActivity(workspace.getId(), member.getId(), "2025-12-10 09:00:00");

        mockMvc.perform(post("/api/reports/{id}/generate", reportId)
                .header("X-Workspace-Id", workspace.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .session(session)
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.widgets[0].total").value(2))
            .andExpect(jsonPath("$.widgets[0].priorTotal").value(1))
            .andExpect(jsonPath("$.appendix[0].sourceId").value("metric.0.0"))
            .andExpect(jsonPath("$.narrative.available").value(true))
            .andExpect(jsonPath("$.citations[0].sourceId").value("metric.0.0"));

        MvcResult snapshotResult = mockMvc.perform(post("/api/reports/{id}/snapshots", reportId)
                .header("X-Workspace-Id", workspace.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .session(session)
                .with(csrf()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.computedResult.widgets[0].total").value(2))
            .andReturn();
        int snapshotId = responseId(snapshotResult);

        insertActivity(workspace.getId(), member.getId(), "2026-01-25 09:00:00");

        mockMvc.perform(post("/api/reports/{id}/generate", reportId)
                .header("X-Workspace-Id", workspace.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .session(session)
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.widgets[0].total").value(3));

        mockMvc.perform(get("/api/reports/{id}/snapshots/{snapshotId}", reportId, snapshotId)
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.computedResult.widgets[0].total").value(2));

        mockMvc.perform(delete("/api/reports/{id}/snapshots/{snapshotId}", reportId, snapshotId)
                .header("X-Workspace-Id", workspace.getId())
                .session(session)
                .with(csrf()))
            .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/reports/{id}", reportId)
                .header("X-Workspace-Id", workspace.getId())
                .session(session)
                .with(csrf()))
            .andExpect(status().isNoContent());
    }

    @Test
    void customRoleWithoutCreatePermissionIsForbidden() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspace();
        User reader = newMember(workspace, "member");
        WorkspaceRole role = new WorkspaceRole();
        role.setWorkspaceId(workspace.getId());
        role.setName("Report Reader " + UUID.randomUUID().toString().substring(0, 8));
        roleMapper.insertRole(role);
        roleMapper.insertPermissions(workspace.getId(), role.getId(), List.of("REPORT_READ"));
        workspaceMapper.setMemberCustomRole(workspace.getId(), reader.getId(), role.getId());
        MockHttpSession session = login(reader.getUsername());

        mockMvc.perform(post("/api/reports")
                .header("X-Workspace-Id", workspace.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(REPORT_BODY)
                .session(session)
                .with(csrf()))
            .andExpect(status().isForbidden());
    }

    @Test
    void customRoleWithoutReadPermissionIsForbidden() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspace();
        User member = newMember(workspace, "member");
        MockHttpSession session = login(member.getUsername());
        int reportId = createReport(session, workspace);
        WorkspaceRole role = new WorkspaceRole();
        role.setWorkspaceId(workspace.getId());
        role.setName("No Report Access " + UUID.randomUUID().toString().substring(0, 8));
        roleMapper.insertRole(role);
        roleMapper.insertPermissions(workspace.getId(), role.getId(), List.of("CONTACT_READ"));
        workspaceMapper.setMemberCustomRole(workspace.getId(), member.getId(), role.getId());

        mockMvc.perform(get("/api/reports/{id}", reportId)
                .header("X-Workspace-Id", workspace.getId())
                .session(session))
            .andExpect(status().isForbidden());
    }

    @Test
    void reportIdIsIsolatedAcrossWorkspacesForSameUser() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace first = newWorkspace();
        Workspace second = newWorkspace();
        User member = newMember(first, "member");
        workspaceMapper.addMember(second.getId(), member.getId(), "member");
        MockHttpSession session = login(member.getUsername());
        int reportId = createReport(session, first);
        MvcResult snapshotResult = mockMvc.perform(post("/api/reports/{id}/snapshots", reportId)
                .header("X-Workspace-Id", first.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .session(session)
                .with(csrf()))
            .andExpect(status().isCreated())
            .andReturn();
        int snapshotId = responseId(snapshotResult);

        mockMvc.perform(get("/api/reports/{id}", reportId)
                .header("X-Workspace-Id", second.getId())
                .session(session))
            .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/reports")
                .header("X-Workspace-Id", second.getId())
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(post("/api/reports/{id}/generate", reportId)
                .header("X-Workspace-Id", second.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .session(session)
                .with(csrf()))
            .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/reports/{id}/snapshots", reportId)
                .header("X-Workspace-Id", second.getId())
                .session(session))
            .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/reports/{id}/snapshots/{snapshotId}", reportId, snapshotId)
                .header("X-Workspace-Id", second.getId())
                .session(session))
            .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/reports/{id}/export.csv", reportId)
                .header("X-Workspace-Id", second.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .session(session)
                .with(csrf()))
            .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/reports/{id}/snapshots/{snapshotId}", reportId, snapshotId)
                .header("X-Workspace-Id", second.getId())
                .session(session)
                .with(csrf()))
            .andExpect(status().isNotFound());
    }

    @Test
    void dateBucketsAlignPriorValuesByRelativePeriodPosition() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Workspace workspace = newWorkspace();
        User member = newMember(workspace, "member");
        MockHttpSession session = login(member.getUsername());
        String dateReport = REPORT_BODY
                .replace("\"groupBy\": \"none\"", "\"groupBy\": \"date\"")
                .replace("\"chartType\": \"kpi\"", "\"chartType\": \"line-area\"");
        int reportId = createReport(session, workspace, dateReport);
        insertActivity(workspace.getId(), member.getId(), "2026-01-03 09:00:00");
        insertActivity(workspace.getId(), member.getId(), "2025-12-03 09:00:00");

        mockMvc.perform(post("/api/reports/{id}/generate", reportId)
                .header("X-Workspace-Id", workspace.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .session(session)
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.widgets[0].points.length()").value(1))
            .andExpect(jsonPath("$.widgets[0].points[0].value").value(1))
            .andExpect(jsonPath("$.widgets[0].points[0].priorValue").value(1));
    }

    private int createReport(MockHttpSession session, Workspace workspace) throws Exception {
        return createReport(session, workspace, REPORT_BODY);
    }

    private int createReport(MockHttpSession session, Workspace workspace, String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/reports")
                .header("X-Workspace-Id", workspace.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .session(session)
                .with(csrf()))
            .andExpect(status().isCreated())
            .andReturn();
        return responseId(result);
    }

    private int responseId(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asInt();
    }

    private void insertActivity(int workspaceId, int userId, String timestamp) {
        jdbcTemplate.update(
                "INSERT INTO activity (workspace_id, type, subject, created_by_id, timestamp) VALUES (?, ?, ?, ?, ?)",
                workspaceId, "call", "Report integration activity", userId, timestamp);
    }

    private Workspace newWorkspace() {
        String slug = "report-" + UUID.randomUUID().toString().substring(0, 8);
        Workspace workspace = new Workspace();
        workspace.setName(slug);
        workspace.setSlug(slug);
        workspaceMapper.insert(workspace);
        return workspace;
    }

    private User newMember(Workspace workspace, String role) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("report_" + suffix);
        user.setDisplayName("Report " + suffix);
        user.setEmail(suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        workspaceMapper.addMember(workspace.getId(), user.getId(), role);
        return user;
    }

    private MockHttpSession login(String username) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}";
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session, "login did not establish a report test session");
        return session;
    }
}
