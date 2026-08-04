package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.WorkflowDiagnosticCode;
import ooo.klae.connex.backend.dto.WorkflowDiagnosticDto;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.tenant.Permission;

@ExtendWith(MockitoExtension.class)
class WorkflowActionGuardTest {

    @Mock private WorkspaceService workspaceService;
    @Mock private RuleDefinitionValidator definitionValidator;
    @Mock private TagMapper tagMapper;
    @Mock private PipelineMapper pipelineMapper;
    @Mock private DealMapper dealMapper;

    @InjectMocks private WorkflowActionGuard guard;

    @Test
    void removeTagFailsClosedWhenThePublishedTagReferenceWasDeleted() {
        RuleAction action = new RuleAction();
        action.setType("remove_tag");
        action.setTagId(29);
        when(definitionValidator.actionPermission(action, "person"))
            .thenReturn(Permission.PERSON_UPDATE);
        when(workspaceService.permissionsFor(7, 17))
            .thenReturn(Set.of(Permission.PERSON_UPDATE));
        when(tagMapper.getTagById(7, 29)).thenReturn(null);

        WorkflowDiagnosticDto diagnostic = guard.blocker(
            7, 17, "person", 41, "remove-tag", action);

        assertEquals(WorkflowDiagnosticCode.ACTION_TAG_UNAVAILABLE, diagnostic.code());
    }
}
