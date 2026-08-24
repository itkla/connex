package ooo.klae.connex.backend.ai.assistant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.AiFeature;
import ooo.klae.connex.backend.ai.AiFeatureGate;
import ooo.klae.connex.backend.beans.AiBriefSchedule;
import ooo.klae.connex.backend.dto.AiBriefScheduleDto;
import ooo.klae.connex.backend.dto.AiCommandCenterDto;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * Assembles the one authorized read the Ask Connex command centre renders from.
 *
 * <p>It composes existing per-member reads rather than adding a projection of its own, so nothing the
 * surface shows can diverge from what the schedule and watch endpoints return individually.
 *
 * <p>Availability is the conjunction of the two facts that actually decide whether a scheduled brief
 * can ever run: the declared skill catalog answers "does this build implement a brief at all", and
 * the fail-closed feature gate answers "may this member, in this workspace, invoke the assistant
 * right now". Reporting only the first would render an enabled schedule switch in a workspace whose
 * scheduled runs are guaranteed to skip, which is the one thing a standing-work surface must not do:
 * it would promise a brief that silently never arrives.
 */
@Service
@RequiredArgsConstructor
public class AiCommandCenterService {

    private final AiBriefScheduleService scheduleService;
    private final AiWatchService watchService;
    private final AiSkillCatalog skillCatalog;
    private final AiFeatureGate featureGate;
    private final WorkspaceService workspaceService;

    /** Returns the calling member's brief schedule, last delivered brief, and watches. */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.AI_USE)
    public AiCommandCenterDto get() {
        AiBriefSchedule schedule = scheduleService.current();
        return new AiCommandCenterDto(
                AiBriefScheduleDto.from(schedule, AiChatScopeCalendar.zone(workspaceService).getId()),
                schedule == null ? null : schedule.getLastDeliveredSessionId(),
                schedule == null ? null : schedule.getLastDeliveredKind(),
                schedule == null ? null : schedule.getLastDeliveredAt(),
                skillCatalog.isAvailable("daily_work_brief_v1")
                        && featureGate.isAiUsable(AiFeature.ASSISTANT_CHAT),
                watchService.list(),
                AiWatchService.MAX_WATCHES_PER_MEMBER);
    }
}
