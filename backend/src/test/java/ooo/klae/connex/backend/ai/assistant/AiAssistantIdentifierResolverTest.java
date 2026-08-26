package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.masking.MaskingEngine;
import ooo.klae.connex.backend.beans.AiAssistantIdentifierMention;
import ooo.klae.connex.backend.mappers.AiAssistantIdentifierMapper;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlAccess;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlOperations.WorkspaceScope;
import ooo.klae.connex.backend.services.WorkspaceService;

class AiAssistantIdentifierResolverTest {
    @Test
    void resolveUsesOneGloballyBoundedLookupAndSeedsEveryMatchedKind() {
        AiAssistantIdentifierMapper mapper = mock(AiAssistantIdentifierMapper.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        OrganizationWorkspaceScopeControlAccess workspaceScopeControlAccess =
                mock(OrganizationWorkspaceScopeControlAccess.class);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(workspaceScopeControlAccess.getForWorkspace(7))
                .thenReturn(new WorkspaceScope(2, List.of(7), "[7]"));
        when(mapper.findMentionedRecords(7, "[7]", "Ask Ada about Acme Renewal", 21))
                .thenReturn(List.of(
                        mention("person", 11, "Ada"),
                        mention("company", 12, "Acme"),
                        mention("deal", 13, "Acme Renewal")));
        AiAssistantIdentifierResolver resolver = new AiAssistantIdentifierResolver(
                mapper, workspaceService, workspaceScopeControlAccess);

        AiAssistantIdentifierResolver.Resolution resolution =
                resolver.resolve("Ask Ada about Acme Renewal");
        MaskingContext context = new MaskingContext();
        resolver.seed(resolution, context);
        String masked = MaskingEngine.maskFreeText(
                "Ask Ada about Acme Renewal", context);

        assertEquals(3, resolution.resources().size());
        assertFalse(masked.contains("Ada"));
        assertFalse(masked.contains("Acme"));
        verify(mapper).findMentionedRecords(7, "[7]", "Ask Ada about Acme Renewal", 21);
    }

    @Test
    void resolveRejectsAResultBeyondTheGlobalTurnLimit() {
        AiAssistantIdentifierMapper mapper = mock(AiAssistantIdentifierMapper.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        OrganizationWorkspaceScopeControlAccess workspaceScopeControlAccess =
                mock(OrganizationWorkspaceScopeControlAccess.class);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(workspaceScopeControlAccess.getForWorkspace(7))
                .thenReturn(new WorkspaceScope(2, List.of(7), "[7]"));
        List<AiAssistantIdentifierMention> overflow = IntStream.rangeClosed(1, 21)
                .mapToObj(id -> mention("person", id, "Person " + id))
                .toList();
        when(mapper.findMentionedRecords(7, "[7]", "many names", 21)).thenReturn(overflow);
        AiAssistantIdentifierResolver resolver = new AiAssistantIdentifierResolver(
                mapper, workspaceService, workspaceScopeControlAccess);

        AiAssistantLoopException exception = assertThrows(
                AiAssistantLoopException.class,
                () -> resolver.resolve("many names"));

        assertEquals("identifier_limit_exceeded", exception.detailReason());
    }

    private static AiAssistantIdentifierMention mention(String kind, int id, String value) {
        AiAssistantIdentifierMention mention = new AiAssistantIdentifierMention();
        mention.setKind(kind);
        mention.setId(id);
        mention.setValue(value);
        return mention;
    }
}
