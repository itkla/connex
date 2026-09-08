package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.WorkflowDiagnosticCode;
import ooo.klae.connex.backend.dto.WorkflowInputDefinition;
import ooo.klae.connex.backend.dto.WorkflowInputType;
import ooo.klae.connex.backend.exceptions.WorkflowDefinitionValidationException;

@ExtendWith(MockitoExtension.class)
class WorkflowInputResolverTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Mock private WorkspaceService workspaceService;

    private WorkflowInputResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new WorkflowInputResolver(workspaceService, JSON);
    }

    @Test
    void resolvesSuppliedAndDefaultValuesWithMemberDisclosure() {
        User member = new User();
        member.setId(19);
        member.setDisplayName("Aiko Ito");
        when(workspaceService.getMembers(7)).thenReturn(List.of(member));
        List<WorkflowInputDefinition> inputs = List.of(
            input("assignee", WorkflowInputType.USER, true, null),
            input("note", WorkflowInputType.TEXT, true, JSON.valueToTree("Default note")),
            input("dueDate", WorkflowInputType.DATE, false, null));

        WorkflowInputResolver.Resolved result = resolver.resolve(
            7,
            inputs,
            Map.of(
                "assignee", JSON.valueToTree(19),
                "dueDate", JSON.valueToTree("2026-09-15")));

        assertEquals(19, result.values().get("assignee").intValue());
        assertEquals("Default note", result.values().get("note").textValue());
        assertEquals("Aiko Ito", result.disclosure().getFirst().displayValue());
        assertEquals("supplied", result.disclosure().getFirst().source());
        assertEquals("default", result.disclosure().get(1).source());
    }

    @Test
    void rejectsUnknownBlankRequiredAndNonCanonicalDateValues() {
        when(workspaceService.getMembers(7)).thenReturn(List.of());
        WorkflowDefinitionValidationException unknown = assertThrows(
            WorkflowDefinitionValidationException.class,
            () -> resolver.resolve(
                7,
                List.of(input("note", WorkflowInputType.TEXT, false, null)),
                Map.of("other", JSON.valueToTree("value"))));
        assertEquals(WorkflowDiagnosticCode.INPUT_UNKNOWN, unknown.diagnostic().code());

        WorkflowDefinitionValidationException blank = assertThrows(
            WorkflowDefinitionValidationException.class,
            () -> resolver.resolve(
                7,
                List.of(input("note", WorkflowInputType.TEXT, true, null)),
                Map.of("note", JSON.valueToTree("   "))));
        assertEquals(WorkflowDiagnosticCode.INPUT_REQUIRED, blank.diagnostic().code());

        WorkflowDefinitionValidationException date = assertThrows(
            WorkflowDefinitionValidationException.class,
            () -> resolver.resolve(
                7,
                List.of(input("dueDate", WorkflowInputType.DATE, true, null)),
                Map.of("dueDate", JSON.valueToTree("999-09-15"))));
        assertEquals(WorkflowDiagnosticCode.INPUT_TYPE_INVALID, date.diagnostic().code());
    }

    private static WorkflowInputDefinition input(
            String key,
            WorkflowInputType type,
            boolean required,
            JsonNode defaultValue) {
        return new WorkflowInputDefinition(key, key, type, required, defaultValue);
    }
}
