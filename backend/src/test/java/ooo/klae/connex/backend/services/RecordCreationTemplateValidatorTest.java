package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.CustomFieldDefinition;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.dto.recordcreation.LocalizedTextDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationDefaultSpecDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateDefinitionDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateFieldDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateGroupDto;
import ooo.klae.connex.backend.exceptions.RecordCreationTemplateException;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.CustomFieldDefinitionMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.recordcreation.RecordCreationDefaultKind;
import ooo.klae.connex.backend.recordcreation.RecordCreationFieldRegistry;
import ooo.klae.connex.backend.recordcreation.RecordCreationRecordType;
import tools.jackson.databind.json.JsonMapper;

class RecordCreationTemplateValidatorTest {

    private final CustomFieldDefinitionMapper customFieldMapper = mock(CustomFieldDefinitionMapper.class);
    private final TagMapper tagMapper = mock(TagMapper.class);
    private final CompanyMapper companyMapper = mock(CompanyMapper.class);
    private final PersonMapper personMapper = mock(PersonMapper.class);
    private final PipelineMapper pipelineMapper = mock(PipelineMapper.class);
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final JsonMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    private RecordCreationTemplateValidator validator;

    @BeforeEach
    void setUp() {
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        validator = new RecordCreationTemplateValidator(
            new RecordCreationFieldRegistry(),
            customFieldMapper,
            tagMapper,
            companyMapper,
            personMapper,
            pipelineMapper,
            workspaceService,
            objectMapper);
    }

    @Test
    void canonicalizesLocalizedContentAndProducesStableHash() {
        var first = validator.validateAndCanonicalize(
            RecordCreationRecordType.person,
            new LocalizedTextDto("  Cafe\u0301  ", "  カフェ  "),
            new LocalizedTextDto(" ", " "),
            definition(field("name", false, null)));
        var second = validator.validateAndCanonicalize(
            RecordCreationRecordType.person,
            new LocalizedTextDto("Café", "カフェ"),
            null,
            definition(field("name", false, null)));

        assertEquals("Café", first.name().en());
        assertNull(first.description());
        assertEquals(first.definitionJson(), second.definitionJson());
        assertTrue(java.util.Arrays.equals(first.definitionHash(), second.definitionHash()));
    }

    @Test
    void rejectsDuplicateGroupAndFieldKeys() {
        RecordCreationTemplateException duplicateGroup = assertThrows(
            RecordCreationTemplateException.class,
            () -> validate(new RecordCreationTemplateDefinitionDto(1, List.of(
                group("same", field("name", false, null)),
                group("same", field("email", false, null))))));
        RecordCreationTemplateException duplicateField = assertThrows(
            RecordCreationTemplateException.class,
            () -> validate(new RecordCreationTemplateDefinitionDto(1, List.of(
                group("one", field("name", false, null)),
                group("two", field("name", false, null))))));

        assertEquals("TEMPLATE_DEFINITION_INVALID", duplicateGroup.error().code());
        assertEquals("TEMPLATE_DEFINITION_INVALID", duplicateField.error().code());
    }

    @Test
    void enforcesStructuralAndLocalizationLimits() {
        assertCode("TEMPLATE_DEFINITION_INVALID", () -> validate(
            new RecordCreationTemplateDefinitionDto(2, List.of(group("one", field("name", false, null))))));
        assertCode("TEMPLATE_DEFINITION_INVALID", () -> validator.validateAndCanonicalize(
            RecordCreationRecordType.person,
            new LocalizedTextDto("English", ""),
            null,
            definition(field("name", false, null))));
        assertCode("TEMPLATE_DEFINITION_INVALID", () -> validator.validateAndCanonicalize(
            RecordCreationRecordType.person,
            names(),
            null,
            new RecordCreationTemplateDefinitionDto(1, List.of(new RecordCreationTemplateGroupDto(
                "group",
                new LocalizedTextDto("x".repeat(81), "ラベル"),
                null,
                List.of(field("name", false, null)))))));
        List<RecordCreationTemplateFieldDto> twentyOne = java.util.stream.IntStream.rangeClosed(1, 21)
            .mapToObj(index -> field(index == 1 ? "name" : "custom:" + index, false, null))
            .toList();
        assertCode("TEMPLATE_DEFINITION_INVALID", () -> validate(
            new RecordCreationTemplateDefinitionDto(1, List.of(new RecordCreationTemplateGroupDto(
                "too-many", labels(), null, twentyOne)))));
    }

    @Test
    void enforcesEveryGroupAndFieldBoundary() {
        allowCustomFields("text");
        assertCode("TEMPLATE_DEFINITION_INVALID", () -> validate(
            new RecordCreationTemplateDefinitionDto(1, List.of())));
        List<RecordCreationTemplateGroupDto> nineGroups = java.util.stream.IntStream.range(0, 9)
            .mapToObj(index -> group(
                "group-" + index,
                field("custom:" + (index + 1), false, null)))
            .toList();
        assertCode("TEMPLATE_DEFINITION_INVALID", () -> validate(
            new RecordCreationTemplateDefinitionDto(1, nineGroups)));
        assertCode("TEMPLATE_DEFINITION_INVALID", () -> validate(
            new RecordCreationTemplateDefinitionDto(1, List.of(
                new RecordCreationTemplateGroupDto("empty", labels(), null, List.of())))));
        assertCode("TEMPLATE_DEFINITION_INVALID", () -> validate(
            new RecordCreationTemplateDefinitionDto(1, List.of(
                group("Invalid_Key", field("name", false, null))))));
        List<RecordCreationTemplateGroupDto> fortyOneFields = List.of(
            new RecordCreationTemplateGroupDto(
                "first", labels(), null, customFields(1, 20, null, null)),
            new RecordCreationTemplateGroupDto(
                "second", labels(), null, customFields(21, 20, null, null)),
            new RecordCreationTemplateGroupDto(
                "third", labels(), null, customFields(41, 1, null, null)));
        assertCode("TEMPLATE_DEFINITION_INVALID", () -> validate(
            new RecordCreationTemplateDefinitionDto(1, fortyOneFields)));
    }

    @Test
    void enforcesEveryLocalizedContentBoundaryAndParity() {
        assertCode("TEMPLATE_DEFINITION_INVALID", () -> validator.validateAndCanonicalize(
            RecordCreationRecordType.person,
            new LocalizedTextDto("x".repeat(129), "名前"),
            null,
            definition(field("name", false, null))));
        assertCode("TEMPLATE_DEFINITION_INVALID", () -> validator.validateAndCanonicalize(
            RecordCreationRecordType.person,
            names(),
            new LocalizedTextDto("x".repeat(513), "説明"),
            definition(field("name", false, null))));
        assertCode("TEMPLATE_DEFINITION_INVALID", () -> validate(
            new RecordCreationTemplateDefinitionDto(1, List.of(
                new RecordCreationTemplateGroupDto(
                    "basics", labels(), new LocalizedTextDto("x".repeat(241), "説明"),
                    List.of(field("name", false, null)))))));
        assertCode("TEMPLATE_DEFINITION_INVALID", () -> validate(definition(
            new RecordCreationTemplateFieldDto(
                "name", false, new LocalizedTextDto("x".repeat(241), "ヘルプ"), null, null))));
        assertCode("TEMPLATE_DEFINITION_INVALID", () -> validate(definition(
            new RecordCreationTemplateFieldDto(
                "name", false, null, new LocalizedTextDto("x".repeat(161), "例"), null))));
        assertCode("TEMPLATE_DEFINITION_INVALID", () -> validate(definition(
            new RecordCreationTemplateFieldDto(
                "name", false, new LocalizedTextDto("Help", ""), null, null))));
    }

    @Test
    void rejectsCanonicalJsonBeyondTheUtf8ByteLimit() {
        allowCustomFields("text");
        LocalizedTextDto help = new LocalizedTextDto(
            "\uD83D\uDE00".repeat(240), "\uD83D\uDE00".repeat(240));
        LocalizedTextDto placeholder = new LocalizedTextDto(
            "\uD83D\uDE00".repeat(160), "\uD83D\uDE00".repeat(160));
        RecordCreationTemplateDefinitionDto oversized = new RecordCreationTemplateDefinitionDto(
            1,
            List.of(
                new RecordCreationTemplateGroupDto(
                    "first", labels(), null, customFields(1, 20, help, placeholder)),
                new RecordCreationTemplateGroupDto(
                    "second", labels(), null, customFields(21, 20, help, placeholder))));

        assertCode("TEMPLATE_DEFINITION_INVALID", () -> validate(oversized));
    }

    @Test
    void rejectsCrossTypeAndUnavailableCustomFields() {
        assertCode("TEMPLATE_DEFINITION_INVALID", () -> validate(definition(
            field("pipeline", false, null))));
        assertCode("TEMPLATE_DEFINITION_INVALID", () -> validate(definition(
            field("custom:42", false, null))));

        CustomFieldDefinition wrongType = custom(42, "deal", "text", false);
        when(customFieldMapper.getById(7, 42)).thenReturn(wrongType);
        assertCode("TEMPLATE_DEFINITION_INVALID", () -> validate(definition(
            field("custom:42", false, null))));

        wrongType.setEntityType("person");
        wrongType.setArchived(true);
        assertCode("TEMPLATE_DEFINITION_INVALID", () -> validate(definition(
            field("custom:42", false, null))));
    }

    @Test
    void validatesProtectedFieldsAndApprovedDefaultKinds() {
        assertCode("TEMPLATE_DEFINITION_INVALID", () -> validate(definition(
            field("consentStatus", true, null))));
        assertCode("TEMPLATE_DEFAULT_FORBIDDEN", () -> validate(definition(
            field("owner", false, new RecordCreationDefaultSpecDto(
                RecordCreationDefaultKind.literal_reference,
                null, null, null, null, 9, null)))));
        assertCode("TEMPLATE_DEFAULT_FORBIDDEN", () -> validate(definition(
            field("leadSource", false, new RecordCreationDefaultSpecDto(
                RecordCreationDefaultKind.literal_string,
                "BUSINESS_CARD", null, null, null, null, null)))));
        assertCode("TEMPLATE_DEFAULT_FORBIDDEN", () -> validate(definition(
            field("email", false, new RecordCreationDefaultSpecDto(
                RecordCreationDefaultKind.literal_string,
                "not-an-email", null, null, null, null, null)))));
    }

    @Test
    void canonicalizesTagDefaultsAndChecksEveryReference() {
        when(tagMapper.exists(7, 2)).thenReturn(true);
        when(tagMapper.exists(7, 4)).thenReturn(true);
        var validated = validator.validateAndCanonicalize(
            RecordCreationRecordType.person,
            names(),
            null,
            definition(field("tags", false, new RecordCreationDefaultSpecDto(
                RecordCreationDefaultKind.literal_references,
                null, null, null, null, null, List.of(4, 2, 4)))));

        assertEquals(
            List.of(2, 4),
            validated.definition().groups().getFirst().fields().getFirst()
                .defaultSpec().referenceIds());

        when(tagMapper.exists(7, 4)).thenReturn(false);
        assertCode("TEMPLATE_DEFAULT_FORBIDDEN", () -> validate(definition(
            field("tags", false, new RecordCreationDefaultSpecDto(
                RecordCreationDefaultKind.literal_references,
                null, null, null, null, null, List.of(4))))));
    }

    @Test
    void validatesPipelineAndStagePairing() {
        Pipeline pipeline = new Pipeline();
        pipeline.setId(3);
        Pipeline other = new Pipeline();
        other.setId(4);
        Stage stage = new Stage();
        stage.setId(5);
        stage.setPipeline(other);
        when(pipelineMapper.pipelineExists(7, 3)).thenReturn(true);
        when(pipelineMapper.getVisibleStageById(7, 5)).thenReturn(stage);
        RecordCreationTemplateDefinitionDto mismatched = new RecordCreationTemplateDefinitionDto(
            1,
            List.of(group(
                "basics",
                field("pipeline", false, reference(3)),
                field("stage", false, reference(5)))));

        assertCode("TEMPLATE_DEFAULT_FORBIDDEN", () -> validator.validateAndCanonicalize(
            RecordCreationRecordType.deal,
            names(),
            null,
            mismatched));
    }

    @Test
    void rejectsMismatchedDefaultPayloadsAndNumericRange() {
        RecordCreationDefaultSpecDto extraPayload = new RecordCreationDefaultSpecDto(
            RecordCreationDefaultKind.literal_number,
            "extra", BigDecimal.ONE, null, null, null, null);
        assertCode("TEMPLATE_DEFAULT_FORBIDDEN", () -> validator.validateAndCanonicalize(
            RecordCreationRecordType.deal,
            names(),
            null,
            definition(field("value", false, extraPayload))));
        RecordCreationDefaultSpecDto negativeValue = new RecordCreationDefaultSpecDto(
            RecordCreationDefaultKind.literal_number,
            null, new BigDecimal("-1"), null, null, null, null);
        assertCode("TEMPLATE_DEFAULT_FORBIDDEN", () -> validator.validateAndCanonicalize(
            RecordCreationRecordType.deal,
            names(),
            null,
            definition(field("value", false, negativeValue))));
    }

    private void validate(RecordCreationTemplateDefinitionDto definition) {
        validator.validateAndCanonicalize(RecordCreationRecordType.person, names(), null, definition);
    }

    private static RecordCreationTemplateDefinitionDto definition(
            RecordCreationTemplateFieldDto... fields) {
        return new RecordCreationTemplateDefinitionDto(1, List.of(group("basics", fields)));
    }

    private static RecordCreationTemplateGroupDto group(
            String key,
            RecordCreationTemplateFieldDto... fields) {
        return new RecordCreationTemplateGroupDto(key, labels(), null, List.of(fields));
    }

    private static RecordCreationTemplateFieldDto field(
            String key,
            boolean required,
            RecordCreationDefaultSpecDto defaultSpec) {
        return new RecordCreationTemplateFieldDto(key, required, null, null, defaultSpec);
    }

    private static List<RecordCreationTemplateFieldDto> customFields(
            int first,
            int count,
            LocalizedTextDto help,
            LocalizedTextDto placeholder) {
        return java.util.stream.IntStream.range(first, first + count)
            .mapToObj(id -> new RecordCreationTemplateFieldDto(
                "custom:" + id, false, help, placeholder, null))
            .toList();
    }

    private void allowCustomFields(String fieldType) {
        when(customFieldMapper.getById(eq(7), anyInt())).thenAnswer(invocation ->
            custom(invocation.getArgument(1), "person", fieldType, false));
    }

    private static RecordCreationDefaultSpecDto reference(int id) {
        return new RecordCreationDefaultSpecDto(
            RecordCreationDefaultKind.literal_reference,
            null, null, null, null, id, null);
    }

    private static LocalizedTextDto names() {
        return new LocalizedTextDto("Template", "テンプレート");
    }

    private static LocalizedTextDto labels() {
        return new LocalizedTextDto("Basics", "基本情報");
    }

    private static CustomFieldDefinition custom(
            int id,
            String entityType,
            String fieldType,
            boolean required) {
        CustomFieldDefinition definition = new CustomFieldDefinition();
        definition.setId(id);
        definition.setEntityType(entityType);
        definition.setFieldType(fieldType);
        definition.setLabel("Custom");
        definition.setRequired(required);
        return definition;
    }

    private static void assertCode(String code, Runnable work) {
        RecordCreationTemplateException exception = assertThrows(
            RecordCreationTemplateException.class, work::run);
        assertEquals(code, exception.error().code());
    }
}
