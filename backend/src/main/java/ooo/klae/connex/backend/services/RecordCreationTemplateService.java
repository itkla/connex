package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.CustomFieldDefinition;
import ooo.klae.connex.backend.beans.RecordCreationTemplate;
import ooo.klae.connex.backend.beans.RecordCreationTemplateSet;
import ooo.klae.connex.backend.beans.RecordCreationTemplateVersion;
import ooo.klae.connex.backend.dto.recordcreation.LocalizedTextDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationImpactDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationImpactRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationPresetCatalogDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateCreateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateDefaultRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateDefinitionDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateDuplicateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateFieldDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateGroupDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplatePreviewRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateReorderRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateResetRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateStateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateListDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateSummaryDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateUpdateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationWarningDto;
import ooo.klae.connex.backend.dto.recordcreation.ResolvedCreationFieldDto;
import ooo.klae.connex.backend.dto.recordcreation.ResolvedCreationTemplateDto;
import ooo.klae.connex.backend.exceptions.RecordCreationTemplateException;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.CustomFieldDefinitionMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.RecordCreationTemplateMapper;
import ooo.klae.connex.backend.mappers.ShareMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.recordcreation.RecordCreationEntryPoint;
import ooo.klae.connex.backend.recordcreation.RecordCreationFieldRegistry;
import ooo.klae.connex.backend.recordcreation.RecordCreationFieldRegistry.FieldDefinition;
import ooo.klae.connex.backend.recordcreation.RecordCreationFieldRegistry.SystemPreset;
import ooo.klae.connex.backend.recordcreation.RecordCreationImpactOperation;
import ooo.klae.connex.backend.recordcreation.RecordCreationRecordType;
import ooo.klae.connex.backend.recordcreation.RecordCreationTemplateAvailability;
import ooo.klae.connex.backend.recordcreation.RecordCreationTemplateStatus;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

@Service
@RequiredArgsConstructor
public class RecordCreationTemplateService {

    private record Selection(
        String selectedTemplateId,
        List<ResolvedCreationTemplateDto> templates,
        List<RecordCreationWarningDto> warnings,
        boolean partial
    ) {
    }

    private record DependencyIds(
        Set<Integer> customFields,
        Set<Integer> tags,
        Set<Integer> pipelines,
        Set<Integer> stages,
        Set<Integer> companies,
        Set<Integer> persons
    ) {
    }

    private static final Pattern WORKSPACE_ID = Pattern.compile("^workspace:([1-9][0-9]{0,9})$");

    private final RecordCreationTemplateMapper templateMapper;
    private final CustomFieldDefinitionMapper customFieldMapper;
    private final TagMapper tagMapper;
    private final PipelineMapper pipelineMapper;
    private final CompanyMapper companyMapper;
    private final PersonMapper personMapper;
    private final ShareMapper shareMapper;
    private final RecordCreationTemplateValidator validator;
    private final RecordCreationTemplateResolver resolver;
    private final RecordCreationFieldRegistry fieldRegistry;
    private final WorkspaceService workspaceService;
    private final AuditService auditService;
    private final Clock clock;

    @RequirePermission(Permission.CUSTOM_FIELD_MANAGE)
    public RecordCreationTemplateListDto list(
        RecordCreationRecordType recordType,
        boolean includeArchived) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        List<RecordCreationTemplate> roots =
            templateMapper.listRoots(workspaceId, recordType.name(), includeArchived);
        String selectedId = selection(workspaceId, recordType, null).selectedTemplateId();
        List<RecordCreationTemplateSummaryDto> result = new ArrayList<>();
        for (RecordCreationTemplate root : roots) {
            RecordCreationTemplateVersion version =
                templateMapper.getCurrentVersion(workspaceId, root.getId());
            result.add(summary(root, version, selectedId));
        }
        int systemPosition = roots.stream()
            .mapToInt(RecordCreationTemplate::getPosition)
            .max()
            .orElse(-1) + 1;
        result.add(systemSummary(recordType, systemPosition, selectedId));
        RecordCreationTemplateSet set = templateMapper.getSet(workspaceId, recordType.name());
        return new RecordCreationTemplateListDto(
            set == null ? 0 : set.getRevision(), selectedId, List.copyOf(result));
    }

    @RequirePermission(Permission.CUSTOM_FIELD_MANAGE)
    public RecordCreationTemplateDto get(String templateId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if (fieldRegistry.isSystemId(templateId)) {
            RecordCreationRecordType recordType = systemRecordType(templateId);
            String selectedId = selection(workspaceId, recordType, null).selectedTemplateId();
            return systemDto(recordType, selectedId.equals(templateId));
        }
        RecordCreationTemplate root = requireRoot(workspaceId, templateId, false);
        RecordCreationTemplateVersion version = requireCurrentVersion(workspaceId, root);
        String selectedId = selection(
            workspaceId,
            RecordCreationRecordType.valueOf(root.getRecordType()),
            null).selectedTemplateId();
        return dto(root, version, selectedId.equals(templateId));
    }

    @RequirePermission(Permission.CUSTOM_FIELD_MANAGE)
    public ResolvedCreationTemplateDto preview(RecordCreationTemplatePreviewRequestDto request) {
        if (request.templateId() != null && request.definition() == null) {
            RecordCreationTemplateDto template = get(request.templateId());
            if (template.recordType() != request.recordType()) {
                throw definitionInvalid("The preview record type does not match the template");
            }
            ResolvedCreationTemplateDto resolved = template.system()
                ? resolver.resolveSystem(template.recordType(), request.context())
                : resolver.resolvePreview(
                    template.id(),
                    template.recordType(),
                    template.name(),
                    template.description(),
                    template.definition(),
                    request.context());
            requirePreviewAvailable(resolved);
            return resolved;
        }
        RecordCreationTemplateValidator.ValidatedTemplate validated =
            validator.validateAndCanonicalize(
                request.recordType(), request.name(), request.description(), request.definition());
        String previewId = request.templateId() == null ? "workspace:preview" : request.templateId();
        ResolvedCreationTemplateDto resolved = resolver.resolvePreview(
            previewId,
            request.recordType(),
            validated.name(),
            validated.description(),
            validated.definition(),
            request.context());
        requirePreviewAvailable(resolved);
        return resolved;
    }

    @RequirePermission(Permission.CUSTOM_FIELD_MANAGE)
    public RecordCreationImpactDto impact(RecordCreationImpactRequestDto request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        RecordCreationTemplateSet set = readSet(workspaceId, request.recordType());
        requireSetRevision(set, request.expectedSetRevision());
        if (request.operation() == RecordCreationImpactOperation.reset) {
            List<RecordCreationTemplate> roots = templateMapper.listRoots(
                workspaceId, request.recordType().name(), true);
            boolean active = roots.stream().anyMatch(
                root -> !RecordCreationTemplateStatus.archived.name().equals(root.getStatus()));
            return new RecordCreationImpactDto(
                request.operation(),
                request.recordType(),
                null,
                false,
                active,
                List.of(),
                List.of(),
                fieldRegistry.systemPreset(request.recordType()).id(),
                0,
                active);
        }
        RecordCreationTemplate root = requireRoot(workspaceId, request.templateId(), false);
        if (!request.recordType().name().equals(root.getRecordType())) {
            throw templateNotFound();
        }
        RecordCreationTemplateVersion version = requireCurrentVersion(workspaceId, root);
        if (request.expectedTemplateVersion() != null
                && request.expectedTemplateVersion() != version.getVersionNumber()) {
            throw staleVersion(set, root, version);
        }
        if (request.removedFieldKeys().stream().anyMatch(
                fieldKey -> fieldKey == null || fieldKey.isBlank())) {
            throw definitionInvalid("Removed field keys must not contain blank values");
        }
        List<String> removed = request.removedFieldKeys().stream().distinct().sorted().toList();
        List<String> blocked = blockedRequiredFields(
            workspaceId, request.recordType(), removed);
        String currentSelected = selection(workspaceId, request.recordType(), null).selectedTemplateId();
        String nextSelected = request.operation() == RecordCreationImpactOperation.archive
            ? selectedExcluding(workspaceId, request.recordType(), root.getId())
            : currentSelected;
        boolean defaultTemplate = wireId(root).equals(currentSelected);
        boolean enabled = RecordCreationTemplateStatus.enabled.name().equals(root.getStatus());
        boolean confirmation = request.operation() == RecordCreationImpactOperation.archive
            ? !RecordCreationTemplateStatus.archived.name().equals(root.getStatus())
            : !removed.isEmpty();
        return new RecordCreationImpactDto(
            request.operation(),
            request.recordType(),
            wireId(root),
            defaultTemplate,
            enabled,
            removed,
            blocked,
            nextSelected,
            0,
            confirmation);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.CUSTOM_FIELD_MANAGE)
    public RecordCreationTemplateDto create(RecordCreationTemplateCreateRequestDto request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = workspaceService.getCurrentUserId();
        lockMutationPermissions(workspaceId, actorId);
        RecordCreationTemplateSet set = lockSet(workspaceId, request.recordType());
        requireSetRevision(set, request.expectedSetRevision());
        RecordCreationTemplateValidator.ValidatedTemplate validated =
            validator.validateAndCanonicalize(
                request.recordType(), request.name(), request.description(), request.definition());
        int position = templateMapper.listRootsForUpdate(workspaceId, request.recordType().name()).stream()
            .mapToInt(RecordCreationTemplate::getPosition)
            .max()
            .orElse(-1) + 1;
        RecordCreationTemplate root = new RecordCreationTemplate();
        root.setWorkspaceId(workspaceId);
        root.setRecordType(request.recordType().name());
        root.setStatus(request.enabled()
            ? RecordCreationTemplateStatus.enabled.name()
            : RecordCreationTemplateStatus.disabled.name());
        root.setPosition(position);
        root.setCreatedById(actorId);
        root.setUpdatedById(actorId);
        templateMapper.insertRoot(root);
        RecordCreationTemplateVersion version = version(
            workspaceId, root.getId(), 1, validated, actorId);
        templateMapper.insertVersion(version);
        requireUpdated(templateMapper.installCurrentVersion(
            workspaceId, root.getId(), version.getId(), 0, actorId));
        requireSetAdvanced(templateMapper.advanceSetRevision(
            workspaceId, request.recordType().name(), set.getRevision()),
            set,
            null,
            null);
        auditService.record(
            "record_creation_template.create",
            "record_creation_template",
            root.getId(),
            wireId(root),
            "Created record creation template",
            auditChanges(
                wireId(root), request.recordType(), null, root.getStatus(), null, position,
                null, 1, null, hex(validated.definitionHash()),
                wireDefault(set.getDefaultTemplateId()),
                wireDefault(set.getDefaultTemplateId()),
                null));
        RecordCreationTemplate stored = requireRootById(workspaceId, root.getId());
        return dto(stored, requireCurrentVersion(workspaceId, stored), false);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.CUSTOM_FIELD_MANAGE)
    public RecordCreationTemplateDto update(
            String templateId,
            RecordCreationTemplateUpdateRequestDto request) {
        rejectSystemMutation(templateId);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = workspaceService.getCurrentUserId();
        lockMutationPermissions(workspaceId, actorId);
        int id = workspaceId(templateId);
        RecordCreationTemplate preliminary = requireRootById(workspaceId, id);
        RecordCreationRecordType recordType = RecordCreationRecordType.valueOf(preliminary.getRecordType());
        RecordCreationTemplateSet set = lockSet(workspaceId, recordType);
        RecordCreationTemplate root = requireRootForUpdate(workspaceId, id);
        RecordCreationTemplateVersion current = requireCurrentVersion(workspaceId, root);
        requireMutationRevisions(set, root, current, request.expectedSetRevision(),
            request.expectedTemplateRevision(), request.expectedTemplateVersion());
        if (RecordCreationTemplateStatus.archived.name().equals(root.getStatus())) {
            throw RecordCreationTemplateException.of(
                HttpStatus.CONFLICT, "TEMPLATE_NOT_ENABLED", "Restore the template before editing it");
        }
        RecordCreationTemplateValidator.ValidatedTemplate validated =
            validator.validateAndCanonicalize(
                recordType, request.name(), request.description(), request.definition());
        RecordCreationTemplateDefinitionDto currentDefinition =
            validator.parseDefinition(current.getDefinitionJson());
        List<String> removed = removedFields(currentDefinition, validated.definition());
        if (!removed.isEmpty() && !request.confirmImpact()) {
            throw RecordCreationTemplateException.impact(new RecordCreationImpactDto(
                RecordCreationImpactOperation.remove_fields,
                recordType,
                templateId,
                templateId.equals(selection(workspaceId, recordType, null).selectedTemplateId()),
                RecordCreationTemplateStatus.enabled.name().equals(root.getStatus()),
                removed,
                blockedRequiredFields(workspaceId, recordType, removed),
                selection(workspaceId, recordType, null).selectedTemplateId(),
                0,
                true));
        }
        boolean contentChanged = !sameContent(current, validated);
        String desiredStatus = request.enabled()
            ? RecordCreationTemplateStatus.enabled.name()
            : RecordCreationTemplateStatus.disabled.name();
        boolean statusChanged = !desiredStatus.equals(root.getStatus());
        if (!contentChanged && !statusChanged) {
            String updatedSelectedId = selection(
                workspaceId,
                RecordCreationRecordType.valueOf(root.getRecordType()),
                null).selectedTemplateId();
            return dto(root, current, updatedSelectedId.equals(wireId(root)));
        }
        int expectedRootRevision = root.getRevision();
        RecordCreationTemplateVersion published = current;
        if (contentChanged) {
            published = version(
                workspaceId,
                root.getId(),
                templateMapper.nextVersionNumber(workspaceId, root.getId()),
                validated,
                actorId);
            templateMapper.insertVersion(published);
            requireUpdated(templateMapper.installCurrentVersion(
                workspaceId,
                root.getId(),
                published.getId(),
                expectedRootRevision,
                actorId));
            expectedRootRevision++;
        }
        if (statusChanged) {
            requireUpdated(templateMapper.updateStatus(
                workspaceId,
                root.getId(),
                desiredStatus,
                null,
                expectedRootRevision,
                actorId));
        }
        requireSetAdvanced(templateMapper.advanceSetRevision(
            workspaceId, recordType.name(), set.getRevision()), set, root, current);
        auditService.record(
            "record_creation_template.update",
            "record_creation_template",
            root.getId(),
            templateId,
            "Updated record creation template",
            auditChanges(
                templateId,
                recordType,
                root.getStatus(),
                desiredStatus,
                root.getPosition(),
                root.getPosition(),
                current.getVersionNumber(),
                published.getVersionNumber(),
                hex(current.getDefinitionHash()),
                hex(published.getDefinitionHash()),
                wireDefault(set.getDefaultTemplateId()),
                wireDefault(set.getDefaultTemplateId()),
                removed));
        RecordCreationTemplate stored = requireRootById(workspaceId, root.getId());
        return dto(
            stored,
            requireCurrentVersion(workspaceId, stored),
            Objects.equals(set.getDefaultTemplateId(), root.getId()));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.CUSTOM_FIELD_MANAGE)
    public RecordCreationTemplateDto duplicate(
            String templateId,
            RecordCreationTemplateDuplicateRequestDto request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = workspaceService.getCurrentUserId();
        lockMutationPermissions(workspaceId, actorId);
        RecordCreationRecordType recordType;
        RecordCreationTemplateDefinitionDto sourceDefinition;
        RecordCreationTemplateVersion sourceVersion = null;
        if (fieldRegistry.isSystemId(templateId)) {
            SystemPreset preset = fieldRegistry.systemPreset(systemRecordType(templateId));
            recordType = preset.recordType();
            if (request.expectedSourceVersion() != preset.version()) {
                throw RecordCreationTemplateException.stale(
                    "TEMPLATE_VERSION_STALE", "The source template version changed", null, 0, preset.version());
            }
            sourceDefinition = preset.definition();
        } else {
            RecordCreationTemplate sourceRoot = requireRoot(workspaceId, templateId, false);
            recordType = RecordCreationRecordType.valueOf(sourceRoot.getRecordType());
            sourceVersion = requireCurrentVersion(workspaceId, sourceRoot);
            if (request.expectedSourceVersion() != sourceVersion.getVersionNumber()) {
                throw staleVersion(readSet(workspaceId, recordType), sourceRoot, sourceVersion);
            }
            sourceDefinition = validator.parseDefinition(sourceVersion.getDefinitionJson());
        }
        RecordCreationTemplateSet set = lockSet(workspaceId, recordType);
        requireSetRevision(set, request.expectedSetRevision());
        if (!fieldRegistry.isSystemId(templateId)) {
            RecordCreationTemplate sourceRoot = requireRootForUpdate(workspaceId, workspaceId(templateId));
            sourceVersion = requireCurrentVersion(workspaceId, sourceRoot);
            if (request.expectedSourceVersion() != sourceVersion.getVersionNumber()) {
                throw staleVersion(set, sourceRoot, sourceVersion);
            }
            sourceDefinition = validator.parseDefinition(sourceVersion.getDefinitionJson());
        }
        RecordCreationTemplateValidator.ValidatedTemplate validated =
            validator.validateAndCanonicalize(
                recordType, request.name(), request.description(), sourceDefinition);
        int position = templateMapper.listRootsForUpdate(workspaceId, recordType.name()).stream()
            .mapToInt(RecordCreationTemplate::getPosition)
            .max()
            .orElse(-1) + 1;
        RecordCreationTemplate root = new RecordCreationTemplate();
        root.setWorkspaceId(workspaceId);
        root.setRecordType(recordType.name());
        root.setStatus(RecordCreationTemplateStatus.disabled.name());
        root.setPosition(position);
        root.setCreatedById(actorId);
        root.setUpdatedById(actorId);
        templateMapper.insertRoot(root);
        RecordCreationTemplateVersion version = version(
            workspaceId, root.getId(), 1, validated, actorId);
        templateMapper.insertVersion(version);
        requireUpdated(templateMapper.installCurrentVersion(
            workspaceId, root.getId(), version.getId(), 0, actorId));
        requireSetAdvanced(templateMapper.advanceSetRevision(
            workspaceId, recordType.name(), set.getRevision()), set, null, null);
        auditService.record(
            "record_creation_template.duplicate",
            "record_creation_template",
            root.getId(),
            wireId(root),
            "Duplicated record creation template",
            auditChanges(
                wireId(root), recordType, null, root.getStatus(), null, position,
                null, 1, null, hex(version.getDefinitionHash()),
                wireDefault(set.getDefaultTemplateId()),
                wireDefault(set.getDefaultTemplateId()),
                null));
        RecordCreationTemplate stored = requireRootById(workspaceId, root.getId());
        return dto(stored, requireCurrentVersion(workspaceId, stored), false);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.CUSTOM_FIELD_MANAGE)
    public RecordCreationTemplateListDto reorder(
            RecordCreationTemplateReorderRequestDto request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = workspaceService.getCurrentUserId();
        lockMutationPermissions(workspaceId, actorId);
        RecordCreationTemplateSet set = lockSet(workspaceId, request.recordType());
        requireSetRevision(set, request.expectedSetRevision());
        List<RecordCreationTemplate> locked =
            templateMapper.listRootsForUpdate(workspaceId, request.recordType().name());
        List<RecordCreationTemplate> active = locked.stream()
            .filter(root -> !RecordCreationTemplateStatus.archived.name().equals(root.getStatus()))
            .toList();
        List<Integer> orderedIds = new ArrayList<>();
        Set<Integer> unique = new HashSet<>();
        for (String id : request.orderedTemplateIds()) {
            if (fieldRegistry.isSystemId(id)) {
                throw orderInvalid();
            }
            int parsed = workspaceId(id);
            if (!unique.add(parsed)) {
                throw orderInvalid();
            }
            orderedIds.add(parsed);
        }
        Set<Integer> expected = active.stream()
            .map(RecordCreationTemplate::getId)
            .collect(java.util.stream.Collectors.toSet());
        if (!expected.equals(unique)) {
            throw orderInvalid();
        }
        Map<Integer, Integer> oldPositions = new LinkedHashMap<>();
        for (RecordCreationTemplate root : active) {
            oldPositions.put(root.getId(), root.getPosition());
        }
        for (int position = 0; position < orderedIds.size(); position++) {
            templateMapper.updatePositions(workspaceId, orderedIds.get(position), position, actorId);
        }
        requireSetAdvanced(templateMapper.advanceSetRevision(
            workspaceId, request.recordType().name(), set.getRevision()), set, null, null);
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("recordType", request.recordType().name());
        changes.put("oldPositions", oldPositions);
        changes.put("orderedTemplateIds", request.orderedTemplateIds());
        auditService.record(
            "record_creation_template.reorder",
            "record_creation_template",
            null,
            request.recordType().name(),
            "Reordered record creation templates",
            changes);
        return list(request.recordType(), false);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.CUSTOM_FIELD_MANAGE)
    public RecordCreationPresetCatalogDto setDefault(RecordCreationTemplateDefaultRequestDto request) {
        rejectSystemMutation(request.templateId());
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = workspaceService.getCurrentUserId();
        lockMutationPermissions(workspaceId, actorId);
        RecordCreationTemplateSet set = lockSet(workspaceId, request.recordType());
        requireSetRevision(set, request.expectedSetRevision());
        RecordCreationTemplate root = requireRootForUpdate(workspaceId, workspaceId(request.templateId()));
        if (!request.recordType().name().equals(root.getRecordType())) {
            throw templateNotFound();
        }
        if (!RecordCreationTemplateStatus.enabled.name().equals(root.getStatus())) {
            throw RecordCreationTemplateException.of(
                HttpStatus.CONFLICT, "TEMPLATE_NOT_ENABLED", "Only an enabled template can be the default");
        }
        RecordCreationTemplateVersion version = requireCurrentVersion(workspaceId, root);
        ResolvedCreationTemplateDto preliminary = resolver.resolveWorkspace(root, version, null);
        requireAvailableForDefault(preliminary);
        DependencyIds dependencies = dependencyIds(preliminary);
        lockDependencies(workspaceId, dependencies);
        ResolvedCreationTemplateDto resolved = resolver.resolveWorkspace(root, version, null);
        if (!dependencies.equals(dependencyIds(resolved))) {
            throw templateUnavailable();
        }
        if (resolved.availability() != RecordCreationTemplateAvailability.available) {
            throw RecordCreationTemplateException.of(
                HttpStatus.CONFLICT, "TEMPLATE_UNAVAILABLE", "The template is unavailable");
        }
        requireSetAdvanced(templateMapper.setDefault(
            workspaceId,
            request.recordType().name(),
            root.getId(),
            set.getRevision()),
            set,
            root,
            version);
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("recordType", request.recordType().name());
        changes.put("oldDefaultId", set.getDefaultTemplateId() == null
            ? null : "workspace:" + set.getDefaultTemplateId());
        changes.put("newDefaultId", request.templateId());
        auditService.record(
            "record_creation_template.default_set",
            "record_creation_template",
            root.getId(),
            request.templateId(),
            "Set record creation template default",
            changes);
        return catalog(request.recordType());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.CUSTOM_FIELD_MANAGE)
    public RecordCreationTemplateDto archive(
            String templateId,
            RecordCreationTemplateStateRequestDto request) {
        rejectSystemMutation(templateId);
        return changeState(templateId, request, RecordCreationTemplateStatus.archived);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.CUSTOM_FIELD_MANAGE)
    public RecordCreationTemplateDto restore(
            String templateId,
            RecordCreationTemplateStateRequestDto request) {
        rejectSystemMutation(templateId);
        return changeState(templateId, request, RecordCreationTemplateStatus.disabled);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.CUSTOM_FIELD_MANAGE)
    public RecordCreationPresetCatalogDto reset(RecordCreationTemplateResetRequestDto request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = workspaceService.getCurrentUserId();
        lockMutationPermissions(workspaceId, actorId);
        RecordCreationTemplateSet set = lockSet(workspaceId, request.recordType());
        requireSetRevision(set, request.expectedSetRevision());
        List<RecordCreationTemplate> roots =
            templateMapper.listRootsForUpdate(workspaceId, request.recordType().name());
        List<RecordCreationTemplate> nonArchived = roots.stream()
            .filter(root -> !RecordCreationTemplateStatus.archived.name().equals(root.getStatus()))
            .toList();
        if (!nonArchived.isEmpty() && !request.confirmImpact()) {
            throw RecordCreationTemplateException.impact(new RecordCreationImpactDto(
                RecordCreationImpactOperation.reset,
                request.recordType(),
                null,
                false,
                true,
                List.of(),
                List.of(),
                fieldRegistry.systemPreset(request.recordType()).id(),
                0,
                true));
        }
        LocalDateTime archivedAt = LocalDateTime.now(clock);
        for (RecordCreationTemplate root : nonArchived) {
            requireUpdated(templateMapper.updateStatus(
                workspaceId,
                root.getId(),
                RecordCreationTemplateStatus.archived.name(),
                archivedAt,
                root.getRevision(),
                actorId));
        }
        requireSetAdvanced(templateMapper.setDefault(
            workspaceId,
            request.recordType().name(),
            null,
            set.getRevision()),
            set,
            null,
            null);
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("recordType", request.recordType().name());
        changes.put("oldDefaultId", set.getDefaultTemplateId() == null
            ? null : "workspace:" + set.getDefaultTemplateId());
        changes.put("newDefaultId", null);
        changes.put("archivedTemplateIds", nonArchived.stream()
            .map(RecordCreationTemplateService::wireId)
            .toList());
        auditService.record(
            "record_creation_template.reset",
            "record_creation_template",
            null,
            request.recordType().name(),
            "Reset record creation templates",
            changes);
        return catalog(request.recordType());
    }

    @RequirePermission(Permission.CUSTOM_FIELD_MANAGE)
    public RecordCreationPresetCatalogDto catalog(RecordCreationRecordType recordType) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        RecordCreationTemplateSet set = readSet(workspaceId, recordType);
        Selection selection = selection(workspaceId, recordType, null);
        return new RecordCreationPresetCatalogDto(
            recordType,
            RecordCreationEntryPoint.quick_create,
            set.getRevision(),
            selection.selectedTemplateId(),
            selection.templates(),
            selection.partial(),
            selection.warnings());
    }

    private RecordCreationTemplateDto changeState(
            String templateId,
            RecordCreationTemplateStateRequestDto request,
            RecordCreationTemplateStatus target) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = workspaceService.getCurrentUserId();
        lockMutationPermissions(workspaceId, actorId);
        int id = workspaceId(templateId);
        RecordCreationTemplate preliminary = requireRootById(workspaceId, id);
        RecordCreationRecordType recordType = RecordCreationRecordType.valueOf(preliminary.getRecordType());
        RecordCreationTemplateSet set = lockSet(workspaceId, recordType);
        RecordCreationTemplate root = requireRootForUpdate(workspaceId, id);
        RecordCreationTemplateVersion current = requireCurrentVersion(workspaceId, root);
        requireMutationRevisions(
            set,
            root,
            current,
            request.expectedSetRevision(),
            request.expectedTemplateRevision(),
            current.getVersionNumber());
        if (target == RecordCreationTemplateStatus.archived) {
            if (RecordCreationTemplateStatus.archived.name().equals(root.getStatus())) {
                return dto(root, current, false);
            }
            if (!request.confirmImpact()) {
                throw RecordCreationTemplateException.impact(new RecordCreationImpactDto(
                    RecordCreationImpactOperation.archive,
                    recordType,
                    templateId,
                    templateId.equals(selection(workspaceId, recordType, null).selectedTemplateId()),
                    RecordCreationTemplateStatus.enabled.name().equals(root.getStatus()),
                    List.of(),
                    List.of(),
                    selectedExcluding(workspaceId, recordType, root.getId()),
                    0,
                    true));
            }
        } else if (!RecordCreationTemplateStatus.archived.name().equals(root.getStatus())) {
            throw RecordCreationTemplateException.of(
                HttpStatus.CONFLICT, "TEMPLATE_NOT_ENABLED", "Only an archived template can be restored");
        }
        LocalDateTime archivedAt = target == RecordCreationTemplateStatus.archived
            ? LocalDateTime.now(clock)
            : null;
        requireUpdated(templateMapper.updateStatus(
            workspaceId,
            root.getId(),
            target.name(),
            archivedAt,
            root.getRevision(),
            actorId));
        requireSetAdvanced(templateMapper.advanceSetRevision(
            workspaceId, recordType.name(), set.getRevision()), set, root, current);
        auditService.record(
            target == RecordCreationTemplateStatus.archived
                ? "record_creation_template.archive"
                : "record_creation_template.restore",
            "record_creation_template",
            root.getId(),
            templateId,
            target == RecordCreationTemplateStatus.archived
                ? "Archived record creation template"
                : "Restored record creation template",
            auditChanges(
                templateId,
                recordType,
                root.getStatus(),
                target.name(),
                root.getPosition(),
                root.getPosition(),
                current.getVersionNumber(),
                current.getVersionNumber(),
                hex(current.getDefinitionHash()),
                hex(current.getDefinitionHash()),
                wireDefault(set.getDefaultTemplateId()),
                wireDefault(set.getDefaultTemplateId()),
                List.of()));
        RecordCreationTemplate stored = requireRootById(workspaceId, root.getId());
        return dto(stored, requireCurrentVersion(workspaceId, stored), false);
    }

    private void lockMutationPermissions(int workspaceId, int actorId) {
        workspaceService.lockAndRequirePermissions(
            workspaceId,
            Map.of(actorId, Set.of(Permission.CUSTOM_FIELD_MANAGE)));
    }

    private void lockDependencies(int workspaceId, DependencyIds dependencies) {
        dependencies.customFields().stream().sorted()
            .forEach(id -> customFieldMapper.getByIdForUpdate(workspaceId, id));
        dependencies.tags().stream().sorted()
            .forEach(id -> tagMapper.getTagByIdForUpdate(workspaceId, id));
        dependencies.stages().stream().sorted()
            .forEach(id -> pipelineMapper.getVisibleStageByIdForUpdate(workspaceId, id));
        dependencies.pipelines().stream().sorted()
            .forEach(id -> pipelineMapper.getVisiblePipelineByIdForUpdate(workspaceId, id));
        dependencies.pipelines().stream().sorted()
            .forEach(id -> shareMapper.lockPipelineShareForWorkspace(id, workspaceId));
        dependencies.persons().stream().sorted()
            .forEach(id -> personMapper.getVisiblePersonByIdForUpdate(workspaceId, id));
        dependencies.persons().stream().sorted()
            .forEach(id -> shareMapper.lockPersonShareForWorkspace(id, workspaceId));
        dependencies.companies().stream().sorted()
            .forEach(id -> companyMapper.getVisibleCompanyByIdForUpdate(workspaceId, id));
        dependencies.companies().stream().sorted()
            .forEach(id -> shareMapper.lockCompanyShareForWorkspace(id, workspaceId));
    }

    private static DependencyIds dependencyIds(ResolvedCreationTemplateDto template) {
        Set<Integer> customFields = new TreeSet<>();
        Set<Integer> tags = new TreeSet<>();
        Set<Integer> pipelines = new TreeSet<>();
        Set<Integer> stages = new TreeSet<>();
        Set<Integer> companies = new TreeSet<>();
        Set<Integer> persons = new TreeSet<>();
        for (ResolvedCreationFieldDto field : template.groups().stream()
                .flatMap(group -> group.fields().stream()).toList()) {
            if (field.customFieldId() != null) {
                customFields.add(field.customFieldId());
            }
            JsonNode value = field.defaultValue();
            if (value == null) {
                continue;
            }
            switch (field.key()) {
                case "tags" -> {
                    if (value.isArray()) {
                        for (JsonNode tag : value) {
                            if (tag.isInt()) {
                                tags.add(tag.intValue());
                            }
                        }
                    }
                }
                case "pipeline" -> addIntegerValue(pipelines, value);
                case "stage" -> addIntegerValue(stages, value);
                case "company" -> addIntegerValue(companies, value);
                case "referrerPerson" -> addIntegerValue(persons, value);
                default -> {
                }
            }
        }
        return new DependencyIds(
            Set.copyOf(customFields),
            Set.copyOf(tags),
            Set.copyOf(pipelines),
            Set.copyOf(stages),
            Set.copyOf(companies),
            Set.copyOf(persons));
    }

    private static void addIntegerValue(Set<Integer> values, JsonNode value) {
        if (value.isInt()) {
            values.add(value.intValue());
        }
    }

    private static void requireAvailableForDefault(ResolvedCreationTemplateDto resolved) {
        if (resolved.availability() != RecordCreationTemplateAvailability.available) {
            throw templateUnavailable();
        }
    }

    private static RecordCreationTemplateException templateUnavailable() {
        return RecordCreationTemplateException.of(
            HttpStatus.CONFLICT, "TEMPLATE_UNAVAILABLE", "The template is unavailable");
    }

    private Selection selection(
            int workspaceId,
            RecordCreationRecordType recordType,
            Integer excludedRootId) {
        RecordCreationTemplateSet set = readSet(workspaceId, recordType);
        List<RecordCreationTemplate> roots = templateMapper.listRoots(
            workspaceId, recordType.name(), false).stream()
            .filter(root -> !Objects.equals(excludedRootId, root.getId()))
            .sorted(Comparator.comparingInt(RecordCreationTemplate::getPosition)
                .thenComparingInt(RecordCreationTemplate::getId))
            .toList();
        List<ResolvedCreationTemplateDto> templates = new ArrayList<>();
        List<RecordCreationWarningDto> warnings = new ArrayList<>();
        String selected = null;
        Map<Integer, ResolvedCreationTemplateDto> resolvedById = new LinkedHashMap<>();
        for (RecordCreationTemplate root : roots) {
            if (!RecordCreationTemplateStatus.enabled.name().equals(root.getStatus())) {
                continue;
            }
            RecordCreationTemplateVersion version = templateMapper.getCurrentVersion(workspaceId, root.getId());
            if (version == null) {
                RecordCreationWarningDto warning = new RecordCreationWarningDto(
                    "TEMPLATE_UNAVAILABLE", wireId(root), null, null);
                warnings.add(warning);
                continue;
            }
            ResolvedCreationTemplateDto resolved = resolver.resolveWorkspace(root, version, null);
            templates.add(resolved);
            warnings.addAll(resolved.warnings());
            resolvedById.put(root.getId(), resolved);
        }
        if (set.getDefaultTemplateId() != null) {
            ResolvedCreationTemplateDto explicit = resolvedById.get(set.getDefaultTemplateId());
            if (explicit != null
                    && explicit.availability() == RecordCreationTemplateAvailability.available) {
                selected = explicit.id();
            }
        }
        if (selected == null) {
            selected = templates.stream()
                .filter(template -> template.availability() == RecordCreationTemplateAvailability.available)
                .map(ResolvedCreationTemplateDto::id)
                .findFirst()
                .orElse(null);
        }
        ResolvedCreationTemplateDto system = resolver.resolveSystem(recordType, null);
        templates.add(system);
        if (selected == null
                && system.availability() == RecordCreationTemplateAvailability.available) {
            selected = system.id();
        }
        if (selected == null) {
            throw catalogUnavailable();
        }
        boolean partial = !warnings.isEmpty() || templates.stream()
            .anyMatch(template -> template.availability() != RecordCreationTemplateAvailability.available);
        return new Selection(selected, List.copyOf(templates), List.copyOf(warnings), partial);
    }

    private String selectedExcluding(
            int workspaceId,
            RecordCreationRecordType recordType,
            int excludedRootId) {
        return selection(workspaceId, recordType, excludedRootId).selectedTemplateId();
    }

    private RecordCreationTemplateSummaryDto summary(
            RecordCreationTemplate root,
            RecordCreationTemplateVersion version,
            String selectedId) {
        RecordCreationRecordType recordType = RecordCreationRecordType.valueOf(root.getRecordType());
        ResolvedCreationTemplateDto resolved = version == null
            ? unavailable(wireId(root), recordType, false, 0, null, null)
            : resolver.resolveWorkspace(root, version, null);
        return new RecordCreationTemplateSummaryDto(
            wireId(root),
            recordType,
            RecordCreationTemplateStatus.valueOf(root.getStatus()),
            false,
            root.getPosition(),
            root.getRevision(),
            version == null ? 0 : version.getVersionNumber(),
            version == null ? null : new LocalizedTextDto(version.getNameEn(), version.getNameJa()),
            version == null ? null : localized(version.getDescriptionEn(), version.getDescriptionJa()),
            wireId(root).equals(selectedId),
            resolved.availability(),
            resolved.warnings().stream().map(RecordCreationWarningDto::code).distinct().toList(),
            root.getCreatedById(),
            root.getUpdatedById(),
            instant(root.getCreatedAt()),
            instant(root.getUpdatedAt()),
            instant(root.getArchivedAt()));
    }

    private RecordCreationTemplateSummaryDto systemSummary(
            RecordCreationRecordType recordType,
            int position,
            String selectedId) {
        SystemPreset preset = fieldRegistry.systemPreset(recordType);
        ResolvedCreationTemplateDto resolved = resolver.resolveSystem(recordType, null);
        return new RecordCreationTemplateSummaryDto(
            preset.id(),
            recordType,
            RecordCreationTemplateStatus.enabled,
            true,
            position,
            0,
            preset.version(),
            preset.name(),
            preset.description(),
            preset.id().equals(selectedId),
            resolved.availability(),
            resolved.warnings().stream().map(RecordCreationWarningDto::code).distinct().toList(),
            null,
            null,
            null,
            null,
            null);
    }

    private RecordCreationTemplateDto dto(
            RecordCreationTemplate root,
            RecordCreationTemplateVersion version,
            boolean defaultTemplate) {
        RecordCreationRecordType recordType = RecordCreationRecordType.valueOf(root.getRecordType());
        ResolvedCreationTemplateDto resolved = resolver.resolveWorkspace(root, version, null);
        return new RecordCreationTemplateDto(
            wireId(root),
            recordType,
            RecordCreationTemplateStatus.valueOf(root.getStatus()),
            false,
            root.getPosition(),
            root.getRevision(),
            version.getVersionNumber(),
            new LocalizedTextDto(version.getNameEn(), version.getNameJa()),
            localized(version.getDescriptionEn(), version.getDescriptionJa()),
            validator.parseDefinition(version.getDefinitionJson()),
            hex(version.getDefinitionHash()),
            defaultTemplate,
            resolved.availability(),
            resolved.warnings(),
            root.getCreatedById(),
            root.getUpdatedById(),
            instant(root.getCreatedAt()),
            instant(root.getUpdatedAt()),
            instant(root.getArchivedAt()));
    }

    private RecordCreationTemplateDto systemDto(
            RecordCreationRecordType recordType,
            boolean defaultTemplate) {
        SystemPreset preset = fieldRegistry.systemPreset(recordType);
        RecordCreationTemplateValidator.ValidatedTemplate validated =
            validator.validateAndCanonicalize(
                recordType, preset.name(), preset.description(), preset.definition());
        ResolvedCreationTemplateDto resolved = resolver.resolveSystem(recordType, null);
        return new RecordCreationTemplateDto(
            preset.id(),
            recordType,
            RecordCreationTemplateStatus.enabled,
            true,
            0,
            0,
            preset.version(),
            preset.name(),
            preset.description(),
            preset.definition(),
            hex(validated.definitionHash()),
            defaultTemplate,
            resolved.availability(),
            resolved.warnings(),
            null,
            null,
            null,
            null,
            null);
    }

    private ResolvedCreationTemplateDto unavailable(
            String id,
            RecordCreationRecordType recordType,
            boolean system,
            int version,
            LocalizedTextDto name,
            LocalizedTextDto description) {
        RecordCreationWarningDto warning = new RecordCreationWarningDto(
            "TEMPLATE_UNAVAILABLE", id, null, null);
        return new ResolvedCreationTemplateDto(
            id,
            recordType,
            system,
            version,
            name,
            description,
            RecordCreationTemplateAvailability.unavailable,
            List.of(),
            List.of(warning));
    }

    private RecordCreationTemplateVersion version(
            int workspaceId,
            int templateId,
            int versionNumber,
            RecordCreationTemplateValidator.ValidatedTemplate validated,
            int actorId) {
        RecordCreationTemplateVersion version = new RecordCreationTemplateVersion();
        version.setWorkspaceId(workspaceId);
        version.setTemplateId(templateId);
        version.setVersionNumber(versionNumber);
        version.setNameEn(validated.name().en());
        version.setNameJa(validated.name().ja());
        version.setDescriptionEn(validated.description() == null ? null : validated.description().en());
        version.setDescriptionJa(validated.description() == null ? null : validated.description().ja());
        version.setDefinitionJson(validated.definitionJson());
        version.setDefinitionHash(validated.definitionHash());
        version.setCreatedById(actorId);
        return version;
    }

    private RecordCreationTemplateSet readSet(
            int workspaceId,
            RecordCreationRecordType recordType) {
        RecordCreationTemplateSet set = templateMapper.getSet(workspaceId, recordType.name());
        if (set != null) {
            return set;
        }
        RecordCreationTemplateSet empty = new RecordCreationTemplateSet();
        empty.setWorkspaceId(workspaceId);
        empty.setRecordType(recordType.name());
        return empty;
    }

    private RecordCreationTemplateSet lockSet(
            int workspaceId,
            RecordCreationRecordType recordType) {
        templateMapper.insertSetIfAbsent(workspaceId, recordType.name());
        RecordCreationTemplateSet set = templateMapper.getSetForUpdate(workspaceId, recordType.name());
        if (set == null) {
            throw RecordCreationTemplateException.of(
                HttpStatus.SERVICE_UNAVAILABLE,
                "TEMPLATE_CATALOG_UNAVAILABLE",
                "The template catalog could not be locked");
        }
        return set;
    }

    private RecordCreationTemplate requireRoot(
            int workspaceId,
            String templateId,
            boolean forUpdate) {
        if (templateId != null && templateId.startsWith("system:")) {
            throw templateNotFound();
        }
        int id = workspaceId(templateId);
        RecordCreationTemplate root = forUpdate
            ? templateMapper.getRootForUpdate(workspaceId, id)
            : templateMapper.getRoot(workspaceId, id);
        if (root == null) {
            throw templateNotFound();
        }
        return root;
    }

    private RecordCreationTemplate requireRootById(int workspaceId, int id) {
        RecordCreationTemplate root = templateMapper.getRoot(workspaceId, id);
        if (root == null) {
            throw templateNotFound();
        }
        return root;
    }

    private RecordCreationTemplate requireRootForUpdate(int workspaceId, int id) {
        RecordCreationTemplate root = templateMapper.getRootForUpdate(workspaceId, id);
        if (root == null) {
            throw templateNotFound();
        }
        return root;
    }

    private RecordCreationTemplateVersion requireCurrentVersion(
            int workspaceId,
            RecordCreationTemplate root) {
        RecordCreationTemplateVersion version =
            templateMapper.getCurrentVersion(workspaceId, root.getId());
        if (version == null) {
            throw RecordCreationTemplateException.of(
                HttpStatus.SERVICE_UNAVAILABLE,
                "TEMPLATE_CATALOG_UNAVAILABLE",
                "The template version could not be loaded");
        }
        return version;
    }

    private void requireSetRevision(RecordCreationTemplateSet set, int expectedRevision) {
        if (set.getRevision() != expectedRevision) {
            throw RecordCreationTemplateException.stale(
                "TEMPLATE_SET_STALE",
                "The template set changed",
                set.getRevision(),
                null,
                null);
        }
    }

    private void requireMutationRevisions(
            RecordCreationTemplateSet set,
            RecordCreationTemplate root,
            RecordCreationTemplateVersion version,
            int expectedSetRevision,
            int expectedTemplateRevision,
            int expectedTemplateVersion) {
        if (set.getRevision() != expectedSetRevision) {
            throw RecordCreationTemplateException.stale(
                "TEMPLATE_SET_STALE",
                "The template set changed",
                set.getRevision(),
                root.getRevision(),
                version.getVersionNumber());
        }
        if (root.getRevision() != expectedTemplateRevision
                || version.getVersionNumber() != expectedTemplateVersion) {
            throw staleVersion(set, root, version);
        }
    }

    private RecordCreationTemplateException staleVersion(
            RecordCreationTemplateSet set,
            RecordCreationTemplate root,
            RecordCreationTemplateVersion version) {
        return RecordCreationTemplateException.stale(
            "TEMPLATE_VERSION_STALE",
            "The template version changed",
            set.getRevision(),
            root.getRevision(),
            version.getVersionNumber());
    }

    private void requireSetAdvanced(
            int count,
            RecordCreationTemplateSet set,
            RecordCreationTemplate root,
            RecordCreationTemplateVersion version) {
        if (count == 0) {
            throw RecordCreationTemplateException.stale(
                "TEMPLATE_SET_STALE",
                "The template set changed",
                set.getRevision(),
                root == null ? null : root.getRevision(),
                version == null ? null : version.getVersionNumber());
        }
    }

    private static void requireUpdated(int count) {
        if (count == 0) {
            throw RecordCreationTemplateException.of(
                HttpStatus.CONFLICT,
                "TEMPLATE_VERSION_STALE",
                "The template changed before the update completed");
        }
    }

    private List<String> blockedRequiredFields(
            int workspaceId,
            RecordCreationRecordType recordType,
            List<String> removed) {
        Set<String> removedSet = Set.copyOf(removed);
        List<String> blocked = new ArrayList<>();
        for (FieldDefinition field : fieldRegistry.fields(recordType).values()) {
            if (removedSet.contains(field.key()) && field.schemaRequired()) {
                blocked.add(field.key());
            }
        }
        Map<Integer, CustomFieldDefinition> custom = new LinkedHashMap<>();
        for (CustomFieldDefinition definition :
                customFieldMapper.getByEntityType(workspaceId, recordType.name())) {
            custom.put(definition.getId(), definition);
        }
        for (String key : removed) {
            java.util.regex.Matcher matcher = Pattern.compile("^custom:([1-9][0-9]{0,9})$").matcher(key);
            if (matcher.matches()) {
                try {
                    CustomFieldDefinition definition = custom.get(Integer.parseInt(matcher.group(1)));
                    if (definition != null && definition.isRequired()) {
                        blocked.add(key);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return blocked.stream().distinct().sorted().toList();
    }

    private static List<String> removedFields(
            RecordCreationTemplateDefinitionDto before,
            RecordCreationTemplateDefinitionDto after) {
        Set<String> afterKeys = fieldKeys(after);
        return fieldKeys(before).stream()
            .filter(key -> !afterKeys.contains(key))
            .sorted()
            .toList();
    }

    private static Set<String> fieldKeys(RecordCreationTemplateDefinitionDto definition) {
        Set<String> keys = new HashSet<>();
        for (RecordCreationTemplateGroupDto group : definition.groups()) {
            for (RecordCreationTemplateFieldDto field : group.fields()) {
                keys.add(field.key());
            }
        }
        return keys;
    }

    private static boolean sameContent(
            RecordCreationTemplateVersion current,
            RecordCreationTemplateValidator.ValidatedTemplate validated) {
        String descriptionEn = validated.description() == null ? null : validated.description().en();
        String descriptionJa = validated.description() == null ? null : validated.description().ja();
        return current.getNameEn().equals(validated.name().en())
            && current.getNameJa().equals(validated.name().ja())
            && Objects.equals(current.getDescriptionEn(), descriptionEn)
            && Objects.equals(current.getDescriptionJa(), descriptionJa)
            && Arrays.equals(current.getDefinitionHash(), validated.definitionHash())
            && current.getDefinitionJson().equals(validated.definitionJson());
    }

    private Map<String, Object> auditChanges(
            String templateId,
            RecordCreationRecordType recordType,
            String oldStatus,
            String newStatus,
            Integer oldPosition,
            Integer newPosition,
            Integer oldVersion,
            Integer newVersion,
            String oldHash,
            String newHash,
            String oldDefault,
            String newDefault,
            List<String> removed) {
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("templateId", templateId);
        changes.put("recordType", recordType.name());
        changes.put("oldStatus", oldStatus);
        changes.put("newStatus", newStatus);
        changes.put("oldPosition", oldPosition);
        changes.put("newPosition", newPosition);
        changes.put("oldVersion", oldVersion);
        changes.put("newVersion", newVersion);
        changes.put("oldDefinitionHash", oldHash);
        changes.put("newDefinitionHash", newHash);
        changes.put("oldDefaultId", oldDefault);
        changes.put("newDefaultId", newDefault);
        changes.put("removedFieldKeys", removed == null ? List.of() : removed);
        return changes;
    }

    private static RecordCreationTemplateException definitionInvalid(String message) {
        return RecordCreationTemplateException.of(
            HttpStatus.BAD_REQUEST, "TEMPLATE_DEFINITION_INVALID", message);
    }

    private static RecordCreationTemplateException templateNotFound() {
        return RecordCreationTemplateException.of(
            HttpStatus.NOT_FOUND, "TEMPLATE_NOT_FOUND", "Template not found");
    }

    private static RecordCreationTemplateException orderInvalid() {
        return RecordCreationTemplateException.of(
            HttpStatus.BAD_REQUEST, "TEMPLATE_ORDER_INVALID", "The template order is invalid");
    }

    private static void rejectSystemMutation(String templateId) {
        if (templateId != null && templateId.startsWith("system:")) {
            throw RecordCreationTemplateException.of(
                HttpStatus.CONFLICT,
                "SYSTEM_TEMPLATE_IMMUTABLE",
                "System templates are immutable");
        }
    }

    private static int workspaceId(String templateId) {
        java.util.regex.Matcher matcher = WORKSPACE_ID.matcher(
            templateId == null ? "" : templateId);
        if (!matcher.matches()) {
            throw templateNotFound();
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException exception) {
            throw templateNotFound();
        }
    }

    private RecordCreationRecordType systemRecordType(String templateId) {
        for (RecordCreationRecordType recordType : RecordCreationRecordType.values()) {
            if (fieldRegistry.systemPreset(recordType).id().equals(templateId)) {
                return recordType;
            }
        }
        throw templateNotFound();
    }

    private static String wireId(RecordCreationTemplate root) {
        return "workspace:" + root.getId();
    }

    private static String wireDefault(Integer templateId) {
        return templateId == null ? null : "workspace:" + templateId;
    }

    private static String hex(byte[] value) {
        return value == null ? null : HexFormat.of().formatHex(value);
    }

    private static LocalizedTextDto localized(String en, String ja) {
        return en == null && ja == null ? null : new LocalizedTextDto(en, ja);
    }

    private static java.time.Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static void requirePreviewAvailable(ResolvedCreationTemplateDto resolved) {
        if (resolved.availability() != RecordCreationTemplateAvailability.available) {
            throw RecordCreationTemplateException.of(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "TEMPLATE_FIELD_UNAVAILABLE",
                "The preview references an unavailable field");
        }
    }

    private static RecordCreationTemplateException catalogUnavailable() {
        return RecordCreationTemplateException.of(
            HttpStatus.SERVICE_UNAVAILABLE,
            "TEMPLATE_CATALOG_UNAVAILABLE",
            "The template catalog could not be loaded safely");
    }
}
