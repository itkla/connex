package ooo.klae.connex.backend.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import ooo.klae.connex.backend.beans.CustomFieldDefinition;

class CustomFieldDefinitionDtoTest {
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void closeValidatorFactory() {
        FACTORY.close();
    }

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

    @Test
    void invalidFieldTypeUsesHumanValidationMessage() {
        CustomFieldDefinitionDto dto = new CustomFieldDefinitionDto();
        dto.setEntityType("company");
        dto.setFieldKey("priority_tier");
        dto.setLabel("Priority Tier");
        dto.setFieldType("unsupported");

        Set<ConstraintViolation<CustomFieldDefinitionDto>> violations = VALIDATOR.validate(dto);

        assertEquals(1, violations.size());
        ConstraintViolation<CustomFieldDefinitionDto> violation = violations.iterator().next();
        assertEquals("fieldType", violation.getPropertyPath().toString());
        assertEquals("Choose a supported field type.", violation.getMessage());
    }

    @Test
    void invalidDataClassificationUsesHumanValidationMessage() {
        CustomFieldDefinitionDto dto = new CustomFieldDefinitionDto();
        dto.setEntityType("company");
        dto.setFieldKey("priority_tier");
        dto.setLabel("Priority Tier");
        dto.setFieldType("text");
        dto.setDataClassification("specialCare");

        Set<ConstraintViolation<CustomFieldDefinitionDto>> violations = VALIDATOR.validate(dto);

        assertEquals(1, violations.size());
        ConstraintViolation<CustomFieldDefinitionDto> violation = violations.iterator().next();
        assertEquals("dataClassification", violation.getPropertyPath().toString());
        assertEquals("Choose how sensitive this field's data is.", violation.getMessage());
    }
}
