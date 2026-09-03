package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Sequence;
import ooo.klae.connex.backend.beans.SequenceVersion;
import ooo.klae.connex.backend.dto.sequence.SequenceMergeFieldDto;
import ooo.klae.connex.backend.dto.sequence.SequencePreviewDto;
import ooo.klae.connex.backend.dto.sequence.SequencePreviewRequest;
import ooo.klae.connex.backend.dto.sequence.SequenceStepDto;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/** Builds read-only localized previews without creating delivery or activity state. */
@Service
@RequiredArgsConstructor
public class SequencePreviewService {
    private final SequenceService sequenceService;
    private final SequenceVersionService versionService;
    private final SequenceMergeFieldResolver mergeFieldResolver;
    private final WorkspaceService workspaceService;
    private final AuthService authService;

    /** Returns the fixed allowlisted merge-field catalog. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    @RequirePermission(Permission.SEQUENCE_VIEW)
    public List<SequenceMergeFieldDto> mergeFields() {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = authService.getCurrentUser().getId();
        sequenceService.requireViewPermission(workspaceId, actorId);
        return mergeFieldResolver.catalog();
    }

    /** Renders one published sequence version against one readable contact. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    @RequirePermission(Permission.SEQUENCE_VIEW)
    public SequencePreviewDto preview(
            int sequenceId,
            int versionNumber,
            SequencePreviewRequest request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = authService.getCurrentUser().getId();
        sequenceService.requireViewPermission(workspaceId, actorId);
        if (request == null || request.personId() < 1) {
            throw ooo.klae.connex.backend.exceptions.SequenceException.badRequest(
                "SEQUENCE_PREVIEW_INVALID", "A contact is required for preview");
        }
        Sequence sequence = sequenceService.requireVisible(workspaceId, sequenceId, actorId);
        SequenceVersion version = versionService.requireVersion(workspaceId, sequenceId, versionNumber);
        SequenceMergeFieldResolver.ResolvedFields fields =
            mergeFieldResolver.resolve(sequence, workspaceId, actorId, request.personId());
        String locale = preferredLocale();
        List<SequencePreviewDto.RenderedStep> renderedSteps = new ArrayList<>();
        Set<String> unresolved = new TreeSet<>();
        for (SequenceStepDto step : versionService.parseSteps(version)) {
            SelectedContent selected = selectContent(step.contents(), locale);
            SequenceStepDto.ContentDto content = selected == null ? null : selected.content();
            SequenceMergeFieldResolver.Rendered subject = render(
                content == null ? null : content.subject(), fields, false);
            SequenceMergeFieldResolver.Rendered bodyText = render(
                content == null ? null : content.bodyText(), fields, false);
            SequenceMergeFieldResolver.Rendered bodyHtml = render(
                content == null ? null : content.bodyHtml(), fields, true);
            unresolved.addAll(subject.unresolved());
            unresolved.addAll(bodyText.unresolved());
            unresolved.addAll(bodyHtml.unresolved());
            renderedSteps.add(new SequencePreviewDto.RenderedStep(
                step.position(),
                step.type(),
                selected == null ? null : selected.locale(),
                subject.value(),
                bodyText.value(),
                bodyHtml.value()));
        }
        return new SequencePreviewDto(
            versionNumber, List.copyOf(renderedSteps), List.copyOf(unresolved));
    }

    private SequenceMergeFieldResolver.Rendered render(
            String template,
            SequenceMergeFieldResolver.ResolvedFields fields,
            boolean html) {
        return mergeFieldResolver.render(template, fields, html);
    }

    private static SelectedContent selectContent(
            List<SequenceStepDto.ContentDto> contents,
            String locale) {
        if (contents == null || contents.isEmpty()) {
            return null;
        }
        Set<String> order = new LinkedHashSet<>();
        order.add(locale);
        order.add("en");
        order.add("ja");
        for (String candidate : order) {
            for (SequenceStepDto.ContentDto content : contents) {
                if (candidate.equals(content.locale())) {
                    return new SelectedContent(candidate, content);
                }
            }
        }
        return null;
    }

    private static String preferredLocale() {
        Locale locale = LocaleContextHolder.getLocale();
        return locale != null && "ja".equalsIgnoreCase(locale.getLanguage()) ? "ja" : "en";
    }

    private record SelectedContent(String locale, SequenceStepDto.ContentDto content) {
    }
}
