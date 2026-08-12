package ooo.klae.connex.backend.services;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto;
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
    private final DataSubjectDisclosureMapper dataSubjectDisclosureMapper;
    private final SqlSessionFactory sqlSessionFactory;
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

    @Transactional(readOnly = true)
    public DataSubjectDisclosureDto assemble(int workspaceId, int personId, List<Integer> workspaceIds) {
        if (workspaceIds.isEmpty()) {
            throw new IllegalArgumentException("A disclosure workspace allowlist cannot be empty");
        }
        PersonDto person = dataSubjectDisclosureMapper.findPerson(workspaceId, personId, workspaceIds);
        if (person == null) {
            throw new ResourceNotFoundException("Linked subject person not found: " + personId);
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
        disclosure.setNotes(dataSubjectDisclosureMapper.findNotes(workspaceId, personId, workspaceIds));
        disclosure.setRecordCommentThreads(
            recordCommentThreads(workspaceId, personId, workspaceIds));
        disclosure.setTasks(dataSubjectDisclosureMapper.findTasks(workspaceId, personId, workspaceIds));
        disclosure.setAttachments(
            dataSubjectDisclosureMapper.findAttachments(workspaceId, personId, workspaceIds));
        disclosure.setEmploymentHistory(
            dataSubjectDisclosureMapper.findEmployment(workspaceId, personId, workspaceIds));
        disclosure.setRelationshipEdges(dataSubjectDisclosureMapper.findEdges(workspaceId, personId, workspaceIds));
        disclosure.setDealAssociations(dataSubjectDisclosureMapper.findDeals(workspaceId, personId, workspaceIds));
        disclosure.setIntroductions(
            dataSubjectDisclosureMapper.findIntroductions(workspaceId, personId, workspaceIds));
        disclosure.setThirdPartyProvisions(
            dataSubjectDisclosureMapper.findProvisions(workspaceId, personId, workspaceIds));
        return disclosure;
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
