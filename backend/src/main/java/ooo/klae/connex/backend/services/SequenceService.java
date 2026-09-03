package ooo.klae.connex.backend.services;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Sequence;
import ooo.klae.connex.backend.beans.SequenceStep;
import ooo.klae.connex.backend.beans.SequenceStepContent;
import ooo.klae.connex.backend.dto.sequence.SequenceDto;
import ooo.klae.connex.backend.dto.sequence.SequenceRequest;
import ooo.klae.connex.backend.dto.sequence.SequenceStepDto;
import ooo.klae.connex.backend.dto.sequence.SequenceStepRequest;
import ooo.klae.connex.backend.dto.sequence.SequenceStepType;
import ooo.klae.connex.backend.exceptions.SequenceException;
import ooo.klae.connex.backend.mappers.SequenceMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/** Business logic for sequence templates and mutable draft steps. */
@Service
@RequiredArgsConstructor
public class SequenceService {
    private static final int MAX_STEPS = 100;

    private final SequenceMapper sequenceMapper;
    private final WorkspaceService workspaceService;
    private final AuthService authService;
    private final AuditService auditService;
    private final SequenceMergeFieldResolver mergeFieldResolver;

    /** Lists active sequence templates visible to the current member. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    @RequirePermission(Permission.SEQUENCE_VIEW)
    public List<SequenceDto> list() {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = authService.getCurrentUser().getId();
        requireViewPermission(workspaceId, userId);
        return sequenceMapper.getVisibleSequences(workspaceId, userId).stream()
            .map(sequence -> toDto(sequence, loadSteps(workspaceId, sequence.getId())))
            .toList();
    }

    /** Returns one visible sequence template. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    @RequirePermission(Permission.SEQUENCE_VIEW)
    public SequenceDto get(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = authService.getCurrentUser().getId();
        requireViewPermission(workspaceId, userId);
        Sequence sequence = requireVisible(workspaceId, id, userId);
        return toDto(sequence, loadSteps(workspaceId, id));
    }

    /** Creates a personal or shared sequence with an atomically stored draft. */
    @Transactional
    @RequirePermission(Permission.SEQUENCE_MANAGE)
    public SequenceDto create(SequenceRequest request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = authService.getCurrentUser().getId();
        lockManagePermission(workspaceId, actorId);
        ValidatedRequest validated = validate(request);
        Sequence sequence = new Sequence();
        sequence.setWorkspaceId(workspaceId);
        sequence.setOwnerId(actorId);
        sequence.setStatus("draft");
        sequence.setCreatedById(actorId);
        sequence.setUpdatedById(actorId);
        apply(sequence, validated);
        sequenceMapper.insertSequence(sequence);
        replaceSteps(workspaceId, sequence.getId(), validated.steps());
        auditService.record(
            "sequence.create", "sequence", sequence.getId(), sequence.getName(),
            "Created sequence " + sequence.getName(), Map.of("stepCount", validated.steps().size()));
        return getInternal(workspaceId, sequence.getId(), actorId);
    }

    /** Replaces mutable sequence fields and its complete ordered draft. */
    @Transactional
    @RequirePermission(Permission.SEQUENCE_MANAGE)
    public SequenceDto update(int id, SequenceRequest request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = authService.getCurrentUser().getId();
        lockManagePermission(workspaceId, actorId);
        Sequence sequence = requireVisibleForUpdate(workspaceId, id, actorId);
        if (sequence.getArchivedAt() != null) {
            throw SequenceException.notFound("Sequence not found");
        }
        ValidatedRequest validated = validate(request);
        String previousName = sequence.getName();
        apply(sequence, validated);
        sequence.setUpdatedById(actorId);
        if (sequenceMapper.updateSequence(sequence) == 0) {
            throw SequenceException.notFound("Sequence not found");
        }
        sequenceMapper.deleteDraftSteps(workspaceId, id);
        replaceSteps(workspaceId, id, validated.steps());
        auditService.record(
            "sequence.update", "sequence", id, sequence.getName(),
            "Updated sequence " + sequence.getName(),
            auditService.singleChange("name", previousName, sequence.getName()));
        return getInternal(workspaceId, id, actorId);
    }

    /** Archives a sequence without removing published history. */
    @Transactional
    @RequirePermission(Permission.SEQUENCE_MANAGE)
    public void archive(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = authService.getCurrentUser().getId();
        lockManagePermission(workspaceId, actorId);
        Sequence sequence = requireVisibleForUpdate(workspaceId, id, actorId);
        if (sequence.getArchivedAt() != null) {
            return;
        }
        if (sequenceMapper.archiveSequence(workspaceId, id, actorId, LocalDateTime.now()) == 0) {
            throw SequenceException.notFound("Sequence not found");
        }
        auditService.record(
            "sequence.archive", "sequence", id, sequence.getName(),
            "Archived sequence " + sequence.getName(), null);
    }

    Sequence requireVisible(int workspaceId, int id, int userId) {
        Sequence sequence = sequenceMapper.getVisibleSequence(workspaceId, id, userId);
        if (sequence == null) {
            throw SequenceException.notFound("Sequence not found");
        }
        return sequence;
    }

    Sequence requireVisibleForUpdate(int workspaceId, int id, int userId) {
        Sequence sequence = sequenceMapper.getVisibleSequenceForUpdate(workspaceId, id, userId);
        if (sequence == null) {
            throw SequenceException.notFound("Sequence not found");
        }
        return sequence;
    }

    List<SequenceStepDto> loadSteps(int workspaceId, int sequenceId) {
        List<SequenceStep> steps = sequenceMapper.getSteps(workspaceId, sequenceId);
        if (steps.isEmpty()) {
            return List.of();
        }
        List<SequenceStepContent> contents = sequenceMapper.getStepContents(
            workspaceId, steps.stream().map(SequenceStep::getId).toList());
        return toStepDtos(steps, contents);
    }

    List<SequenceStepDto> loadStepsForShare(int workspaceId, int sequenceId) {
        List<SequenceStep> steps = sequenceMapper.getStepsForShare(workspaceId, sequenceId);
        if (steps.isEmpty()) {
            return List.of();
        }
        List<SequenceStepContent> contents = sequenceMapper.getStepContentsForShare(
            workspaceId, steps.stream().map(SequenceStep::getId).toList());
        return toStepDtos(steps, contents);
    }

    private static List<SequenceStepDto> toStepDtos(
            List<SequenceStep> steps,
            List<SequenceStepContent> contents) {
        Map<Long, List<SequenceStepContent>> contentsByStep = contents.stream()
            .collect(java.util.stream.Collectors.groupingBy(
                SequenceStepContent::getStepId,
                java.util.LinkedHashMap::new,
                java.util.stream.Collectors.toList()));
        return steps.stream().map(step -> new SequenceStepDto(
            step.getPosition(),
            SequenceStepType.fromToken(step.getStepType()),
            step.getDelayValue(),
            step.getDelayUnit(),
            step.getAdvancePolicy(),
            contentsByStep.getOrDefault(step.getId(), List.of()).stream()
                .map(content -> new SequenceStepDto.ContentDto(
                    content.getLocale(), content.getSubject(), content.getBodyText(), content.getBodyHtml()))
                .toList())).toList();
    }

    void lockManagePermission(int workspaceId, int actorId) {
        workspaceService.lockAndRequirePermissions(
            workspaceId, Map.of(actorId, Set.of(Permission.SEQUENCE_MANAGE)));
    }

    void requireViewPermission(int workspaceId, int actorId) {
        workspaceService.requirePermission(workspaceId, actorId, Permission.SEQUENCE_VIEW);
    }

    private SequenceDto getInternal(int workspaceId, int id, int actorId) {
        Sequence sequence = requireVisible(workspaceId, id, actorId);
        return toDto(sequence, loadSteps(workspaceId, id));
    }

    private ValidatedRequest validate(SequenceRequest request) {
        if (request == null) {
            throw invalid("Sequence request is required");
        }
        String name = required(request.name(), 128, "Sequence name is required");
        String purpose = optional(request.purpose(), 512);
        if (!Set.of("personal", "shared").contains(request.visibility())) {
            throw invalid("Sequence visibility must be personal or shared");
        }
        if (request.weekdayMask() < 1 || request.weekdayMask() > 127) {
            throw invalid("Sequence weekday mask must select at least one weekday");
        }
        if (request.sendWindowStart() == null || request.sendWindowEnd() == null
                || request.sendWindowStart().equals(request.sendWindowEnd())) {
            throw invalid("Sequence send window must have distinct start and end times");
        }
        String timezone = timezone(request.timezone());
        List<SequenceStepRequest> submitted = request.steps();
        if (submitted == null || submitted.size() > MAX_STEPS) {
            throw invalid("A sequence accepts at most 100 steps");
        }
        List<SequenceStepDto> steps = new ArrayList<>(submitted.size());
        for (int position = 0; position < submitted.size(); position++) {
            steps.add(validateStep(submitted.get(position), position));
        }
        return new ValidatedRequest(
            name, purpose, request.visibility(), timezone, request.weekdayMask(),
            request.sendWindowStart(), request.sendWindowEnd(), List.copyOf(steps));
    }

    private SequenceStepDto validateStep(SequenceStepRequest request, int position) {
        if (request == null || request.type() == null) {
            throw invalid("Every sequence step needs a type");
        }
        int delayValue = request.delayValue() == null ? 0 : request.delayValue();
        if (delayValue < 0 || delayValue > 8760) {
            throw invalid("Sequence step delay must be between 0 and 8760");
        }
        String delayUnit = request.delayUnit() == null ? "hours" : request.delayUnit();
        if (!Set.of("hours", "business_days").contains(delayUnit)) {
            throw invalid("Sequence step delay unit is invalid");
        }
        boolean manual = request.type() == SequenceStepType.CALL_TASK
            || request.type() == SequenceStepType.GENERAL_TASK;
        String advancePolicy = request.advancePolicy() == null
            ? manual ? "manual_completion" : "automatic"
            : request.advancePolicy();
        if (manual && !Set.of("manual_completion", "manual_completion_or_skip").contains(advancePolicy)
                || !manual && !"automatic".equals(advancePolicy)) {
            throw invalid("Sequence step advance policy is invalid for its type");
        }
        List<SequenceStepRequest.Content> submitted = request.contents() == null
            ? List.of() : request.contents();
        if (submitted.size() > 2 || request.type() == SequenceStepType.WAIT && !submitted.isEmpty()
                || request.type() != SequenceStepType.WAIT && submitted.isEmpty()) {
            throw invalid("Sequence step localized content is invalid");
        }
        Set<String> locales = new HashSet<>();
        List<SequenceStepDto.ContentDto> contents = new ArrayList<>();
        for (SequenceStepRequest.Content content : submitted) {
            if (content == null || !Set.of("en", "ja").contains(content.locale())
                    || !locales.add(content.locale())) {
                throw invalid("Sequence step locales must be unique en or ja values");
            }
            String subject = optional(content.subject(), 255);
            String bodyText = optionalContent(content.bodyText(), 65535);
            String bodyHtml = optionalContent(content.bodyHtml(), 262144);
            if (subject == null && bodyText == null && bodyHtml == null) {
                throw invalid("Sequence step content cannot be empty");
            }
            if (request.type() == SequenceStepType.SEND_EMAIL
                    && (subject == null || bodyText == null && bodyHtml == null)) {
                throw invalid("Email steps require a subject and a body");
            }
            mergeFieldResolver.validateTemplate(subject);
            mergeFieldResolver.validateTemplate(bodyText);
            mergeFieldResolver.validateTemplate(bodyHtml);
            contents.add(new SequenceStepDto.ContentDto(
                content.locale(), subject, bodyText, bodyHtml));
        }
        contents.sort(java.util.Comparator.comparing(SequenceStepDto.ContentDto::locale));
        return new SequenceStepDto(
            position, request.type(), delayValue, delayUnit, advancePolicy, List.copyOf(contents));
    }

    private void replaceSteps(int workspaceId, int sequenceId, List<SequenceStepDto> steps) {
        for (SequenceStepDto draft : steps) {
            SequenceStep step = new SequenceStep();
            step.setWorkspaceId(workspaceId);
            step.setSequenceId(sequenceId);
            step.setPosition(draft.position());
            step.setStepType(draft.type().token());
            step.setDelayValue(draft.delayValue());
            step.setDelayUnit(draft.delayUnit());
            step.setAdvancePolicy(draft.advancePolicy());
            sequenceMapper.insertStep(step);
            for (SequenceStepDto.ContentDto draftContent : draft.contents()) {
                SequenceStepContent content = new SequenceStepContent();
                content.setWorkspaceId(workspaceId);
                content.setStepId(step.getId());
                content.setLocale(draftContent.locale());
                content.setSubject(draftContent.subject());
                content.setBodyText(draftContent.bodyText());
                content.setBodyHtml(draftContent.bodyHtml());
                sequenceMapper.insertStepContent(content);
            }
        }
    }

    private static void apply(Sequence sequence, ValidatedRequest request) {
        sequence.setName(request.name());
        sequence.setPurpose(request.purpose());
        sequence.setVisibility(request.visibility());
        sequence.setTimezone(request.timezone());
        sequence.setWeekdayMask(request.weekdayMask());
        sequence.setSendWindowStart(request.sendWindowStart());
        sequence.setSendWindowEnd(request.sendWindowEnd());
    }

    private static SequenceDto toDto(Sequence sequence, List<SequenceStepDto> steps) {
        return new SequenceDto(
            sequence.getId(), sequence.getName(), sequence.getPurpose(), sequence.getOwnerId(),
            sequence.getVisibility(), sequence.getStatus(), sequence.getTimezone(),
            sequence.getWeekdayMask(), sequence.getSendWindowStart(), sequence.getSendWindowEnd(),
            List.copyOf(steps), sequence.getCreatedAt(), sequence.getUpdatedAt());
    }

    private static String timezone(String value) {
        String normalized = required(value, 64, "Sequence timezone is required");
        try {
            ZoneId zone = ZoneId.of(normalized);
            if (!ZoneId.getAvailableZoneIds().contains(zone.getId())) {
                throw invalid("Sequence timezone must be a named IANA timezone");
            }
            return zone.getId();
        } catch (DateTimeException exception) {
            throw invalid("Sequence timezone must be a named IANA timezone");
        }
    }

    private static String required(String value, int max, String message) {
        String normalized = optional(value, max);
        if (normalized == null) {
            throw invalid(message);
        }
        return normalized;
    }

    private static String optional(String value, int max) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > max) {
            throw invalid("Sequence field is too long");
        }
        return normalized;
    }

    private static String optionalContent(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() > max) {
            throw invalid("Sequence field is too long");
        }
        return value;
    }

    private static SequenceException invalid(String message) {
        return SequenceException.badRequest("SEQUENCE_INVALID", message);
    }

    private record ValidatedRequest(
            String name,
            String purpose,
            String visibility,
            String timezone,
            int weekdayMask,
            java.time.LocalTime sendWindowStart,
            java.time.LocalTime sendWindowEnd,
            List<SequenceStepDto> steps) {
    }
}
