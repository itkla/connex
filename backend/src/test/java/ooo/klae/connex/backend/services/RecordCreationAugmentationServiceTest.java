package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.CustomFieldDefinition;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.RecordCreationTemplate;
import ooo.klae.connex.backend.beans.RecordCreationTemplateSet;
import ooo.klae.connex.backend.beans.RecordCreationTemplateVersion;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.dto.recordcreation.LocalizedTextDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationContextDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateUseDto;
import ooo.klae.connex.backend.dto.recordcreation.ResolvedCreationFieldDto;
import ooo.klae.connex.backend.dto.recordcreation.ResolvedCreationGroupDto;
import ooo.klae.connex.backend.dto.recordcreation.ResolvedCreationTemplateDto;
import ooo.klae.connex.backend.exceptions.RecordCreationTemplateException;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.CustomFieldDefinitionMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.RecordCreationTemplateMapper;
import ooo.klae.connex.backend.mappers.ShareMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.recordcreation.RecordCreationAugmentation;
import ooo.klae.connex.backend.recordcreation.RecordCreationEntryPoint;
import ooo.klae.connex.backend.recordcreation.RecordCreationFieldRegistry;
import ooo.klae.connex.backend.recordcreation.RecordCreationFieldSource;
import ooo.klae.connex.backend.recordcreation.RecordCreationFieldValueType;
import ooo.klae.connex.backend.recordcreation.RecordCreationRecordType;
import ooo.klae.connex.backend.recordcreation.RecordCreationTemplateAvailability;

class RecordCreationAugmentationServiceTest {
    private final RecordCreationTemplateMapper templateMapper = mock(RecordCreationTemplateMapper.class);
    private final RecordCreationTemplateResolver resolver = mock(RecordCreationTemplateResolver.class);
    private final RecordCreationFieldRegistry registry = new RecordCreationFieldRegistry();
    private final CustomFieldDefinitionMapper customFieldMapper = mock(CustomFieldDefinitionMapper.class);
    private final CustomFieldValueService customFieldValueService = mock(CustomFieldValueService.class);
    private final TagMapper tagMapper = mock(TagMapper.class);
    private final PipelineMapper pipelineMapper = mock(PipelineMapper.class);
    private final PersonMapper personMapper = mock(PersonMapper.class);
    private final CompanyMapper companyMapper = mock(CompanyMapper.class);
    private final DealMapper dealMapper = mock(DealMapper.class);
    private final ShareMapper shareMapper = mock(ShareMapper.class);
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final JsonMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    private RecordCreationAugmentationService service;

    @BeforeEach
    void setUp() {
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        service = new RecordCreationAugmentationService(
            templateMapper,
            resolver,
            registry,
            customFieldMapper,
            customFieldValueService,
            tagMapper,
            pipelineMapper,
            personMapper,
            companyMapper,
            dealMapper,
            shareMapper,
            workspaceService);
    }

    @Test
    void stalePreliminarySetRevisionFailsBeforeCanonicalCreateCanStart() {
        RecordCreationTemplateUseDto use = use("system:person:standard", 1, 4);
        when(templateMapper.getSet(7, "person")).thenReturn(set(5));
        when(resolver.resolveSystem(RecordCreationRecordType.person, use.context()))
            .thenReturn(resolved(RecordCreationRecordType.person));

        RecordCreationTemplateException exception = assertThrows(
            RecordCreationTemplateException.class,
            () -> service.resolvePreliminary(RecordCreationRecordType.person, use));

        assertEquals("TEMPLATE_SET_STALE", exception.error().code());
        assertEquals(5, exception.error().currentSetRevision());
    }

    @Test
    void companyCreationRejectsRelatedCompanyContext() {
        RecordCreationTemplateUseDto use = new RecordCreationTemplateUseDto(
            "system:company:standard",
            1,
            0,
            RecordCreationEntryPoint.record_detail,
            new RecordCreationContextDto(44));

        RecordCreationTemplateException exception = assertThrows(
            RecordCreationTemplateException.class,
            () -> service.resolvePreliminary(RecordCreationRecordType.company, use));

        assertEquals("VALIDATION_FAILED", exception.error().code());
        verify(templateMapper, never()).getSet(7, "company");
    }

    @Test
    void staleWorkspaceVersionReturnsCurrentRevisionFence() {
        RecordCreationTemplateUseDto use = use("workspace:42", 3, 8);
        RecordCreationTemplate root = root(42, "person", "enabled", 6);
        RecordCreationTemplateVersion version = version(42, 4);
        when(templateMapper.getSet(7, "person")).thenReturn(set(8));
        when(templateMapper.getRoot(7, 42)).thenReturn(root);
        when(templateMapper.getCurrentVersion(7, 42)).thenReturn(version);

        RecordCreationTemplateException exception = assertThrows(
            RecordCreationTemplateException.class,
            () -> service.resolvePreliminary(RecordCreationRecordType.person, use));

        assertEquals("TEMPLATE_VERSION_STALE", exception.error().code());
        assertEquals(6, exception.error().currentTemplateRevision());
        assertEquals(4, exception.error().currentTemplateVersion());
    }

    @Test
    void finalPersonFenceLocksDependenciesInContractOrderAndReturnsAuditProvenance() {
        RecordCreationContextDto context = new RecordCreationContextDto(60);
        RecordCreationAugmentation augmentation = new RecordCreationAugmentation(
            "system:person:standard",
            1,
            2,
            RecordCreationEntryPoint.record_detail,
            context,
            Map.of(9, node("VIP")),
            List.of(4));
        ResolvedCreationTemplateDto resolved = resolved(
            RecordCreationRecordType.person,
            field("custom:9", 9, true),
            field("tags", null, false));
        when(templateMapper.getSetForUpdate(7, "person")).thenReturn(set(2));
        when(resolver.resolveSystem(RecordCreationRecordType.person, context)).thenReturn(resolved);
        CustomFieldDefinition definition = custom(9, "person");
        when(customFieldMapper.getByIdForUpdate(7, 9)).thenReturn(definition);
        Tag tag = new Tag();
        tag.setId(4);
        when(tagMapper.getTagByIdForUpdate(7, 4)).thenReturn(tag);
        Person referrer = new Person();
        referrer.setId(50);
        when(personMapper.getVisiblePersonByIdForUpdate(7, 50)).thenReturn(referrer);
        Company submittedCompany = new Company();
        submittedCompany.setId(55);
        Company contextCompany = new Company();
        contextCompany.setId(60);
        when(companyMapper.getVisibleCompanyByIdForUpdate(7, 55)).thenReturn(submittedCompany);
        when(companyMapper.getVisibleCompanyByIdForUpdate(7, 60)).thenReturn(contextCompany);
        when(personMapper.insertTags(7, 101, List.of(4))).thenReturn(1);

        Map<String, Object> metadata = service.applyPerson(101, 55, 50, augmentation);

        assertEquals("system:person:standard", metadata.get("creationTemplateId"));
        assertEquals(1, metadata.get("creationTemplateVersion"));
        assertEquals("record_detail", metadata.get("creationTemplateEntryPoint"));
        var order = inOrder(
            templateMapper, customFieldMapper, tagMapper, personMapper, shareMapper,
            companyMapper, customFieldValueService);
        order.verify(templateMapper).getSetForUpdate(7, "person");
        order.verify(customFieldMapper).getByIdForUpdate(7, 9);
        order.verify(tagMapper).getTagByIdForUpdate(7, 4);
        order.verify(personMapper).getVisiblePersonByIdForUpdate(7, 50);
        order.verify(shareMapper).lockPersonShareForWorkspace(50, 7);
        order.verify(companyMapper).getVisibleCompanyByIdForUpdate(7, 55);
        order.verify(companyMapper).getVisibleCompanyByIdForUpdate(7, 60);
        order.verify(shareMapper).lockCompanyShareForWorkspace(55, 7);
        order.verify(shareMapper).lockCompanyShareForWorkspace(60, 7);
        order.verify(customFieldValueService).applyJsonValuesForCreate(
            "person", 101, augmentation.customFields(), Map.of(9, definition));
        order.verify(personMapper).insertTags(7, 101, List.of(4));
    }

    @Test
    void finalDealFenceLocksStageBeforePipelineAndRejectsMismatch() {
        RecordCreationContextDto context = new RecordCreationContextDto(null);
        RecordCreationAugmentation augmentation = new RecordCreationAugmentation(
            "system:deal:standard", 1, 0, RecordCreationEntryPoint.quick_create,
            context, Map.of(), List.of());
        when(templateMapper.getSetForUpdate(7, "deal")).thenReturn(set(0));
        when(resolver.resolveSystem(RecordCreationRecordType.deal, context))
            .thenReturn(resolved(RecordCreationRecordType.deal));
        Pipeline selected = new Pipeline();
        selected.setId(8);
        Pipeline other = new Pipeline();
        other.setId(9);
        Stage stage = new Stage();
        stage.setId(10);
        stage.setPipeline(other);
        when(pipelineMapper.getVisibleStageByIdForUpdate(7, 10)).thenReturn(stage);
        when(pipelineMapper.getVisiblePipelineByIdForUpdate(7, 8)).thenReturn(selected);

        RecordCreationTemplateException exception = assertThrows(
            RecordCreationTemplateException.class,
            () -> service.applyDeal(201, 8, 10, null, augmentation));

        assertEquals("VALIDATION_FAILED", exception.error().code());
        var order = inOrder(pipelineMapper, shareMapper);
        order.verify(pipelineMapper).getVisibleStageByIdForUpdate(7, 10);
        order.verify(pipelineMapper).getVisiblePipelineByIdForUpdate(7, 8);
        order.verify(shareMapper).lockPipelineShareForWorkspace(8, 7);
        verify(customFieldValueService, never()).applyJsonValuesForCreate(any(), eq(201), any(), any());
    }

    @Test
    void finalSetRevisionChangeStopsBeforeDependencyWrites() {
        RecordCreationContextDto context = new RecordCreationContextDto(null);
        RecordCreationAugmentation augmentation = new RecordCreationAugmentation(
            "system:company:standard", 1, 2, RecordCreationEntryPoint.quick_create,
            context, Map.of(), List.of());
        when(templateMapper.getSetForUpdate(7, "company")).thenReturn(set(3));
        when(resolver.resolveSystem(RecordCreationRecordType.company, context))
            .thenReturn(resolved(RecordCreationRecordType.company));

        RecordCreationTemplateException exception = assertThrows(
            RecordCreationTemplateException.class,
            () -> service.applyCompany(301, augmentation));

        assertEquals("TEMPLATE_SET_STALE", exception.error().code());
        verify(customFieldValueService, never()).applyJsonValuesForCreate(any(), any(Integer.class), any(), any());
        verify(companyMapper, never()).insertTags(any(Integer.class), any(Integer.class), any());
    }

    private RecordCreationTemplateUseDto use(String id, int version, int setRevision) {
        return new RecordCreationTemplateUseDto(
            id, version, setRevision, RecordCreationEntryPoint.quick_create,
            new RecordCreationContextDto(null));
    }

    private static RecordCreationTemplateSet set(int revision) {
        RecordCreationTemplateSet set = new RecordCreationTemplateSet();
        set.setRevision(revision);
        return set;
    }

    private static RecordCreationTemplate root(
            int id,
            String recordType,
            String status,
            int revision) {
        RecordCreationTemplate root = new RecordCreationTemplate();
        root.setId(id);
        root.setRecordType(recordType);
        root.setStatus(status);
        root.setRevision(revision);
        return root;
    }

    private static RecordCreationTemplateVersion version(int templateId, int number) {
        RecordCreationTemplateVersion version = new RecordCreationTemplateVersion();
        version.setTemplateId(templateId);
        version.setVersionNumber(number);
        return version;
    }

    private static CustomFieldDefinition custom(int id, String entityType) {
        CustomFieldDefinition definition = new CustomFieldDefinition();
        definition.setId(id);
        definition.setEntityType(entityType);
        definition.setFieldType("text");
        return definition;
    }

    private static ResolvedCreationTemplateDto resolved(
            RecordCreationRecordType type,
            ResolvedCreationFieldDto... fields) {
        return new ResolvedCreationTemplateDto(
            "system:" + type.name() + ":standard",
            type,
            true,
            1,
            new LocalizedTextDto("Standard", "標準"),
            null,
            RecordCreationTemplateAvailability.available,
            List.of(new ResolvedCreationGroupDto(
                "basics", new LocalizedTextDto("Basics", "基本"), null, List.of(fields))),
            List.of());
    }

    private static ResolvedCreationFieldDto field(
            String key,
            Integer customId,
            boolean required) {
        return new ResolvedCreationFieldDto(
            key,
            customId == null ? RecordCreationFieldSource.system : RecordCreationFieldSource.custom,
            customId,
            RecordCreationFieldValueType.text,
            null,
            new LocalizedTextDto(key, key),
            null,
            null,
            required,
            required,
            false,
            null,
            null,
            List.of());
    }

    private JsonNode node(Object value) {
        return objectMapper.valueToTree(value);
    }
}
