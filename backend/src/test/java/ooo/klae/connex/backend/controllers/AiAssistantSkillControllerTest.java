package ooo.klae.connex.backend.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ooo.klae.connex.backend.ai.AiFeature;
import ooo.klae.connex.backend.ai.assistant.AiSkillCatalog;
import ooo.klae.connex.backend.ai.assistant.AiSkillCatalog.Authority;
import ooo.klae.connex.backend.ai.assistant.AiSkillCatalog.Availability;
import ooo.klae.connex.backend.ai.assistant.AiSkillCatalog.Bounds;
import ooo.klae.connex.backend.ai.assistant.AiSkillCatalog.Budgets;
import ooo.klae.connex.backend.ai.assistant.AiSkillCatalog.Evaluation;
import ooo.klae.connex.backend.ai.assistant.AiSkillCatalog.PartialBehavior;
import ooo.klae.connex.backend.ai.assistant.AiSkillCatalog.SkillSpec;
import ooo.klae.connex.backend.ai.assistant.AiSkillDirectoryService;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.GlobalExceptionHandler;
import ooo.klae.connex.backend.observability.ErrorReporter;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;
import ooo.klae.connex.backend.tenant.TenantContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The directory is what a contextual entry point is built from, so its HTTP shape is the contract:
 * exactly the client half of each declaration, only what this build can run, only what the caller's
 * role can run, and only for the surface the client asked about.
 */
class AiAssistantSkillControllerTest {
    private static final int WORKSPACE_ID = 3;
    private static final int USER_ID = 5;
    private static final String PATH = "/api/ai/assistant/skills";

    /** Every property an entry point may publish, and nothing else. */
    private static final Set<String> CONTRACT_FIELDS = Set.of(
            "key", "version", "nameKey", "descriptionKey", "contextKinds", "needsSubject",
            "authority");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private WorkspaceService workspaceService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AiSkillCatalog catalog = mock(AiSkillCatalog.class);
        when(catalog.skills()).thenReturn(List.of(
                spec("relationship_brief_v1", Availability.AVAILABLE,
                        Set.of("person", "company"), true, Set.of(Permission.AI_USE),
                        Authority.READ),
                spec("pipeline_attention_review_v1", Availability.AVAILABLE,
                        Set.of("deal", "company"), false, Set.of(Permission.AI_USE),
                        Authority.READ),
                spec("natural_language_report_v1", Availability.AVAILABLE,
                        Set.of("deal"), false, Set.of(Permission.AI_USE, Permission.REPORT_READ),
                        Authority.DRAFT),
                spec("meeting_preparation_v1", Availability.DECLARED,
                        Set.of("person"), true, Set.of(Permission.AI_USE), Authority.READ)));
        workspaceService = mock(WorkspaceService.class);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(WORKSPACE_ID);
        when(workspaceService.getCurrentUserId()).thenReturn(USER_ID);
        when(workspaceService.permissionsFor(WORKSPACE_ID, USER_ID))
                .thenReturn(Set.of(Permission.AI_USE));
        mockMvc = standalone(new AiSkillDirectoryService(catalog, workspaceService));
    }

    @Test
    void theDirectoryPublishesOnlyTheContractedFieldsInCatalogOrder() throws Exception {
        String body = mockMvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].key").value("relationship_brief_v1"))
                .andExpect(jsonPath("$[0].version").value("1.0.0"))
                .andExpect(jsonPath("$[0].nameKey")
                        .value("askConnex.skills.relationshipBrief.name"))
                .andExpect(jsonPath("$[0].descriptionKey")
                        .value("askConnex.skills.relationshipBrief.description"))
                .andExpect(jsonPath("$[0].contextKinds.length()").value(2))
                .andExpect(jsonPath("$[0].contextKinds[0]").value("company"))
                .andExpect(jsonPath("$[0].contextKinds[1]").value("person"))
                .andExpect(jsonPath("$[0].needsSubject").value(true))
                .andExpect(jsonPath("$[0].authority").value("read"))
                .andExpect(jsonPath("$[1].key").value("pipeline_attention_review_v1"))
                .andExpect(jsonPath("$[1].needsSubject").value(false))
                .andReturn().getResponse().getContentAsString();

        for (JsonNode entry : JSON.readTree(body).values()) {
            assertEquals(CONTRACT_FIELDS, Set.copyOf(entry.propertyNames()),
                    "An entry point published a property outside the client contract");
        }
    }

    @Test
    void aContextFilterNarrowsTheDirectoryToThatSurface() throws Exception {
        mockMvc.perform(get(PATH).param("context", "deal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].key").value("pipeline_attention_review_v1"));
    }

    @Test
    void anUndeclaredContextIsRejectedRatherThanTreatedAsNoFilter() throws Exception {
        mockMvc.perform(get(PATH).param("context", "radar"))
                .andExpect(status().isBadRequest());
    }

    /**
     * The catalog gates a skill on the permissions running it would actually use, so a member who
     * could not run it must never be shown it — an entry point the server would refuse is worse than
     * no entry point at all.
     */
    @Test
    void aSkillTheCallersRoleCannotRunIsNeverOffered() throws Exception {
        mockMvc.perform(get(PATH).param("context", "deal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key == 'natural_language_report_v1')]").isEmpty());

        when(workspaceService.permissionsFor(WORKSPACE_ID, USER_ID))
                .thenReturn(Set.of(Permission.AI_USE, Permission.REPORT_READ));

        mockMvc.perform(get(PATH).param("context", "deal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].key").value("natural_language_report_v1"))
                .andExpect(jsonPath("$[1].authority").value("draft"));
    }

    @Test
    void aReservedSkillThisBuildCannotRunIsNeverOffered() throws Exception {
        mockMvc.perform(get(PATH).param("context", "person"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].key").value("relationship_brief_v1"));
    }

    /**
     * Authentication is the filter chain's default-deny; the workspace-scoped half of the gate is
     * the declarative permission on the directory read, which is what a caller without assistant
     * access hits.
     */
    @Test
    void listingIsGatedOnTheAssistantPermission() throws Exception {
        Method method = AiSkillDirectoryService.class.getDeclaredMethod("list", String.class);
        RequirePermission required = method.getAnnotation(RequirePermission.class);

        assertNotNull(required, "The directory read must be permission-gated");
        assertEquals(Permission.AI_USE, required.value());
    }

    @Test
    void aCallerRefusedByThatGateSeesForbiddenRatherThanAnEmptyDirectory() throws Exception {
        AiSkillDirectoryService refusing = mock(AiSkillDirectoryService.class);
        when(refusing.list(null)).thenThrow(
                new ForbiddenException("Requires the AI_USE permission in this workspace"));

        standalone(refusing).perform(get(PATH))
                .andExpect(status().isForbidden());
    }

    /** A read-only surface: there is no mutating, CSRF-protected route on the directory. */
    @Test
    void theDirectoryExposesNoMutatingRoute() throws Exception {
        mockMvc.perform(post(PATH))
                .andExpect(status().isMethodNotAllowed());
    }

    private static MockMvc standalone(AiSkillDirectoryService directory) {
        return MockMvcBuilders.standaloneSetup(new AiAssistantSkillController(directory))
                .setControllerAdvice(new GlobalExceptionHandler(
                        mock(ErrorReporter.class), new TenantContext()))
                .build();
    }

    private static SkillSpec spec(
            String key,
            Availability availability,
            Set<String> contextKinds,
            boolean contextRequired,
            Set<Permission> permissions,
            Authority authority) {
        String camel = camelCase(key);
        return new SkillSpec(
                key,
                "1.0.0",
                availability,
                availability == Availability.AVAILABLE ? null : "skill_not_yet_implemented",
                "askConnex.skills." + camel + ".name",
                "askConnex.skills." + camel + ".description",
                contextKinds,
                contextRequired,
                Set.of(),
                Set.of(),
                List.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                true,
                permissions,
                AiFeature.ASSISTANT_CHAT,
                32_768,
                authority,
                new Bounds(0, 0, 0, 0),
                new Budgets(0, 0L, 0),
                Integer.MAX_VALUE,
                PartialBehavior.FAIL_CLOSED,
                new Evaluation(key, 0, Set.of()),
                List.<Pattern>of(),
                "");
    }

    private static String camelCase(String key) {
        String[] parts = key.split("_");
        StringBuilder camel = new StringBuilder();
        for (int index = 0; index < parts.length; index++) {
            String part = parts[index];
            if (index == parts.length - 1 && part.matches("v\\d+")) {
                continue;
            }
            if (camel.isEmpty()) {
                camel.append(part);
                continue;
            }
            camel.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return camel.toString();
    }
}
