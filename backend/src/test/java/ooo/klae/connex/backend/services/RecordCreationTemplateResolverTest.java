package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.CustomFieldDefinition;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.RecordCreationTemplate;
import ooo.klae.connex.backend.beans.RecordCreationTemplateVersion;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.dto.recordcreation.LocalizedTextDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationContextDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationDefaultSpecDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateDefinitionDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateFieldDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateGroupDto;
import ooo.klae.connex.backend.dto.recordcreation.ResolvedCreationFieldDto;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.CustomFieldDefinitionMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.recordcreation.RecordCreationDefaultKind;
import ooo.klae.connex.backend.recordcreation.RecordCreationDefaultOrigin;
import ooo.klae.connex.backend.recordcreation.RecordCreationFieldRegistry;
import ooo.klae.connex.backend.recordcreation.RecordCreationRecordType;
import ooo.klae.connex.backend.recordcreation.RecordCreationTemplateAvailability;
import tools.jackson.databind.json.JsonMapper;

class RecordCreationTemplateResolverTest {

    private final RecordCreationTemplateValidator validator = mock(RecordCreationTemplateValidator.class);
    private final CustomFieldDefinitionMapper customFieldMapper = mock(CustomFieldDefinitionMapper.class);
    private final UserCalendarService userCalendarService = mock(UserCalendarService.class);
    private final CompanyMapper companyMapper = mock(CompanyMapper.class);
    private final PersonMapper personMapper = mock(PersonMapper.class);
    private final PipelineMapper pipelineMapper = mock(PipelineMapper.class);
    private final TagMapper tagMapper = mock(TagMapper.class);
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);

    private RecordCreationTemplateResolver resolver;

    @BeforeEach
    void setUp() {
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(workspaceService.getCurrentUserId()).thenReturn(11);
        when(customFieldMapper.getByEntityType(7, "person")).thenReturn(List.of());
        when(customFieldMapper.getByEntityType(7, "company")).thenReturn(List.of());
        when(customFieldMapper.getByEntityType(7, "deal")).thenReturn(List.of());
        when(pipelineMapper.getAllPipelines(7)).thenReturn(List.of());
        when(pipelineMapper.getAllStages(7)).thenReturn(List.of());
        when(tagMapper.getAllTags(7)).thenReturn(List.of());
        resolver = new RecordCreationTemplateResolver(
            new RecordCreationFieldRegistry(),
            userCalendarService,
            validator,
            customFieldMapper,
            companyMapper,
            personMapper,
            pipelineMapper,
            tagMapper,
            workspaceService,
            JsonMapper.builder().findAndAddModules().build());
        when(userCalendarService.today()).thenReturn(LocalDate.parse("2026-08-31"));
    }

    @Test
    void systemPersonPresetKeepsOwnerConsentAndProvenanceVisible() {
        var resolved = resolver.resolveSystem(RecordCreationRecordType.person, null);

        assertEquals("system:person:standard", resolved.id());
        assertEquals(RecordCreationTemplateAvailability.available, resolved.availability());
        assertEquals(List.of("basics", "trust"), resolved.groups().stream().map(group -> group.key()).toList());
        assertNotNull(field(resolved, "owner").defaultValue());
        assertEquals(11, field(resolved, "owner").defaultValue().intValue());
        assertEquals(RecordCreationDefaultOrigin.policy, field(resolved, "owner").defaultOrigin());
        assertTrue(field(resolved, "consentStatus").protectedField());
        assertTrue(field(resolved, "leadSource").options().stream()
            .filter(option -> option.value().equals("BUSINESS_CARD") || option.value().equals("IMPORT"))
            .allMatch(option -> option.disabled()));
    }

    @Test
    void insertsRequiredAndTrustGroupsWithoutChangingConfiguredOrder() {
        CustomFieldDefinition requiredCustom = custom(42, "person", "text", true, false);
        when(customFieldMapper.getByEntityType(7, "person")).thenReturn(List.of(requiredCustom));
        RecordCreationTemplateDefinitionDto definition = definition("main", "email");
        when(validator.parseDefinition("stored")).thenReturn(definition);

        var resolved = resolver.resolveWorkspace(root(9, "person"), version("stored"), null);

        assertEquals("required", resolved.groups().getFirst().key());
        assertEquals("main", resolved.groups().get(1).key());
        assertEquals("trust", resolved.groups().getLast().key());
        assertTrue(resolved.groups().getFirst().fields().stream()
            .map(ResolvedCreationFieldDto::key)
            .toList()
            .containsAll(List.of("name", "owner", "consentStatus", "custom:42")));
        assertEquals("email", resolved.groups().get(1).fields().getFirst().key());
    }

    @Test
    void verifiedRelatedCompanyOverridesTheVisibleCompanyDefault() {
        when(companyMapper.exists(7, 55)).thenReturn(true);

        var resolved = resolver.resolveSystem(
            RecordCreationRecordType.person,
            new RecordCreationContextDto(55));

        assertEquals(55, field(resolved, "company").defaultValue().intValue());
        assertEquals(RecordCreationDefaultOrigin.context, field(resolved, "company").defaultOrigin());
    }

    @Test
    void dealPresetUsesDeterministicMaterialPolicyDefaults() {
        Pipeline later = pipeline(9, "Later");
        Pipeline first = pipeline(3, "First");
        Stage secondStage = stage(8, first, 1, "Second");
        Stage firstStage = stage(7, first, 0, "First stage");
        Tag tag = new Tag();
        tag.setId(4);
        tag.setName("Priority");
        when(pipelineMapper.getAllPipelines(7)).thenReturn(List.of(later, first));
        when(pipelineMapper.getAllStages(7)).thenReturn(List.of(secondStage, firstStage));
        when(tagMapper.getAllTags(7)).thenReturn(List.of(tag));

        var resolved = resolver.resolveSystem(RecordCreationRecordType.deal, null);

        assertEquals(0, field(resolved, "value").defaultValue().decimalValue().intValue());
        assertEquals("USD", field(resolved, "currency").defaultValue().textValue());
        assertEquals(3, field(resolved, "pipeline").defaultValue().intValue());
        assertEquals(7, field(resolved, "stage").defaultValue().intValue());
        assertEquals(11, field(resolved, "owner").defaultValue().intValue());
        assertEquals(List.of("3", "9"), field(resolved, "pipeline").options().stream()
            .map(option -> option.value()).toList());
    }

    @Test
    void omittedPersonAndCompanyOwnersResolveToTheCurrentActorPolicy() {
        var person = resolver.resolvePreview(
            "workspace:person",
            RecordCreationRecordType.person,
            names(),
            null,
            definition("basics", "name"),
            null);
        var company = resolver.resolvePreview(
            "workspace:company",
            RecordCreationRecordType.company,
            names(),
            null,
            definition("basics", "name"),
            null);

        assertEquals(11, field(person, "owner").defaultValue().intValue());
        assertEquals(RecordCreationDefaultOrigin.policy, field(person, "owner").defaultOrigin());
        assertEquals(11, field(company, "owner").defaultValue().intValue());
        assertEquals(RecordCreationDefaultOrigin.policy, field(company, "owner").defaultOrigin());
    }

    @Test
    void pipelineOnlyDefaultDerivesAStageFromThatPipeline() {
        Pipeline first = pipeline(3, "First");
        Pipeline configured = pipeline(9, "Configured");
        Stage firstStage = stage(7, first, 0, "First stage");
        Stage configuredStage = stage(12, configured, 0, "Configured stage");
        when(pipelineMapper.getAllPipelines(7)).thenReturn(List.of(first, configured));
        when(pipelineMapper.getAllStages(7)).thenReturn(List.of(firstStage, configuredStage));

        var resolved = resolver.resolvePreview(
            "workspace:pipeline-only",
            RecordCreationRecordType.deal,
            names(),
            null,
            definitionWithFields(field("pipeline", reference(9))),
            null);

        assertEquals(RecordCreationTemplateAvailability.available, resolved.availability());
        assertEquals(9, field(resolved, "pipeline").defaultValue().intValue());
        assertEquals(12, field(resolved, "stage").defaultValue().intValue());
        assertEquals(RecordCreationDefaultOrigin.policy, field(resolved, "stage").defaultOrigin());
    }

    @Test
    void stageOnlyDefaultDerivesItsCurrentPipeline() {
        Pipeline first = pipeline(3, "First");
        Pipeline configured = pipeline(9, "Configured");
        Stage firstStage = stage(7, first, 0, "First stage");
        Stage configuredStage = stage(12, configured, 0, "Configured stage");
        when(pipelineMapper.getAllPipelines(7)).thenReturn(List.of(first, configured));
        when(pipelineMapper.getAllStages(7)).thenReturn(List.of(firstStage, configuredStage));

        var resolved = resolver.resolvePreview(
            "workspace:stage-only",
            RecordCreationRecordType.deal,
            names(),
            null,
            definitionWithFields(field("stage", reference(12))),
            null);

        assertEquals(RecordCreationTemplateAvailability.available, resolved.availability());
        assertEquals(9, field(resolved, "pipeline").defaultValue().intValue());
        assertEquals(12, field(resolved, "stage").defaultValue().intValue());
        assertEquals(RecordCreationDefaultOrigin.policy, field(resolved, "pipeline").defaultOrigin());
    }

    @Test
    void mismatchedOrMissingCurrentDealPairFailsClosed() {
        Pipeline first = pipeline(3, "First");
        Pipeline other = pipeline(9, "Other");
        Stage stage = stage(12, other, 0, "Other stage");
        when(pipelineMapper.getAllPipelines(7)).thenReturn(List.of(first, other));
        when(pipelineMapper.getAllStages(7)).thenReturn(List.of(stage));

        var mismatched = resolver.resolvePreview(
            "workspace:mismatch",
            RecordCreationRecordType.deal,
            names(),
            null,
            definitionWithFields(
                field("pipeline", reference(3)),
                field("stage", reference(12))),
            null);
        var missing = resolver.resolvePreview(
            "workspace:missing",
            RecordCreationRecordType.deal,
            names(),
            null,
            definitionWithFields(field("pipeline", reference(99))),
            null);

        assertEquals(RecordCreationTemplateAvailability.unavailable, mismatched.availability());
        assertEquals(RecordCreationTemplateAvailability.unavailable, missing.availability());
        assertTrue(mismatched.warnings().stream()
            .allMatch(warning -> warning.code().equals("TEMPLATE_FIELD_UNAVAILABLE")));
    }

    @Test
    void staleTagAndCustomSelectDefaultsFailClosed() {
        CustomFieldDefinition custom = custom(9, "person", "select", false, false);
        custom.setOptionsJson("[{\"key\":\"current\",\"label\":\"Current\"}]");
        when(customFieldMapper.getByEntityType(7, "person")).thenReturn(List.of(custom));

        var tagDefault = resolver.resolvePreview(
            "workspace:tag",
            RecordCreationRecordType.person,
            names(),
            null,
            definitionWithFields(field("tags", references(4))),
            null);
        var selectDefault = resolver.resolvePreview(
            "workspace:select",
            RecordCreationRecordType.person,
            names(),
            null,
            definitionWithFields(field("custom:9", stringDefault("removed"))),
            null);

        assertEquals(RecordCreationTemplateAvailability.unavailable, tagDefault.availability());
        assertEquals(RecordCreationTemplateAvailability.unavailable, selectDefault.availability());
        assertEquals("tags", tagDefault.warnings().getFirst().fieldKey());
        assertEquals("custom:9", selectDefault.warnings().getFirst().fieldKey());
    }

    @Test
    void staleCompanyAndPersonReferencesFailClosed() {
        var company = resolver.resolvePreview(
            "workspace:company-reference",
            RecordCreationRecordType.person,
            names(),
            null,
            definitionWithFields(field("company", reference(55))),
            null);
        var person = resolver.resolvePreview(
            "workspace:person-reference",
            RecordCreationRecordType.person,
            names(),
            null,
            definitionWithFields(field("referrerPerson", reference(66))),
            null);

        assertEquals(RecordCreationTemplateAvailability.unavailable, company.availability());
        assertEquals(RecordCreationTemplateAvailability.unavailable, person.availability());
        assertEquals("company", company.warnings().getFirst().fieldKey());
        assertEquals("referrerPerson", person.warnings().getFirst().fieldKey());
    }

    @Test
    void staleCustomFieldFailsClosedWithoutDroppingTheFieldSilently() {
        RecordCreationTemplateDefinitionDto definition = definition("custom", "custom:99");
        when(validator.parseDefinition("stale")).thenReturn(definition);

        var resolved = resolver.resolveWorkspace(root(9, "person"), version("stale"), null);

        assertEquals(RecordCreationTemplateAvailability.unavailable, resolved.availability());
        assertEquals("CUSTOM_FIELD_UNAVAILABLE", resolved.warnings().getFirst().code());
        assertEquals("custom:99", resolved.warnings().getFirst().fieldKey());
    }

    @Test
    void malformedCustomSelectOptionsFailClosed() {
        CustomFieldDefinition custom = custom(9, "person", "select", false, false);
        custom.setOptionsJson("not-json");
        when(customFieldMapper.getByEntityType(7, "person")).thenReturn(List.of(custom));

        var resolved = resolver.resolvePreview(
            "workspace:preview",
            RecordCreationRecordType.person,
            new LocalizedTextDto("Template", "テンプレート"),
            null,
            definition("custom", "custom:9"),
            null);

        assertEquals(RecordCreationTemplateAvailability.unavailable, resolved.availability());
        assertEquals("CUSTOM_FIELD_UNAVAILABLE", resolved.warnings().getFirst().code());
    }

    private static ResolvedCreationFieldDto field(
            ooo.klae.connex.backend.dto.recordcreation.ResolvedCreationTemplateDto template,
            String key) {
        return template.groups().stream()
            .flatMap(group -> group.fields().stream())
            .filter(field -> field.key().equals(key))
            .findFirst()
            .orElseThrow();
    }

    private static RecordCreationTemplate root(int id, String recordType) {
        RecordCreationTemplate root = new RecordCreationTemplate();
        root.setId(id);
        root.setRecordType(recordType);
        root.setStatus("enabled");
        return root;
    }

    private static RecordCreationTemplateVersion version(String definitionJson) {
        RecordCreationTemplateVersion version = new RecordCreationTemplateVersion();
        version.setVersionNumber(1);
        version.setNameEn("Template");
        version.setNameJa("テンプレート");
        version.setDefinitionJson(definitionJson);
        return version;
    }

    private static RecordCreationTemplateDefinitionDto definition(String group, String field) {
        return new RecordCreationTemplateDefinitionDto(1, List.of(
            new RecordCreationTemplateGroupDto(
                group,
                new LocalizedTextDto("Group", "グループ"),
                null,
                List.of(new RecordCreationTemplateFieldDto(field, false, null, null, null)))));
    }

    private static RecordCreationTemplateDefinitionDto definitionWithFields(
            RecordCreationTemplateFieldDto... fields) {
        return new RecordCreationTemplateDefinitionDto(1, List.of(
            new RecordCreationTemplateGroupDto(
                "basics",
                new LocalizedTextDto("Basics", "基本情報"),
                null,
                List.of(fields))));
    }

    private static RecordCreationTemplateFieldDto field(
            String key,
            RecordCreationDefaultSpecDto defaultSpec) {
        return new RecordCreationTemplateFieldDto(key, false, null, null, defaultSpec);
    }

    private static RecordCreationDefaultSpecDto reference(int id) {
        return new RecordCreationDefaultSpecDto(
            RecordCreationDefaultKind.literal_reference,
            null, null, null, null, id, null);
    }

    private static RecordCreationDefaultSpecDto references(int... ids) {
        return new RecordCreationDefaultSpecDto(
            RecordCreationDefaultKind.literal_references,
            null, null, null, null, null, java.util.Arrays.stream(ids).boxed().toList());
    }

    private static RecordCreationDefaultSpecDto stringDefault(String value) {
        return new RecordCreationDefaultSpecDto(
            RecordCreationDefaultKind.literal_string,
            value, null, null, null, null, null);
    }

    private static LocalizedTextDto names() {
        return new LocalizedTextDto("Template", "テンプレート");
    }

    private static CustomFieldDefinition custom(
            int id,
            String entityType,
            String fieldType,
            boolean required,
            boolean archived) {
        CustomFieldDefinition definition = new CustomFieldDefinition();
        definition.setId(id);
        definition.setEntityType(entityType);
        definition.setFieldType(fieldType);
        definition.setLabel("Custom");
        definition.setRequired(required);
        definition.setArchived(archived);
        return definition;
    }

    private static Pipeline pipeline(int id, String name) {
        Pipeline pipeline = new Pipeline();
        pipeline.setId(id);
        pipeline.setName(name);
        return pipeline;
    }

    private static Stage stage(int id, Pipeline pipeline, int position, String name) {
        Stage stage = new Stage();
        stage.setId(id);
        stage.setPipeline(pipeline);
        stage.setPosition(position);
        stage.setName(name);
        return stage;
    }
}
