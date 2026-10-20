package ooo.klae.connex.backend.work;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.mission.AiAgentRunService;
import ooo.klae.connex.backend.dto.AiAgentOutputDto;
import ooo.klae.connex.backend.dto.WorkItemAction;
import ooo.klae.connex.backend.dto.WorkItemActionResponse;
import ooo.klae.connex.backend.dto.WorkItemContextDto;
import ooo.klae.connex.backend.dto.WorkItemDto;
import ooo.klae.connex.backend.dto.WorkItemEvidenceCode;
import ooo.klae.connex.backend.dto.WorkItemEvidenceDto;
import ooo.klae.connex.backend.dto.WorkItemReasonCode;
import ooo.klae.connex.backend.dto.WorkItemReasonDto;
import ooo.klae.connex.backend.dto.WorkItemSource;
import ooo.klae.connex.backend.dto.WorkItemUrgency;
import ooo.klae.connex.backend.exceptions.AiAgentGoneException;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.AiAgentRunMapper;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;

/** One transient, requester-authorized review item per waiting mission; never grants bulk decision authority. */
@Component
@RequiredArgsConstructor
public class AiAgentMissionWorkItemProvider implements WorkItemProvider {
    private static final int PAGE_SIZE = 100;
    private static final int MAX_RAW_ROWS = 1000;
    private final AiAgentRunMapper runs;
    private final AiAgentRunService missions;
    private final WorkspaceService workspaces;

    @Override
    public WorkItemSource source() {
        return WorkItemSource.ai_mission;
    }

    @Override
    public WorkItemProviderResult load(WorkItemProviderQuery query) {
        workspaces.requirePermission(Permission.AI_USE);
        var projected = new ArrayList<WorkItemDto>();
        Integer before = null;
        boolean exhausted = false;
        for (int scanned = 0; scanned < MAX_RAW_ROWS; scanned += PAGE_SIZE) {
            var page = runs.listRuns(query.workspaceId(), query.actorId(), null, "waiting_approval", before, PAGE_SIZE);
            for (var candidate : page) {
                try {
                    AiAgentOutputDto output = missions.output(candidate.getId());
                    var set = output.proposalSet();
                    Instant deadline = set.reviewExpiresAt();
                    if (candidate.getSessionId() == null || !set.frozen() || set.derivedCounts().pending() == 0
                            || deadline == null || !deadline.isAfter(query.asOf())) {
                        continue;
                    }
                    projected.add(toDto(candidate.getSessionId(), output, query));
                } catch (ResourceNotFoundException | AiAgentGoneException | ForbiddenException refusal) {
                    continue;
                }
            }
            if (page.size() < PAGE_SIZE) {
                exhausted = true;
                break;
            }
            int lastId = page.getLast().getId();
            if (before != null && lastId >= before) {
                throw new InvalidWorkItemSourceRowsException();
            }
            before = lastId;
        }
        var matching = projected.stream().filter(item -> query.urgencies().isEmpty()
                || query.urgencies().contains(item.urgency())).sorted(WorkItemOrdering.comparator()).toList();
        return new WorkItemProviderResult(matching.stream().limit(query.candidateLimit()).toList(),
                matching.size(), projected.size(), query.asOf(), exhausted);
    }

    @Override
    public WorkItemActionResponse execute(int sourceId, WorkItemActionCommand command) {
        throw new BadRequestException("mission_control_required");
    }

    private WorkItemDto toDto(int sessionId, AiAgentOutputDto output, WorkItemProviderQuery query) {
        var set = output.proposalSet();
        Instant deadline = java.util.Objects.requireNonNull(set.reviewExpiresAt());
        Instant frozen = java.util.Objects.requireNonNull(set.frozenAt());
        LocalDate dueDate = LocalDate.ofInstant(deadline, ZoneOffset.UTC);
        String title = "ja".equals(LocaleContextHolder.getLocale().getLanguage())
                ? "レコードの変更案作成" : "Record preparation";
        String version = stateHash(output.runId() + ":" + output.outputRevision() + ":" + set.revision() + ":" + deadline);
        return new WorkItemDto("ai_mission:" + output.runId(), source(), output.runId(), title,
                new WorkItemReasonDto(WorkItemReasonCode.mission_review_pending, dueDate, null, null),
                dueDate, WorkItemUrgency.normal,
                List.of(new WorkItemEvidenceDto(WorkItemEvidenceCode.mission_output_prepared, source(),
                        output.runId(), frozen, dueDate, title)),
                frozen, query.asOf(), version, WorkItemProjectionSupport.etag(version),
                new WorkItemContextDto("ai_mission", output.runId(), title,
                        "/ask-connex/" + sessionId + "?mission=" + output.runId()),
                List.of(WorkItemAction.open_context));
    }

    private String stateHash(String source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
