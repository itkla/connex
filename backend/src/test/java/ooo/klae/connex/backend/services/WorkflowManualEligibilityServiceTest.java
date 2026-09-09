package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.WorkflowManualOptionView;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.dto.WorkflowDefinition;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.tenant.Permission;

@ExtendWith(MockitoExtension.class)
class WorkflowManualEligibilityServiceTest {

    @Mock private WorkflowDefinitionValidator definitionValidator;
    @Mock private WorkflowRuntimeProperties runtimeProperties;
    @Mock private WorkflowRecordGuard recordGuard;
    @Mock private WorkspaceService workspaceService;
    @Mock private SystemActor systemActor;

    private WorkflowManualEligibilityService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowManualEligibilityService(
            definitionValidator,
            runtimeProperties,
            recordGuard,
            workspaceService,
            systemActor);
    }

    @Test
    void systemEligibilityUsesFixedPermissionsAndRequiresActiveCreatorAttribution() {
        WorkflowManualOptionView candidate = candidate();
        WorkflowDefinition definition = definition();
        User actor = new User();
        actor.setId(999);
        when(runtimeProperties.enabled()).thenReturn(true);
        when(systemActor.user()).thenReturn(actor);
        when(systemActor.permissions()).thenReturn(Set.of(Permission.TASK_CREATE));
        when(definitionValidator.validateForMutation("company", "system", definition))
            .thenReturn(Set.of(Permission.TASK_CREATE));
        when(workspaceService.permissionsFor(7, 41))
            .thenReturn(Set.of(Permission.TASK_CREATE));
        when(workspaceService.getRole(7, 17)).thenReturn("admin");

        WorkflowManualEligibilityService.Evaluation available = service.evaluate(
            7, 41, candidate, definition, null);

        assertTrue(available.reasons().isEmpty());
        assertEquals(999, available.actorUserId());
    }

    @Test
    void systemEligibilityDoesNotAdvertiseAWorkflowAfterCreatorRemoval() {
        WorkflowManualOptionView candidate = candidate();
        WorkflowDefinition definition = definition();
        User actor = new User();
        actor.setId(999);
        when(runtimeProperties.enabled()).thenReturn(true);
        when(systemActor.user()).thenReturn(actor);
        when(systemActor.permissions()).thenReturn(Set.of(Permission.TASK_CREATE));
        when(definitionValidator.validateForMutation("company", "system", definition))
            .thenReturn(Set.of(Permission.TASK_CREATE));
        when(workspaceService.permissionsFor(7, 41))
            .thenReturn(Set.of(Permission.TASK_CREATE));

        WorkflowManualEligibilityService.Evaluation unavailable = service.evaluate(
            7, 41, candidate, definition, null);

        assertTrue(unavailable.reasons().contains("actor_unavailable"));
    }

    private static WorkflowManualOptionView candidate() {
        WorkflowManualOptionView candidate = new WorkflowManualOptionView();
        candidate.setWorkflowId(11);
        candidate.setEnabled(true);
        candidate.setRuntimeOwner("canonical");
        candidate.setWorkflowVersionId(19);
        candidate.setRecordType("company");
        candidate.setExecutionMode("system");
        candidate.setCreatedById(17);
        return candidate;
    }

    private static WorkflowDefinition definition() {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("manual");
        return new WorkflowDefinition(
            2,
            "trigger",
            List.of(new WorkflowNode.Trigger("trigger", trigger)),
            List.of(),
            List.of(),
            null,
            null);
    }
}
