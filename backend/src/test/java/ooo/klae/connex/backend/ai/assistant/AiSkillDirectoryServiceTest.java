package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.dto.AiAssistantSkillDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;

class AiSkillDirectoryServiceTest {
    private static final int WORKSPACE_ID = 7;
    private static final int USER_ID = 11;

    /** The executable keys, in the catalog's own declaration order. */
    private static final List<String> EXECUTABLE_KEYS = List.of(
            "relationship_cooling_explanation_v1",
            "activity_digest_v1",
            "relationship_brief_v1",
            "pipeline_attention_review_v1");

    private WorkspaceService workspaceService;
    private AiSkillDirectoryService directory;

    @BeforeEach
    void setUp() {
        workspaceService = mock(WorkspaceService.class);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(WORKSPACE_ID);
        when(workspaceService.getCurrentUserId()).thenReturn(USER_ID);
        when(workspaceService.permissionsFor(WORKSPACE_ID, USER_ID))
                .thenReturn(Set.of(Permission.AI_USE));
        directory = new AiSkillDirectoryService(new AiSkillCatalog(), workspaceService);
    }

    /**
     * A reserved key is a promise about a later build, not a capability. Offering one as an entry
     * point would put a button on a page that the router would then decline.
     */
    @Test
    void listsEveryExecutableSkillInCatalogOrderAndNoReservedKey() {
        assertEquals(EXECUTABLE_KEYS, keys(directory.list(null)));
    }

    @Test
    void aContextFilterKeepsOnlyTheSkillsThatCanAnchorToThatKind() {
        assertEquals(
                List.of("activity_digest_v1", "pipeline_attention_review_v1"),
                keys(directory.list("deal")));
        assertEquals(
                List.of(
                        "relationship_cooling_explanation_v1",
                        "activity_digest_v1",
                        "relationship_brief_v1"),
                keys(directory.list("person")));
    }

    @Test
    void aContextFilterIsMatchedAfterCaseAndWhitespaceNormalization() {
        assertEquals(keys(directory.list("deal")), keys(directory.list("  Deal ")));
    }

    @Test
    void anUndeclaredOrEmptyContextIsRefusedRatherThanSilentlyWidened() {
        assertThrows(BadRequestException.class, () -> directory.list("radar"));
        assertThrows(BadRequestException.class, () -> directory.list(""));
        assertThrows(BadRequestException.class, () -> directory.list("   "));
    }

    /**
     * The entry point is copy the client owns and a contract the server owns: i18n keys, the
     * declaration version it was built against, whether a record must anchor it, and the ceiling on
     * what running it can do.
     */
    @Test
    void anEntryPointCarriesTheClientHalfOfTheContractOnly() {
        AiAssistantSkillDto entry = directory.list(null).getFirst();

        assertEquals("relationship_cooling_explanation_v1", entry.key());
        assertEquals("1.0.0", entry.version());
        assertEquals("askConnex.skills.relationshipCoolingExplanation.name", entry.nameKey());
        assertEquals(
                "askConnex.skills.relationshipCoolingExplanation.description",
                entry.descriptionKey());
        assertEquals(List.of("company", "person"), entry.contextKinds());
        assertTrue(entry.needsSubject());
        assertEquals("read", entry.authority());
    }

    @Test
    void aMemberWithoutTheAssistantPermissionIsOfferedNothing() {
        when(workspaceService.permissionsFor(WORKSPACE_ID, USER_ID)).thenReturn(Set.of());

        assertTrue(directory.list(null).isEmpty());
        assertTrue(directory.list("deal").isEmpty());
    }

    private static List<String> keys(List<AiAssistantSkillDto> entries) {
        return entries.stream().map(AiAssistantSkillDto::key).toList();
    }
}
