package ooo.klae.connex.backend.ai.assistant;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.AiFeature;
import ooo.klae.connex.backend.ai.AiFeatureGate;
import ooo.klae.connex.backend.beans.AiWatch;
import ooo.klae.connex.backend.dto.AiWatchCreateRequest;
import ooo.klae.connex.backend.dto.AiWatchDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.AiWatchMapper;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * The member's own watches: created, inspected, paused, and deleted by their owner alone.
 *
 * <p>A watch is personal. It is addressed only by the resolved session identity and the workspace it
 * was created in, so no read or write here can reach another member's watches even by identifier,
 * and there is deliberately no administrative listing — a member's watch list describes what they
 * are paying attention to, which is not governance data.
 *
 * <p>Creation validates the whole typed contract before storing it: the assistant must be usable in
 * this workspace for this member, the type must be one this build evaluates, the subject kind must
 * be one that type can watch, the record must be readable now, the threshold the type requires must
 * be present, and any declared expiry must be a real future date. Nothing here accepts prose.
 */
@Service
@RequiredArgsConstructor
public class AiWatchService {

    /**
     * The most watches one member may hold in one workspace.
     *
     * <p>Every active watch is re-evaluated on every sweep, so the cap is what keeps a member's own
     * watch list from becoming an unbounded scheduled workload — and what keeps their notification
     * inbox survivable.
     */
    static final int MAX_WATCHES_PER_MEMBER = 50;

    private static final int DEFAULT_COOLDOWN_DAYS = 7;
    private static final String ACTIVE = "active";
    private static final String PAUSED = "paused";

    private final AiWatchMapper watchMapper;
    private final AiFeatureGate featureGate;
    private final AiWatchSubjectReader subjectReader;
    private final WorkspaceService workspaceService;
    private final Clock clock;

    /** Lists the calling member's watches, newest first, with currently readable subject labels. */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.AI_USE)
    public List<AiWatchDto> list() {
        List<AiWatch> watches = watchMapper.listForOwner(
                workspaceService.getCurrentWorkspaceId(), workspaceService.getCurrentUserId());
        List<AiWatchDto> projected = new ArrayList<>(watches.size());
        for (AiWatch watch : watches) {
            projected.add(AiWatchDto.from(
                    watch,
                    subjectReader.label(watch.getSubjectKind(), watch.getSubjectId())
                            .orElse(null)));
        }
        return List.copyOf(projected);
    }

    /**
     * Creates one typed watch for the calling member.
     *
     * <p>The assistant feature gate is consulted here as well as at evaluation time. A watch created
     * while the assistant is switched off would sit inert and then start firing the moment it was
     * switched on, which is a standing subscription the member was never told they were unable to
     * create; refusing at creation keeps the refusal where they can see it.
     *
     * @param request the complete typed trigger the member applied
     * @return the stored watch
     * @throws BadRequestException when the type, subject, threshold, or expiry is not valid
     * @throws ConflictException when the member already watches this condition on this record
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.AI_USE)
    public AiWatchDto create(AiWatchCreateRequest request) {
        if (request == null) {
            throw new BadRequestException("Watch request is invalid");
        }
        featureGate.requireAiUsable(AiFeature.ASSISTANT_CHAT);
        AiWatchType type = AiWatchType.from(request.watchType())
                .orElseThrow(() -> new BadRequestException(
                        "Watch type is not evaluated by this build"));
        if (!type.subjectKinds().contains(request.subjectKind())) {
            throw new BadRequestException(
                    "Watch type cannot be created against this record kind");
        }
        String label = subjectReader.label(request.subjectKind(), request.subjectId())
                .orElseThrow(() -> new ResourceNotFoundException("Watched record not found"));
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = workspaceService.getCurrentUserId();
        if (watchMapper.countForOwner(workspaceId, userId) >= MAX_WATCHES_PER_MEMBER) {
            throw new ConflictException("Watch limit reached for this workspace");
        }
        AiWatch watch = new AiWatch();
        watch.setWorkspaceId(workspaceId);
        watch.setOwnerUserId(userId);
        watch.setWatchType(type.key());
        watch.setSubjectKind(request.subjectKind());
        watch.setSubjectId(request.subjectId());
        applyThreshold(type, request, watch);
        watch.setStatus(ACTIVE);
        watch.setCooldownDays(
                request.cooldownDays() == null ? DEFAULT_COOLDOWN_DAYS : request.cooldownDays());
        watch.setExpiresOn(requireFutureExpiry(request.expiresOn()));
        try {
            watchMapper.insert(watch);
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            throw new ConflictException("This condition is already watched on this record");
        }
        return AiWatchDto.from(watch, label);
    }

    /**
     * Pauses or resumes one of the calling member's watches.
     *
     * <p>Pausing stops evaluation without discarding the trigger or its firing history, so resuming
     * does not replay everything the watch would have fired while it was paused: the cooldown and
     * last-fired state are still there.
     *
     * @param id durable watch identifier
     * @param active whether the watch should evaluate
     * @return the updated watch
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.AI_USE)
    public AiWatchDto setActive(int id, boolean active) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = workspaceService.getCurrentUserId();
        if (watchMapper.updateStatus(workspaceId, userId, id, active ? ACTIVE : PAUSED) != 1) {
            throw new ResourceNotFoundException("Watch not found");
        }
        AiWatch watch = watchMapper.findForOwner(workspaceId, userId, id);
        if (watch == null) {
            throw new ResourceNotFoundException("Watch not found");
        }
        return AiWatchDto.from(
                watch,
                subjectReader.label(watch.getSubjectKind(), watch.getSubjectId()).orElse(null));
    }

    /** Deletes one of the calling member's watches. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.AI_USE)
    public void delete(int id) {
        if (watchMapper.delete(
                workspaceService.getCurrentWorkspaceId(),
                workspaceService.getCurrentUserId(), id) != 1) {
            throw new ResourceNotFoundException("Watch not found");
        }
    }

    /**
     * Validates a declared expiry as a real calendar date the watch can still reach.
     *
     * <p>The request pattern only proves the shape {@code dddd-dd-dd}, which {@code 2026-13-45}
     * satisfies; storing it would produce a watch whose {@code expires_on} comparison never matches
     * and which therefore silently never evaluates again. A date already in the past is refused for
     * the same reason: the member would be shown an active watch that can never fire.
     *
     * @param expiresOn the declared ISO-8601 local date, or null when the watch does not expire
     * @return the normalized date, or null
     * @throws BadRequestException when the date is not real or is not in the future
     */
    private String requireFutureExpiry(String expiresOn) {
        if (expiresOn == null || expiresOn.isBlank()) {
            return null;
        }
        LocalDate declared;
        try {
            declared = LocalDate.parse(expiresOn.trim());
        } catch (DateTimeParseException exception) {
            throw new BadRequestException("Watch expiry is not a real date");
        }
        if (!declared.isAfter(LocalDate.now(clock.withZone(
                AiChatScopeCalendar.zone(workspaceService))))) {
            throw new BadRequestException("Watch expiry must be in the future");
        }
        return declared.toString();
    }

    /**
     * Applies exactly the threshold the declared type requires, and rejects the others.
     *
     * <p>Storing a threshold the type does not read would produce a watch whose displayed trigger and
     * evaluated trigger disagree, which is the one thing an inspectable watch may never do.
     */
    private static void applyThreshold(
            AiWatchType type, AiWatchCreateRequest request, AiWatch watch) {
        switch (type.threshold()) {
            case BAND -> {
                if (request.thresholdBand() == null) {
                    throw new BadRequestException("Watch requires a warmth band threshold");
                }
                watch.setThresholdBand(request.thresholdBand());
            }
            case DAYS -> {
                if (request.thresholdDays() == null) {
                    throw new BadRequestException("Watch requires a day threshold");
                }
                watch.setThresholdDays(request.thresholdDays());
            }
            case LEVEL -> {
                if (request.thresholdLevel() == null) {
                    throw new BadRequestException("Watch requires a risk level threshold");
                }
                watch.setThresholdLevel(request.thresholdLevel());
            }
            case NONE -> {
                if (request.thresholdBand() != null || request.thresholdDays() != null
                        || request.thresholdLevel() != null) {
                    throw new BadRequestException("Watch type declares no threshold");
                }
            }
        }
    }
}
