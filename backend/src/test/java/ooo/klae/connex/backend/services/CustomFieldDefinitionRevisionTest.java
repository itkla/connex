package ooo.klae.connex.backend.services;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import ooo.klae.connex.backend.beans.CustomFieldDefinition;
import ooo.klae.connex.backend.beans.RecordCreationTemplateSet;
import ooo.klae.connex.backend.mappers.CustomFieldDefinitionMapper;
import ooo.klae.connex.backend.mappers.RecordCreationTemplateMapper;
import ooo.klae.connex.backend.tenant.Permission;

class CustomFieldDefinitionRevisionTest {
    @Test
    void createLocksAuthorizationAndAffectedSetBeforeSchemaWriteAndRevisionAdvance() {
        CustomFieldDefinitionMapper definitionMapper = mock(CustomFieldDefinitionMapper.class);
        RecordCreationTemplateMapper templateMapper = mock(RecordCreationTemplateMapper.class);
        AuditService auditService = mock(AuditService.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(workspaceService.getCurrentUserId()).thenReturn(11);
        RecordCreationTemplateSet set = new RecordCreationTemplateSet();
        set.setRevision(3);
        when(templateMapper.getSetForUpdate(7, "person")).thenReturn(set);
        when(templateMapper.advanceSetRevision(7, "person", 3)).thenReturn(1);
        CustomFieldDefinitionService service = new CustomFieldDefinitionService(
            definitionMapper,
            templateMapper,
            auditService,
            workspaceService,
            JsonMapper.builder().findAndAddModules().build());
        CustomFieldDefinition definition = new CustomFieldDefinition();
        definition.setEntityType("person");
        definition.setFieldType("text");
        definition.setFieldKey("guided_revision");
        definition.setLabel("Guided revision");

        service.create(definition, null);

        var order = inOrder(workspaceService, templateMapper, definitionMapper);
        order.verify(workspaceService).lockAndRequirePermissions(
            7, Map.of(11, Set.of(Permission.CUSTOM_FIELD_MANAGE)));
        order.verify(templateMapper).insertSetIfAbsent(7, "person");
        order.verify(templateMapper).getSetForUpdate(7, "person");
        order.verify(definitionMapper).getByKey(7, "person", "guided_revision");
        order.verify(definitionMapper).insert(definition);
        order.verify(templateMapper).advanceSetRevision(7, "person", 3);
    }
}
