package ooo.klae.connex.backend.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.CustomFieldDefinition;

class CustomFieldDefinitionDtoTest {

    @Test
    void toBean_defaultsOmittedFlags() {
        CustomFieldDefinitionDto dto = new CustomFieldDefinitionDto();
        dto.setEntityType("company");
        dto.setFieldKey("priority_tier");
        dto.setLabel("Priority Tier");
        dto.setFieldType("text");

        CustomFieldDefinition bean = dto.toBean();

        assertFalse(bean.isRequired());
        assertEquals(0, bean.getPosition());
        assertFalse(bean.isArchived());
    }

    @Test
    void toBean_preservesProvidedValues() {
        CustomFieldDefinitionDto dto = new CustomFieldDefinitionDto();
        dto.setEntityType("deal");
        dto.setFieldKey("band");
        dto.setLabel("Band");
        dto.setFieldType("number");
        dto.setRequired(true);
        dto.setPosition(3);
        dto.setArchived(true);

        CustomFieldDefinition bean = dto.toBean();

        assertTrue(bean.isRequired());
        assertEquals(3, bean.getPosition());
        assertTrue(bean.isArchived());
    }
}
