package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.CompiledWorkflow;
import ooo.klae.connex.backend.services.WorkflowDraftCanonicalizer.CanonicalDraft;

@ExtendWith(MockitoExtension.class)
class WorkflowVersionProjectionTest {

    @Mock private RuleDefinitionCodec definitionCodec;
    @Mock private CompiledWorkflow compiled;

    @Test
    void invalidCompiledEntryReturnsTypedActionableError() {
        WorkflowVersionProjection projection = new WorkflowVersionProjection(definitionCodec);
        when(compiled.entryNodeId()).thenReturn("complete");
        when(compiled.node("complete")).thenReturn(new WorkflowNode.End("complete"));
        Workflow workflow = new Workflow();
        CanonicalDraft draft = new CanonicalDraft(
            "Workflow", null, "deal", "user", "{}", "{}", new byte[32]);

        BadRequestException exception = assertThrows(
            BadRequestException.class,
            () -> projection.project(workflow, draft, compiled));

        assertEquals(
            "Compiled workflow entry node must reference the trigger node",
            exception.getMessage());
    }

    @Test
    void invalidEnrollmentConfigurationReturnsTypedActionableError() {
        WorkflowVersionProjection projection = new WorkflowVersionProjection(definitionCodec);
        when(compiled.entryNodeId()).thenReturn("trigger");
        when(compiled.node("trigger")).thenReturn(
            new WorkflowNode.Trigger("trigger", new RuleTrigger()));
        when(compiled.enrollmentConditionNodeId()).thenReturn("enrollment");
        when(compiled.node("enrollment")).thenReturn(
            new WorkflowNode.Condition("enrollment", null));
        Workflow workflow = new Workflow();
        CanonicalDraft draft = new CanonicalDraft(
            "Workflow", null, "deal", "user", "{}", "{}", new byte[32]);

        BadRequestException exception = assertThrows(
            BadRequestException.class,
            () -> projection.project(workflow, draft, compiled));

        assertEquals(
            "Compiled workflow enrollment condition configuration is required",
            exception.getMessage());
    }
}
