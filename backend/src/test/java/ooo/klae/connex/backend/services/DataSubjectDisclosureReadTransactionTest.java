package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.PersonDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.RecordCommentDisclosureDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.RecordCommentThreadDisclosureDto;
import ooo.klae.connex.backend.mappers.DataSubjectDisclosureMapper;

@ExtendWith(MockitoExtension.class)
class DataSubjectDisclosureReadTransactionTest {
    @Mock private DataSubjectDisclosureMapper dataSubjectDisclosureMapper;
    @Mock private DataSubjectDisclosureMapper lockedMapper;
    @Mock private SqlSessionFactory sqlSessionFactory;
    @Mock private SqlSession sqlSession;
    @Mock private Connection connection;

    private DataSubjectDisclosureReadTransaction readTransaction;

    @BeforeEach
    void setUp() {
        readTransaction = new DataSubjectDisclosureReadTransaction(
            dataSubjectDisclosureMapper,
            sqlSessionFactory);
        lenient().when(sqlSessionFactory.openSession(false)).thenReturn(sqlSession);
        lenient().when(sqlSession.getConnection()).thenReturn(connection);
        lenient().when(sqlSession.getMapper(DataSubjectDisclosureMapper.class))
            .thenReturn(lockedMapper);
    }

    @Test
    void reservesTheTenantSessionBeforeControlRootsAndLocksThePersonInsideThem()
            throws Exception {
        AtomicBoolean controlRootsHeld = new AtomicBoolean();
        when(dataSubjectDisclosureMapper.subjectPersonExists(4, 5)).thenReturn(true);
        when(lockedMapper.lockSubjectPersonForShare(4, 5)).thenReturn(5);

        String result = readTransaction.withLockedSubjectPerson(
            4,
            5,
            lockedPersonWork -> {
                verify(lockedMapper, never()).lockSubjectPersonForShare(4, 5);
                controlRootsHeld.set(true);
                return lockedPersonWork.get();
            },
            () -> {
                assertEquals(true, controlRootsHeld.get());
                return "written";
            });

        assertEquals("written", result);
        InOrder order = inOrder(
            sqlSessionFactory,
            sqlSession,
            connection,
            dataSubjectDisclosureMapper,
            lockedMapper);
        order.verify(sqlSessionFactory).openSession(false);
        order.verify(sqlSession).getConnection();
        order.verify(connection).setAutoCommit(false);
        order.verify(sqlSession).getMapper(DataSubjectDisclosureMapper.class);
        order.verify(dataSubjectDisclosureMapper).subjectPersonExists(4, 5);
        order.verify(lockedMapper).lockSubjectPersonForShare(4, 5);
        order.verify(connection).rollback();
        order.verify(sqlSession).close();
    }

    @Test
    void anInitiallyMissingPersonAbortsBeforeControlRoots() throws Exception {
        AtomicBoolean controlTransaction = new AtomicBoolean();
        AtomicBoolean controlWrite = new AtomicBoolean();

        assertThrows(
            BadRequestException.class,
            () -> readTransaction.withLockedSubjectPerson(
                4,
                5,
                lockedPersonWork -> {
                    controlTransaction.set(true);
                    return lockedPersonWork.get();
                },
                () -> {
                    controlWrite.set(true);
                    return "unused";
                }));

        assertEquals(false, controlTransaction.get());
        assertEquals(false, controlWrite.get());
        verify(lockedMapper, never()).lockSubjectPersonForShare(4, 5);
        verify(connection).rollback();
        verify(sqlSession).close();
    }

    @Test
    void aPersonRemovedAfterInitialProofIsAConflict() throws Exception {
        AtomicBoolean controlWrite = new AtomicBoolean();
        when(dataSubjectDisclosureMapper.subjectPersonExists(4, 5)).thenReturn(true);
        when(lockedMapper.lockSubjectPersonForShare(4, 5)).thenReturn(null);

        assertThrows(
            ConflictException.class,
            () -> readTransaction.withLockedSubjectPerson(
                4,
                5,
                lockedPersonWork -> lockedPersonWork.get(),
                () -> {
                    controlWrite.set(true);
                    return "unused";
                }));

        assertEquals(false, controlWrite.get());
        verify(lockedMapper).lockSubjectPersonForShare(4, 5);
        verify(connection).rollback();
        verify(sqlSession).close();
    }

    @Test
    void rollbackFailureIsSuppressedOntoTheControlFailure() throws Exception {
        IllegalStateException primary = new IllegalStateException("control failed");
        SQLException rollbackFailure = new SQLException("rollback failed");
        when(dataSubjectDisclosureMapper.subjectPersonExists(4, 5)).thenReturn(true);
        doThrow(rollbackFailure).when(connection).rollback();

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> readTransaction.withLockedSubjectPerson(
                4,
                5,
                lockedPersonWork -> {
                    throw primary;
                },
                () -> "unused"));

        assertEquals(primary, thrown);
        assertEquals(List.of(rollbackFailure), List.of(thrown.getSuppressed()));
        verify(sqlSession).close();
    }

    @Test
    void disclosureAssemblyNestsActiveAndRedactedCommentsUnderTheirThread() {
        PersonDto person = new PersonDto();
        person.setId(5);
        RecordCommentThreadDisclosureDto thread = new RecordCommentThreadDisclosureDto();
        thread.setId(11L);
        RecordCommentDisclosureDto active = new RecordCommentDisclosureDto();
        active.setId(21L);
        active.setThreadId(11L);
        active.setContent("Subject comment");
        RecordCommentDisclosureDto redacted = new RecordCommentDisclosureDto();
        redacted.setId(22L);
        redacted.setThreadId(11L);
        redacted.setContent(null);
        redacted.setDeletedAt(java.time.LocalDateTime.of(2026, 1, 2, 3, 4));
        when(dataSubjectDisclosureMapper.findPerson(4, 5, List.of(4, 6))).thenReturn(person);
        when(dataSubjectDisclosureMapper.findRecordCommentThreads(4, 5, List.of(4, 6)))
            .thenReturn(List.of(thread));
        when(dataSubjectDisclosureMapper.findRecordComments(4, 5, List.of(4, 6)))
            .thenReturn(List.of(active, redacted));

        var disclosure = readTransaction.assemble(4, 5, List.of(4, 6));

        assertEquals(1, disclosure.getRecordCommentThreads().size());
        assertEquals(List.of(21L, 22L), disclosure.getRecordCommentThreads().getFirst()
            .getComments().stream().map(RecordCommentDisclosureDto::getId).toList());
        assertEquals("Subject comment", disclosure.getRecordCommentThreads().getFirst()
            .getComments().getFirst().getContent());
        assertNull(disclosure.getRecordCommentThreads().getFirst()
            .getComments().getLast().getContent());
        assertEquals(redacted.getDeletedAt(), disclosure.getRecordCommentThreads().getFirst()
            .getComments().getLast().getDeletedAt());
    }
}
