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
 * <p>The two sections report availability separately because they depend on different facts, and
 * collapsing them would misdescribe one of them. A brief is real provider egress, so its availability
 * is the conjunction of the declared skill catalog — "does this build implement a brief at all" — and
 * the full fail-closed gate, which answers "may this member, in this workspace, invoke the assistant
 * right now". Reporting only the first would render an enabled schedule switch in a workspace whose
 * scheduled runs are guaranteed to skip, promising a brief that silently never arrives.
 *
 * <p>Watches reach no provider at all: every condition is decided by the warmth, task, and deal-risk
 * models. Their availability is therefore the governance gate alone, so a workspace that has not
 * configured a provider still gets working watches and is told so, while switching the assistant off
 * still stops them.
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
                featureGate.isFeatureGoverned(AiFeature.ASSISTANT_CHAT),
                watchService.list(),
                AiWatchService.MAX_WATCHES_PER_MEMBER);
    }
}
