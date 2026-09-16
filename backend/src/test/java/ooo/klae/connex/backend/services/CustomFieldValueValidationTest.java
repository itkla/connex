package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.CustomFieldDefinition;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.CustomFieldDefinitionMapper;
import ooo.klae.connex.backend.mappers.CustomFieldValueMapper;

@ExtendWith(MockitoExtension.class)
class CustomFieldValueValidationTest {
    @Mock private CustomFieldValueMapper valueMapper;
    @Mock private CustomFieldDefinitionMapper definitionMapper;
    @Mock private CustomFieldDefinitionService definitionService;
    @Mock private WorkspaceService workspaceService;
    @InjectMocks private CustomFieldValueService service;

    @Test
    void rejectsExtremeNumericExponentAsBadRequest() {
        CustomFieldDefinition definition = new CustomFieldDefinition();
        definition.setEntityType("deal");
        definition.setFieldType("number");
        definition.setLabel("Amount");

        BadRequestException error = assertThrows(BadRequestException.class,
            () -> service.validateValue(definition, "1E2147483647"));

        assertEquals("'Amount' is out of range", error.getMessage());
    }
}
