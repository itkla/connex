package ooo.klae.connex.backend.services;

import java.io.StringWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.PersonDisqualificationReason;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.NoteDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.PersonDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.RecordCommentDisclosureDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.RecordCommentThreadDisclosureDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.mappers.DataSubjectDisclosureMapper;

/**
 * Reads a complete subject disclosure from one routed tenant-catalog snapshot.
 *
 * <p>Linked request writes reserve one routed tenant session before the control
 * transaction takes roots. One fair admission permit prevents reserved sessions
 * from exhausting the shared pool. The reserved session owns a non-auto-commit
 * connection but stays SQL-idle while a separate short session proves the person.
 * The control callback then takes roots, locks the person on the reserved session,
 * and commits the control write before that tenant transaction rolls back.
 */
@Component
@RequiredArgsConstructor
public class DataSubjectDisclosureReadTransaction {
    private static final int NOTE_PAGE_SIZE = 100;
    private final DataSubjectDisclosureMapper dataSubjectDisclosureMapper;
    private final SqlSessionFactory sqlSessionFactory;
    private final ObjectMapper objectMapper;
    private final Semaphore linkedMutationAdmission = new Semaphore(1, true);

    @Transactional(readOnly = true)
    public boolean subjectPersonExists(int workspaceId, int personId) {
        return dataSubjectDisclosureMapper.subjectPersonExists(workspaceId, personId);
    }

    public <T> T withLockedSubjectPerson(
            int workspaceId,
            int personId,
            Function<Supplier<T>, T> controlTransaction,
            Supplier<T> work) {
        acquireLinkedMutationAdmission();
        try (SqlSession session = sqlSessionFactory.openSession(false)) {
            Connection connection = session.getConnection();
            beginReservedTransaction(connection);
            DataSubjectDisclosureMapper lockedMapper =
                session.getMapper(DataSubjectDisclosureMapper.class);
            T result;
            try {
                if (!dataSubjectDisclosureMapper.subjectPersonExists(workspaceId, personId)) {
                    throw new BadRequestException(
                        "Subject person must exist in a workspace belonging to the organization");
                }
                result = controlTransaction.apply(() -> {
                    if (lockedMapper.lockSubjectPersonForShare(
                            workspaceId,
                            personId) == null) {
                        throw new ConflictException(
                            "The subject person changed before the data-subject request could be recorded");
                    }
                    return work.get();
                });
            } catch (RuntimeException | Error failure) {
                rollbackReservedTransaction(connection, failure);
                throw failure;
            }
            rollbackReservedTransaction(connection, null);
            return result;
        } finally {
            linkedMutationAdmission.release();
        }
    }

    private static void beginReservedTransaction(Connection connection) {
        try {
            connection.setAutoCommit(false);
        } catch (SQLException exception) {
            throw new ServiceUnavailableException(
                "Data-subject request validation could not reserve a tenant transaction",
                exception);
        }
    }

    private static void rollbackReservedTransaction(Connection connection, Throwable primary) {
        try {
            connection.rollback();
        } catch (SQLException exception) {
            if (primary != null) {
                primary.addSuppressed(exception);
                return;
            }
            throw new ServiceUnavailableException(
                "Data-subject request validation could not release its tenant transaction",
                exception);
        }
    }

    private void acquireLinkedMutationAdmission() {
        try {
            linkedMutationAdmission.acquire();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ServiceUnavailableException(
                "Data-subject request validation was interrupted");
        }
    }

    /**
     * Serializes every eligible subject note into one JSON array, reading bodies in pages of at
     * most {@link #NOTE_PAGE_SIZE} preflight IDs so only one page of note objects is live at a
     * time. The serialized array is accumulated in full, which is the disclosure's payload bound.
     */
    private String disclosureNotes(int workspaceId, int personId, List<Integer> workspaceIds) {
        List<Integer> noteIds = dataSubjectDisclosureMapper.findNoteIds(workspaceId, personId, workspaceIds);
        StringWriter payload = new StringWriter();
        try (JsonGenerator generator = objectMapper.createGenerator(payload)) {
            generator.writeStartArray();
            for (int start = 0; start < noteIds.size(); start += NOTE_PAGE_SIZE) {
                appendNotePage(workspaceId, personId, workspaceIds,
                    noteIds.subList(start, Math.min(start + NOTE_PAGE_SIZE, noteIds.size())), generator);
            }
            generator.writeEndArray();
        }
        return payload.toString();
    }

    private void appendNotePage(int workspaceId, int personId, List<Integer> workspaceIds,
            List<Integer> noteIds, JsonGenerator generator) {
        List<NoteDto> notes = dataSubjectDisclosureMapper.findNotePage(workspaceId, personId, workspaceIds, noteIds);
        if (!notes.stream().map(NoteDto::getId).toList().equals(noteIds)) {
            throw new ResourceNotFoundException("Disclosure note no longer available");
        }
        for (NoteDto note : notes) {
            objectMapper.writeValue(generator, note);
        }
    }

    /**
     * Assembles the complete subject disclosure inside one read-only transaction.
     *
     * <p>The single transaction is load-bearing for the note pages. Under the deployed MySQL
     * default of REPEATABLE READ, the ID preflight and every body page share one snapshot, so the
     * page-completeness refusal in {@link #appendNotePage} cannot be reached by a concurrent note
     * delete. If these reads are ever split across transactions or sessions, or the isolation
     * level is relaxed to READ COMMITTED, that refusal becomes reachable and would abort a
     * verified statutory export; tolerate removals by intersecting with the surviving IDs instead.
     */
    @Transactional(readOnly = true)
    public DataSubjectDisclosureDto assemble(int workspaceId, int personId, List<Integer> workspaceIds) {
        if (workspaceIds.isEmpty()) {
            throw new IllegalArgumentException("A disclosure workspace allowlist cannot be empty");
        }
        PersonDto person = dataSubjectDisclosureMapper.findPerson(workspaceId, personId, workspaceIds);
        if (person == null) {
            throw new ResourceNotFoundException("Linked subject person not found: " + personId);
        }
        Locale locale = LocaleContextHolder.getLocale();
        if (person.getDisqualifiedReason() != null) {
            person.setDisqualifiedReasonLabel(reasonLabel(
                person.getDisqualifiedReason(), person.getDisqualifiedReasonLabel(), locale));
        }
        DataSubjectDisclosureDto disclosure = new DataSubjectDisclosureDto();
        disclosure.setPerson(person);
        disclosure.setIdentities(
            dataSubjectDisclosureMapper.findIdentities(workspaceId, personId, workspaceIds));
        disclosure.setTags(dataSubjectDisclosureMapper.findTags(workspaceId, personId, workspaceIds));
        disclosure.setCustomFieldValues(
            dataSubjectDisclosureMapper.findCustomFields(workspaceId, personId, workspaceIds));
        disclosure.setActivities(dataSubjectDisclosureMapper.findActivities(workspaceId, personId, workspaceIds));
        disclosure.setProviderCaptureEvidence(
            dataSubjectDisclosureMapper.findProviderCaptureEvidence(
                workspaceId, personId, workspaceIds));
        disclosure.setNotes(disclosureNotes(workspaceId, personId, workspaceIds));
        disclosure.setRecordCommentThreads(
            recordCommentThreads(workspaceId, personId, workspaceIds));
        disclosure.setTasks(dataSubjectDisclosureMapper.findTasks(workspaceId, personId, workspaceIds));
        disclosure.setAttachments(
            dataSubjectDisclosureMapper.findAttachments(workspaceId, personId, workspaceIds));
        disclosure.setEmploymentHistory(
            dataSubjectDisclosureMapper.findEmployment(workspaceId, personId, workspaceIds));
        var lifecycleHistory =
            dataSubjectDisclosureMapper.findLifecycleHistory(workspaceId, personId, workspaceIds);
        lifecycleHistory.forEach(transition -> {
            if (transition.getReason() != null) {
                transition.setReasonLabel(reasonLabel(
                    transition.getReason(), transition.getReasonLabel(), locale));
            }
        });
        disclosure.setLifecycleHistory(lifecycleHistory);
        disclosure.setLifecyclePasses(
            dataSubjectDisclosureMapper.findLifecyclePasses(workspaceId, personId, workspaceIds));
        disclosure.setQualificationAnswers(
            dataSubjectDisclosureMapper.findQualificationAnswers(workspaceId, personId, workspaceIds));
        disclosure.setRelationshipEdges(dataSubjectDisclosureMapper.findEdges(workspaceId, personId, workspaceIds));
        disclosure.setDealAssociations(dataSubjectDisclosureMapper.findDeals(workspaceId, personId, workspaceIds));
        disclosure.setIntroductions(
            dataSubjectDisclosureMapper.findIntroductions(workspaceId, personId, workspaceIds));
        disclosure.setThirdPartyProvisions(
            dataSubjectDisclosureMapper.findProvisions(workspaceId, personId, workspaceIds));
        disclosure.setConsentState(
            dataSubjectDisclosureMapper.findConsentState(workspaceId, personId, workspaceIds));
        disclosure.setConsentHistory(
            dataSubjectDisclosureMapper.findConsentHistory(workspaceId, personId, workspaceIds));
        disclosure.setAudienceExportEvidence(
            dataSubjectDisclosureMapper.findAudienceExportEvidence(workspaceId, personId, workspaceIds));
        return disclosure;
    }

    private static String reasonLabel(String code, String configuredLabel, Locale locale) {
        if (!PersonDisqualificationReason.isCanonicalCode(code)) {
            return code;
        }
        return configuredLabel == null
            ? PersonDisqualificationReason.localizedLabel(code, locale)
            : configuredLabel;
    }

    private List<RecordCommentThreadDisclosureDto> recordCommentThreads(
            int workspaceId,
            int personId,
            List<Integer> workspaceIds) {
        List<RecordCommentThreadDisclosureDto> threads =
            dataSubjectDisclosureMapper.findRecordCommentThreads(
                workspaceId, personId, workspaceIds);
        List<RecordCommentDisclosureDto> comments = dataSubjectDisclosureMapper.findRecordComments(
            workspaceId, personId, workspaceIds);
        Map<Long, List<RecordCommentDisclosureDto>> commentsByThread = comments.stream().collect(
            Collectors.groupingBy(
                RecordCommentDisclosureDto::getThreadId,
                LinkedHashMap::new,
                Collectors.toList()));
        threads.forEach(thread -> thread.setComments(
            commentsByThread.getOrDefault(thread.getId(), List.of())));
        return threads;
    }
}
