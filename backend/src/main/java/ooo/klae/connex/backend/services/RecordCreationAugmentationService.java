package ooo.klae.connex.backend.services;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntSupplier;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.CustomFieldDefinition;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.RecordCreationTemplate;
import ooo.klae.connex.backend.beans.RecordCreationTemplateSet;
import ooo.klae.connex.backend.beans.RecordCreationTemplateVersion;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateUseDto;
import ooo.klae.connex.backend.dto.recordcreation.ResolvedCreationFieldDto;
import ooo.klae.connex.backend.dto.recordcreation.ResolvedCreationTemplateDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
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
import ooo.klae.connex.backend.recordcreation.RecordCreationFieldRegistry;
import ooo.klae.connex.backend.recordcreation.RecordCreationRecordType;
import ooo.klae.connex.backend.recordcreation.RecordCreationTemplateAvailability;
import ooo.klae.connex.backend.recordcreation.RecordCreationTemplateStatus;

@Service
@RequiredArgsConstructor
public class RecordCreationAugmentationService {
    private static final Pattern WORKSPACE_ID = Pattern.compile("^workspace:([1-9][0-9]{0,9})$");

    private final RecordCreationTemplateMapper templateMapper;
    private final RecordCreationTemplateResolver resolver;
    private final RecordCreationFieldRegistry fieldRegistry;
    private final CustomFieldDefinitionMapper customFieldMapper;
    private final CustomFieldValueService customFieldValueService;
    private final TagMapper tagMapper;
    private final PipelineMapper pipelineMapper;
    private final PersonMapper personMapper;
    private final CompanyMapper companyMapper;
    private final DealMapper dealMapper;
    private final ShareMapper shareMapper;
    private final WorkspaceService workspaceService;

    ResolvedCreationTemplateDto resolvePreliminary(
            RecordCreationRecordType recordType,
            RecordCreationTemplateUseDto templateUse) {
        if (recordType == RecordCreationRecordType.company
                && templateUse.context().relatedCompanyId() != null) {
            throw RecordCreationTemplateException.of(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "Company creation does not accept related-company context");
        }
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        RecordCreationTemplateSet set = templateMapper.getSet(workspaceId, recordType.name());
        int currentSetRevision = set == null ? 0 : set.getRevision();
        ExactTemplate exact = resolveExact(workspaceId, recordType, templateUse, false);
        requireSetRevision(templateUse, currentSetRevision, exact);
        return requireAvailable(exact.resolved());
    }

    void validatePersonReferences(Integer companyId, Integer referrerPersonId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if (companyId != null && !companyMapper.exists(workspaceId, companyId)) {
            throw relatedRecordNotFound();
        }
        if (referrerPersonId != null && !personMapper.exists(workspaceId, referrerPersonId)) {
            throw relatedRecordNotFound();
        }
    }

    void validateDealReferences(Integer pipelineId, Integer stageId, Integer companyId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Pipeline pipeline = pipelineId == null
            ? null
            : pipelineMapper.getPipelineById(workspaceId, pipelineId);
        Stage stage = stageId == null
            ? null
            : pipelineMapper.getStageById(workspaceId, stageId);
        if ((pipelineId != null && pipeline == null)
                || (stageId != null && stage == null)
                || (companyId != null && !companyMapper.exists(workspaceId, companyId))) {
            throw relatedRecordNotFound();
        }
        if (pipeline != null
                && stage != null
                && (stage.getPipeline() == null
                    || stage.getPipeline().getId() != pipeline.getId())) {
            throw RecordCreationTemplateException.of(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "The selected stage does not belong to the selected pipeline");
        }
    }

    PreparedAugmentation preparePerson(
            Integer companyId,
            Integer referrerPersonId,
            RecordCreationAugmentation augmentation) {
        return prepare(
            RecordCreationRecordType.person,
            augmentation,
            List.of(),
            List.of(),
            nullable(referrerPersonId),
            companies(companyId, augmentation));
    }

    PreparedAugmentation prepareCompany(RecordCreationAugmentation augmentation) {
        return prepare(
            RecordCreationRecordType.company,
            augmentation,
            List.of(),
            List.of(),
            List.of(),
            List.of());
    }

    PreparedAugmentation prepareDeal(
            Integer pipelineId,
            Integer stageId,
            Integer companyId,
            RecordCreationAugmentation augmentation) {
        return prepare(
            RecordCreationRecordType.deal,
            augmentation,
            nullable(pipelineId),
            nullable(stageId),
            List.of(),
            companies(companyId, augmentation));
    }

    Map<String, Object> applyPerson(int personId, PreparedAugmentation prepared) {
        return apply(
            RecordCreationRecordType.person,
            personId,
            prepared,
            () -> personMapper.insertTags(
                workspaceService.getCurrentWorkspaceId(),
                personId,
                prepared.augmentation().tagIds()));
    }

    Map<String, Object> applyCompany(int companyId, PreparedAugmentation prepared) {
        return apply(
            RecordCreationRecordType.company,
            companyId,
            prepared,
            () -> companyMapper.insertTags(
                workspaceService.getCurrentWorkspaceId(),
                companyId,
                prepared.augmentation().tagIds()));
    }

    Map<String, Object> applyDeal(int dealId, PreparedAugmentation prepared) {
        return apply(
            RecordCreationRecordType.deal,
            dealId,
            prepared,
            () -> dealMapper.insertTags(
                workspaceService.getCurrentWorkspaceId(),
                dealId,
                prepared.augmentation().tagIds()));
    }

    private PreparedAugmentation prepare(
            RecordCreationRecordType recordType,
            RecordCreationAugmentation augmentation,
            List<Integer> pipelineIds,
            List<Integer> stageIds,
            List<Integer> personIds,
            List<Integer> companyIds) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        templateMapper.insertSetIfAbsent(workspaceId, recordType.name());
        RecordCreationTemplateSet set = templateMapper.getSetForUpdate(workspaceId, recordType.name());
        if (set == null) {
            throw catalogUnavailable();
        }
        RecordCreationTemplateUseDto templateUse = new RecordCreationTemplateUseDto(
            augmentation.templateId(),
            augmentation.templateVersion(),
            augmentation.templateSetRevision(),
            augmentation.entryPoint(),
            augmentation.context());
        ExactTemplate exact = resolveExact(workspaceId, recordType, templateUse, true);
        requireSetRevision(templateUse, set.getRevision(), exact);
        ResolvedCreationTemplateDto preliminaryLocked = requireAvailable(exact.resolved());
        Map<Integer, CustomFieldDefinition> lockedCustomFields =
            lockCustomFields(workspaceId, preliminaryLocked);
        lockTags(workspaceId, augmentation.tagIds());
        lockReferences(workspaceId, pipelineIds, stageIds, personIds, companyIds);
        ResolvedCreationTemplateDto resolved = requireAvailable(resolveAgain(exact, augmentation));
        validatePayload(recordType, resolved, augmentation);
        return new PreparedAugmentation(recordType, augmentation, lockedCustomFields);
    }

    private Map<String, Object> apply(
            RecordCreationRecordType recordType,
            int entityId,
            PreparedAugmentation prepared,
            IntSupplier insertTags) {
        if (prepared.recordType() != recordType) {
            throw new IllegalArgumentException("Prepared record creation type does not match");
        }
        RecordCreationAugmentation augmentation = prepared.augmentation();
        try {
            customFieldValueService.applyJsonValuesForCreate(
                recordType.name(),
                entityId,
                augmentation.customFields(),
                prepared.lockedCustomFields());
        } catch (BadRequestException exception) {
            throw RecordCreationTemplateException.of(
                HttpStatus.BAD_REQUEST,
                "CUSTOM_FIELD_VALUE_INVALID",
                exception.getMessage());
        }
        if (!augmentation.tagIds().isEmpty()
                && insertTags.getAsInt() != augmentation.tagIds().size()) {
            throw relatedRecordNotFound();
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("creationTemplateId", augmentation.templateId());
        metadata.put("creationTemplateVersion", augmentation.templateVersion());
        metadata.put("creationTemplateEntryPoint", augmentation.entryPoint().name());
        return metadata;
    }

    private ExactTemplate resolveExact(
            int workspaceId,
            RecordCreationRecordType recordType,
            RecordCreationTemplateUseDto templateUse,
            boolean forUpdate) {
        RecordCreationFieldRegistry.SystemPreset preset = fieldRegistry.systemPreset(recordType);
        if (preset.id().equals(templateUse.templateId())) {
            if (preset.version() != templateUse.templateVersion()) {
                throw RecordCreationTemplateException.stale(
                    "TEMPLATE_VERSION_STALE",
                    "The template version changed",
                    currentSetRevision(workspaceId, recordType),
                    0,
                    preset.version());
            }
            return new ExactTemplate(
                null,
                null,
                resolver.resolveSystem(recordType, templateUse.context()));
        }
        int templateId = workspaceTemplateId(templateUse.templateId());
        RecordCreationTemplate root = forUpdate
            ? templateMapper.getRootForUpdate(workspaceId, templateId)
            : templateMapper.getRoot(workspaceId, templateId);
        if (root == null || !recordType.name().equals(root.getRecordType())) {
            throw templateNotFound();
        }
        RecordCreationTemplateVersion version =
            templateMapper.getCurrentVersion(workspaceId, root.getId());
        if (version == null) {
            throw catalogUnavailable();
        }
        if (version.getVersionNumber() != templateUse.templateVersion()) {
            throw RecordCreationTemplateException.stale(
                "TEMPLATE_VERSION_STALE",
                "The template version changed",
                currentSetRevision(workspaceId, recordType),
                root.getRevision(),
                version.getVersionNumber());
        }
        if (!RecordCreationTemplateStatus.enabled.name().equals(root.getStatus())) {
            throw RecordCreationTemplateException.of(
                HttpStatus.CONFLICT,
                "TEMPLATE_UNAVAILABLE",
                "The submitted template is no longer enabled");
        }
        return new ExactTemplate(
            root,
            version,
            resolver.resolveWorkspace(root, version, templateUse.context()));
    }

    private ResolvedCreationTemplateDto resolveAgain(
            ExactTemplate exact,
            RecordCreationAugmentation augmentation) {
        if (exact.root() == null) {
            return resolver.resolveSystem(
                exact.resolved().recordType(), augmentation.context());
        }
        return resolver.resolveWorkspace(
            exact.root(), exact.version(), augmentation.context());
    }

    private Map<Integer, CustomFieldDefinition> lockCustomFields(
            int workspaceId,
            ResolvedCreationTemplateDto resolved) {
        Map<Integer, CustomFieldDefinition> definitions = new LinkedHashMap<>();
        for (int definitionId : activeCustomFieldIds(resolved)) {
            CustomFieldDefinition definition =
                customFieldMapper.getByIdForUpdate(workspaceId, definitionId);
            if (definition == null
                    || definition.isArchived()
                    || !resolved.recordType().name().equals(definition.getEntityType())) {
                throw customFieldNotFound();
            }
            definitions.put(definitionId, definition);
        }
        return definitions;
    }

    private void lockTags(int workspaceId, List<Integer> tagIds) {
        for (int tagId : sortedUnique(tagIds)) {
            Tag tag = tagMapper.getTagByIdForUpdate(workspaceId, tagId);
            if (tag == null) {
                throw relatedRecordNotFound();
            }
        }
    }

    private void lockReferences(
            int workspaceId,
            List<Integer> pipelineIds,
            List<Integer> stageIds,
            List<Integer> personIds,
            List<Integer> companyIds) {
        Map<Integer, Stage> stages = new LinkedHashMap<>();
        for (int stageId : sortedUnique(stageIds)) {
            Stage stage = pipelineMapper.getVisibleStageByIdForUpdate(workspaceId, stageId);
            if (stage == null) {
                throw relatedRecordNotFound();
            }
            stages.put(stageId, stage);
        }
        Map<Integer, Pipeline> pipelines = new LinkedHashMap<>();
        for (int pipelineId : sortedUnique(pipelineIds)) {
            Pipeline pipeline = pipelineMapper.getVisiblePipelineByIdForUpdate(workspaceId, pipelineId);
            if (pipeline == null) {
                throw relatedRecordNotFound();
            }
            pipelines.put(pipelineId, pipeline);
        }
        for (int pipelineId : sortedUnique(pipelineIds)) {
            shareMapper.lockPipelineShareForWorkspace(pipelineId, workspaceId);
        }
        for (Stage stage : stages.values()) {
            if (stage.getPipeline() == null
                    || !pipelines.containsKey(stage.getPipeline().getId())) {
                throw RecordCreationTemplateException.of(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "The selected stage does not belong to the selected pipeline");
            }
        }
        for (int personId : sortedUnique(personIds)) {
            Person person = personMapper.getVisiblePersonByIdForUpdate(workspaceId, personId);
            if (person == null) {
                throw relatedRecordNotFound();
            }
        }
        for (int personId : sortedUnique(personIds)) {
            shareMapper.lockPersonShareForWorkspace(personId, workspaceId);
        }
        for (int companyId : sortedUnique(companyIds)) {
            Company company = companyMapper.getVisibleCompanyByIdForUpdate(workspaceId, companyId);
            if (company == null) {
                throw relatedRecordNotFound();
            }
        }
        for (int companyId : sortedUnique(companyIds)) {
            shareMapper.lockCompanyShareForWorkspace(companyId, workspaceId);
        }
    }

    private void validatePayload(
            RecordCreationRecordType recordType,
            ResolvedCreationTemplateDto resolved,
            RecordCreationAugmentation augmentation) {
        Set<Integer> activeCustomFields = activeCustomFieldIds(resolved);
        if (!activeCustomFields.containsAll(augmentation.customFields().keySet())) {
            throw customFieldNotFound();
        }
        for (ResolvedCreationFieldDto field : fields(resolved)) {
            if (field.customFieldId() != null
                    && field.required()
                    && !hasValue(augmentation.customFields().get(field.customFieldId()))) {
                throw fieldNotSubmitted(field.key());
            }
        }
        boolean tagsActive = fields(resolved).stream().anyMatch(field -> "tags".equals(field.key()));
        if (!tagsActive && !augmentation.tagIds().isEmpty()) {
            throw RecordCreationTemplateException.of(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "Tags are not active in the submitted template");
        }
        if (augmentation.customFields().keySet().stream().anyMatch(id -> id == null || id < 1)
                || augmentation.tagIds().stream().anyMatch(id -> id == null || id < 1)
                || sortedUnique(augmentation.tagIds()).size() != augmentation.tagIds().size()) {
            throw RecordCreationTemplateException.of(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "Guided record values are invalid");
        }
        if (recordType != resolved.recordType()) {
            throw templateNotFound();
        }
    }

    private void requireSetRevision(
            RecordCreationTemplateUseDto templateUse,
            int currentSetRevision,
            ExactTemplate exact) {
        if (templateUse.templateSetRevision() != currentSetRevision) {
            throw RecordCreationTemplateException.stale(
                "TEMPLATE_SET_STALE",
                "The template set changed",
                currentSetRevision,
                exact.root() == null ? 0 : exact.root().getRevision(),
                exact.version() == null
                    ? fieldRegistry.systemPreset(exact.resolved().recordType()).version()
                    : exact.version().getVersionNumber());
        }
    }

    private int currentSetRevision(int workspaceId, RecordCreationRecordType recordType) {
        RecordCreationTemplateSet set = templateMapper.getSet(workspaceId, recordType.name());
        return set == null ? 0 : set.getRevision();
    }

    private static ResolvedCreationTemplateDto requireAvailable(
            ResolvedCreationTemplateDto resolved) {
        if (resolved.availability() != RecordCreationTemplateAvailability.available) {
            throw RecordCreationTemplateException.of(
                HttpStatus.CONFLICT,
                "TEMPLATE_UNAVAILABLE",
                "The submitted template is unavailable");
        }
        return resolved;
    }

    private static Set<Integer> activeCustomFieldIds(ResolvedCreationTemplateDto resolved) {
        Set<Integer> ids = new TreeSet<>();
        for (ResolvedCreationFieldDto field : fields(resolved)) {
            if (field.customFieldId() != null) {
                ids.add(field.customFieldId());
            }
        }
        return Set.copyOf(ids);
    }

    private static List<ResolvedCreationFieldDto> fields(ResolvedCreationTemplateDto resolved) {
        return resolved.groups().stream().flatMap(group -> group.fields().stream()).toList();
    }

    private static boolean hasValue(tools.jackson.databind.JsonNode value) {
        if (value == null || value.isNull()) {
            return false;
        }
        if (value.isTextual()) {
            return !value.textValue().isBlank();
        }
        if (value.isArray() || value.isObject()) {
            return !value.isEmpty();
        }
        return true;
    }

    private static List<Integer> companies(
            Integer submittedCompanyId,
            RecordCreationAugmentation augmentation) {
        Set<Integer> ids = new LinkedHashSet<>();
        if (submittedCompanyId != null) {
            ids.add(submittedCompanyId);
        }
        if (augmentation.context().relatedCompanyId() != null) {
            ids.add(augmentation.context().relatedCompanyId());
        }
        return List.copyOf(ids);
    }

    private static List<Integer> nullable(Integer value) {
        return value == null ? List.of() : List.of(value);
    }

    private static List<Integer> sortedUnique(List<Integer> values) {
        if (values == null || values.stream().anyMatch(java.util.Objects::isNull)) {
            throw RecordCreationTemplateException.of(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "Guided record values must not contain null elements");
        }
        return values.stream().distinct().sorted().toList();
    }

    private static int workspaceTemplateId(String templateId) {
        java.util.regex.Matcher matcher = WORKSPACE_ID.matcher(templateId == null ? "" : templateId);
        if (!matcher.matches()) {
            throw templateNotFound();
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException exception) {
            throw templateNotFound();
        }
    }

    private static RecordCreationTemplateException templateNotFound() {
        return RecordCreationTemplateException.of(
            HttpStatus.NOT_FOUND,
            "TEMPLATE_NOT_FOUND",
            "Template not found");
    }

    private static RecordCreationTemplateException customFieldNotFound() {
        return RecordCreationTemplateException.of(
            HttpStatus.NOT_FOUND,
            "CUSTOM_FIELD_NOT_FOUND",
            "Custom field not found");
    }

    private static RecordCreationTemplateException relatedRecordNotFound() {
        return RecordCreationTemplateException.of(
            HttpStatus.NOT_FOUND,
            "RELATED_RECORD_NOT_FOUND",
            "Related record not found");
    }

    private static RecordCreationTemplateException fieldNotSubmitted(String fieldKey) {
        return new RecordCreationTemplateException(
            HttpStatus.BAD_REQUEST,
            "TEMPLATE_FIELD_NOT_SUBMITTED",
            "A required template field was not submitted",
            Map.of(fieldKey, "A value is required"),
            null,
            null,
            null,
            null);
    }

    private static RecordCreationTemplateException catalogUnavailable() {
        return RecordCreationTemplateException.of(
            HttpStatus.SERVICE_UNAVAILABLE,
            "TEMPLATE_CATALOG_UNAVAILABLE",
            "The template catalog could not be loaded safely");
    }

    private record ExactTemplate(
        RecordCreationTemplate root,
        RecordCreationTemplateVersion version,
        ResolvedCreationTemplateDto resolved
    ) {
    }

    record PreparedAugmentation(
        RecordCreationRecordType recordType,
        RecordCreationAugmentation augmentation,
        Map<Integer, CustomFieldDefinition> lockedCustomFields
    ) {
        PreparedAugmentation {
            lockedCustomFields = Map.copyOf(lockedCustomFields);
        }
    }
}
