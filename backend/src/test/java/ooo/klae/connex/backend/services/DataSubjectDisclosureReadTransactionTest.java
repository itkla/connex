package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doNothing;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.ref.WeakReference;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.PersonDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.NoteDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.LifecycleTransitionDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.RecordCommentDisclosureDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.RecordCommentThreadDisclosureDto;
import ooo.klae.connex.backend.mappers.DataSubjectDisclosureMapper;

@ExtendWith(MockitoExtension.class)
class DataSubjectDisclosureReadTransactionTest {
    private final DataSubjectDisclosureDelivery delivery = new DataSubjectDisclosureDelivery(120_000,
        org.mockito.Mockito.mock(javax.sql.DataSource.class));

    @AfterEach
    void closeDelivery() {
        delivery.close();
    }

    @Mock private DataSubjectDisclosureMapper dataSubjectDisclosureMapper;
    @Mock private DataSubjectDisclosureMapper lockedMapper;
    @Mock private SqlSessionFactory sqlSessionFactory;
    @Mock private org.apache.ibatis.session.Configuration configuration;
    @Mock private org.apache.ibatis.executor.Executor executor;
    @Mock private org.apache.ibatis.transaction.Transaction transaction;
    @Mock private Connection connection;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private DataSubjectDisclosureReadTransaction readTransaction;

    @BeforeEach
    void setUp() throws Exception {
        readTransaction = new DataSubjectDisclosureReadTransaction(
            dataSubjectDisclosureMapper,
            sqlSessionFactory);
        lenient().when(sqlSessionFactory.getConfiguration()).thenReturn(configuration);
        var dataSource = org.mockito.Mockito.mock(javax.sql.DataSource.class);
        lenient().when(dataSource.getConnection()).thenReturn(connection);
        lenient().when(configuration.getEnvironment()).thenReturn(new org.apache.ibatis.mapping.Environment(
            "reservation", new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), dataSource));
        lenient().when(configuration.newExecutor(org.mockito.ArgumentMatchers.any(),
            eq(org.apache.ibatis.session.ExecutorType.SIMPLE))).thenReturn(executor);
        lenient().when(executor.getTransaction()).thenReturn(transaction);
        lenient().when(transaction.getConnection()).thenReturn(connection);
        lenient().when(connection.getAutoCommit()).thenReturn(true);
        lenient().when(configuration.getMapper(eq(DataSubjectDisclosureMapper.class), org.mockito.ArgumentMatchers.any()))
            .thenReturn(lockedMapper);
    }

    @Test
    void reservesTheTenantSessionBeforeControlRootsAndLocksThePersonInsideThem()
            throws Exception {
        AtomicBoolean controlRootsHeld = new AtomicBoolean();
        when(lockedMapper.subjectPersonExists(4, 5)).thenReturn(true);
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
            configuration,
            executor,
            transaction,
            connection,
            dataSubjectDisclosureMapper,
            lockedMapper);
        order.verify(sqlSessionFactory).getConfiguration();
        order.verify(configuration).newExecutor(org.mockito.ArgumentMatchers.any(), eq(org.apache.ibatis.session.ExecutorType.SIMPLE));
        order.verify(transaction).getConnection();
        order.verify(connection).setAutoCommit(false);
        order.verify(configuration).getMapper(eq(DataSubjectDisclosureMapper.class), org.mockito.ArgumentMatchers.any());
        order.verify(lockedMapper).subjectPersonExists(4, 5);
        order.verify(lockedMapper).lockSubjectPersonForShare(4, 5);
        order.verify(connection).rollback();
        order.verify(connection).setAutoCommit(true);
        order.verify(executor).close(false);
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
        verify(connection).setAutoCommit(true);
        verify(executor).close(false);
    }

    @Test
    void aPersonRemovedAfterInitialProofIsAConflict() throws Exception {
        AtomicBoolean controlWrite = new AtomicBoolean();
        when(lockedMapper.subjectPersonExists(4, 5)).thenReturn(true);
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
        verify(executor).close(false);
    }

    @Test
    void rollbackFailureIsSuppressedOntoTheControlFailure() throws Exception {
        IllegalStateException primary = new IllegalStateException("control failed");
        SQLException rollbackFailure = new SQLException("rollback failed");
        when(lockedMapper.subjectPersonExists(4, 5)).thenReturn(true);
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
        verify(connection, never()).setAutoCommit(true);
        verify(executor).close(false);
    }

    @Test
    void rollbackPreservesAnOriginallyManualConnection() throws Exception {
        when(connection.getAutoCommit()).thenReturn(false);
        when(lockedMapper.subjectPersonExists(4, 5)).thenReturn(true);
        when(lockedMapper.lockSubjectPersonForShare(4, 5)).thenReturn(5);

        assertEquals("written", readTransaction.withLockedSubjectPerson(
            4, 5, work -> work.get(), () -> "written"));

        verify(connection).rollback();
        verify(connection, never()).setAutoCommit(true);
        verify(executor).close(false);
    }

    @Test
    void restorationFailureIsSuppressedOntoTheControlFailure() throws Exception {
        IllegalStateException primary = new IllegalStateException("control failed");
        SQLException restorationFailure = new SQLException("restoration failed");
        when(lockedMapper.subjectPersonExists(4, 5)).thenReturn(true);
        doThrow(restorationFailure).when(connection).setAutoCommit(true);
        doNothing().when(connection).setAutoCommit(false);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> readTransaction.withLockedSubjectPerson(4, 5, work -> {
                throw primary;
            }, () -> "unused"));

        assertEquals(primary, thrown);
        assertEquals(List.of(restorationFailure), List.of(thrown.getSuppressed()));
        verify(connection).rollback();
        verify(executor).close(false);
    }

    @Test
    void restorationFailureAfterSuccessfulControlWorkRefusesSuccess() throws Exception {
        SQLException restorationFailure = new SQLException("restoration failed");
        when(lockedMapper.subjectPersonExists(4, 5)).thenReturn(true);
        when(lockedMapper.lockSubjectPersonForShare(4, 5)).thenReturn(5);
        doThrow(restorationFailure).when(connection).setAutoCommit(true);
        doNothing().when(connection).setAutoCommit(false);

        ServiceUnavailableException thrown = assertThrows(ServiceUnavailableException.class,
            () -> readTransaction.withLockedSubjectPerson(4, 5, work -> work.get(), () -> "written"));

        assertEquals(restorationFailure, thrown.getCause());
        verify(connection).rollback();
        verify(executor).close(false);
    }

    @Test
    void disclosureSerializesEveryNoteAcrossBoundedPages() {
        when(dataSubjectDisclosureMapper.findPerson(4, 5, List.of(4))).thenReturn(new PersonDto());
        List<Integer> noteIds = IntStream.rangeClosed(1, 250).boxed().toList();
        when(dataSubjectDisclosureMapper.findNoteIds(eq(4), eq(5), eq(List.of(4)), any()))
            .thenAnswer(invocation -> {
                Integer afterId = invocation.getArgument(3);
                return noteIds.stream().filter(id -> afterId == null || id > afterId).limit(100).toList();
            });
        for (int start = 0; start < noteIds.size(); start += 100) {
            List<Integer> pageIds = noteIds.subList(start, Math.min(start + 100, noteIds.size()));
            List<NoteDto> page = pageIds.stream().map(id -> {
                NoteDto note = new NoteDto();
                note.setId(id);
                note.setContent("Complete \"disclosure\"\n内容 " + id + "x".repeat(600));
                return note;
            }).toList();
            when(dataSubjectDisclosureMapper.findNotePage(4, 5, List.of(4), pageIds)).thenReturn(page);
        }

        JsonNode notes = readTransaction.withSnapshot(4, 5, List.of(4), disclosure -> {
            var writer = new DataSubjectDisclosureJsonWriter(objectMapper, delivery);
            var fingerprint = writer.measure(disclosure);
            var output = new java.io.ByteArrayOutputStream();
            writer.write(disclosure, fingerprint, measured -> output);
            return objectMapper.readTree(output.toByteArray()).path("notes");
        });

        assertEquals(250, notes.size());
        for (int index = 0; index < 250; index++) {
            assertEquals(index + 1, notes.get(index).path("id").asInt());
            assertEquals("Complete \"disclosure\"\n内容 " + (index + 1) + "x".repeat(600),
                notes.get(index).path("content").asString());
        }
        verify(dataSubjectDisclosureMapper, times(2)).findNoteIds(4, 5, List.of(4), null);
        verify(dataSubjectDisclosureMapper, times(6)).findNotePage(eq(4), eq(5), eq(List.of(4)), anyList());
    }

    @Test
    void disclosureRetainsAtMostOnePageOfNoteBodies() {
        when(dataSubjectDisclosureMapper.findPerson(4, 5, List.of(4))).thenReturn(new PersonDto());
        List<Integer> noteIds = IntStream.rangeClosed(1, 250).boxed().toList();
        when(dataSubjectDisclosureMapper.findNoteIds(eq(4), eq(5), eq(List.of(4)), any()))
            .thenAnswer(invocation -> {
                Integer afterId = invocation.getArgument(3);
                return noteIds.stream().filter(id -> afterId == null || id > afterId).limit(100).toList();
            });
        List<WeakReference<List<NoteDto>>> issuedPages = new ArrayList<>();
        List<WeakReference<NoteDto>> issuedNotes = new ArrayList<>();
        when(dataSubjectDisclosureMapper.findNotePage(eq(4), eq(5), eq(List.of(4)), anyList()))
            .thenAnswer(invocation -> {
                issuedPages.forEach(DataSubjectDisclosureReadTransactionTest::assertPageWasReleased);
                issuedNotes.forEach(DataSubjectDisclosureReadTransactionTest::assertPageWasReleased);
                List<Integer> pageIds = invocation.getArgument(3);
                List<NoteDto> page = new ArrayList<>();
                for (int noteId : pageIds) {
                    NoteDto note = new NoteDto();
                    note.setId(noteId);
                    note.setContent("Subject note body ".repeat(512));
                    page.add(note);
                    issuedNotes.add(new WeakReference<>(note));
                }
                issuedPages.add(new WeakReference<>(page));
                return page;
            });

        readTransaction.withSnapshot(4, 5, List.of(4), disclosure -> {
            var writer = new DataSubjectDisclosureJsonWriter(objectMapper, delivery);
            var fingerprint = writer.measure(disclosure);
            writer.write(disclosure, fingerprint, measured -> java.io.OutputStream.nullOutputStream());
            return null;
        });

        assertEquals(6, issuedPages.size());
        assertEquals(500, issuedNotes.size());
        issuedNotes.forEach(DataSubjectDisclosureReadTransactionTest::assertPageWasReleased);
        issuedPages.forEach(DataSubjectDisclosureReadTransactionTest::assertPageWasReleased);
    }

    /**
     * Fails unless the supplied note page has become unreachable, so peak retention stays at one
     * page of note objects rather than the whole corpus.
     */
    private static void assertPageWasReleased(WeakReference<?> page) {
        for (int attempt = 0; attempt < 20 && page.get() != null; attempt++) {
            System.gc();
        }
        assertNull(page.get(), "A note page or note must not outlive its write in the current pass");
    }

    @Test
    void disclosureRefusesAnIncompleteBodyPage() {
        when(dataSubjectDisclosureMapper.findPerson(4, 5, List.of(4))).thenReturn(new PersonDto());
        NoteDto full = new NoteDto();
        full.setId(7);
        full.setContent("Complete disclosure content");
        when(dataSubjectDisclosureMapper.findNoteIds(4, 5, List.of(4), null)).thenReturn(List.of(7, 8));
        when(dataSubjectDisclosureMapper.findNotePage(4, 5, List.of(4), List.of(7, 8)))
            .thenReturn(List.of(full));

        assertThrows(ResourceNotFoundException.class, () -> readTransaction.withSnapshot(4, 5, List.of(4), value -> {
                new DataSubjectDisclosureJsonWriter(objectMapper, delivery).measure(value);
                return null;
            }));
    }

    @Test
    void disclosureWithoutNotesSkipsBodyQueries() {
        when(dataSubjectDisclosureMapper.findPerson(4, 5, List.of(4))).thenReturn(new PersonDto());

        int count = readTransaction.withSnapshot(4, 5, List.of(4), value ->
            objectMapper.readTree(objectMapper.writeValueAsString(value)).path("notes").size());
        assertEquals(0, count);
        verify(dataSubjectDisclosureMapper, never()).findNotePage(anyInt(), anyInt(), anyList(), anyList());
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
        when(dataSubjectDisclosureMapper.findLifecycleHistory(4, 5, List.of(4, 6)))
            .thenReturn(List.of());

        var disclosure = readTransaction.withSnapshot(4, 5, List.of(4, 6), value -> value);

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

    @Test
    void disclosureDoesNotResolveNoncanonicalCodesThroughConfiguredLabels() {
        PersonDto person = new PersonDto();
        person.setId(5);
        person.setDisqualifiedReason("other");
        person.setDisqualifiedReasonLabel("Configured label");
        LifecycleTransitionDto transition = new LifecycleTransitionDto();
        transition.setReason("ÖTHER");
        transition.setReasonLabel("Configured history label");
        when(dataSubjectDisclosureMapper.findPerson(4, 5, List.of(4))).thenReturn(person);
        when(dataSubjectDisclosureMapper.findLifecycleHistory(4, 5, List.of(4)))
            .thenReturn(List.of(transition));

        var disclosure = readTransaction.withSnapshot(4, 5, List.of(4), value -> value);

        assertEquals("other", disclosure.getPerson().getDisqualifiedReasonLabel());
        assertEquals("ÖTHER", disclosure.getLifecycleHistory().getFirst().getReasonLabel());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 100, 101})
    void bothPassesTraverseEmptyExactAndPartialPages(int count) {
        when(dataSubjectDisclosureMapper.findPerson(4, 5, List.of(4))).thenReturn(new PersonDto());
        when(dataSubjectDisclosureMapper.findNoteIds(eq(4), eq(5), eq(List.of(4)), any()))
            .thenAnswer(invocation -> {
                Integer cursor = invocation.getArgument(3);
                return IntStream.rangeClosed(1, count).boxed()
                    .filter(id -> cursor == null || id > cursor).limit(100).toList();
            });
        if (count > 0) {
            when(dataSubjectDisclosureMapper.findNotePage(eq(4), eq(5), eq(List.of(4)), anyList()))
                .thenAnswer(invocation -> invocation.<List<Integer>>getArgument(3)
                    .stream().map(id -> {
                        NoteDto note = new NoteDto();
                        note.setId(id);
                        return note;
                    }).toList());
        }
        readTransaction.withSnapshot(4, 5, List.of(4), disclosure -> {
            var writer = new DataSubjectDisclosureJsonWriter(objectMapper, delivery);
            var fingerprint = writer.measure(disclosure);
            assertEquals(count, fingerprint.noteCount());
            writer.write(disclosure, fingerprint, measured -> java.io.OutputStream.nullOutputStream());
            return null;
        });
        verify(dataSubjectDisclosureMapper, times(2 * ((count + 99) / 100)))
            .findNotePage(eq(4), eq(5), eq(List.of(4)), anyList());
    }

    @Test
    void noteSourceIsInvalidatedAfterSuccessAndFailure() {
        when(dataSubjectDisclosureMapper.findPerson(4, 5, List.of(4))).thenReturn(new PersonDto());
        DataSubjectDisclosureDto escaped = readTransaction.withSnapshot(4, 5, List.of(4), value -> value);
        assertThrows(RuntimeException.class, () -> objectMapper.writeValueAsString(escaped));
        DataSubjectDisclosureDto[] failed = new DataSubjectDisclosureDto[1];
        assertThrows(IllegalStateException.class, () -> readTransaction.withSnapshot(4, 5, List.of(4), value -> {
            failed[0] = value;
            throw new IllegalStateException("callback failure");
        }));
        assertThrows(RuntimeException.class, () -> objectMapper.writeValueAsString(failed[0]));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"oversized", "nonadvancing", "unordered"})
    void malformedIdPagesFailBeforeAnyBodyRead(String shape) {
        when(dataSubjectDisclosureMapper.findPerson(4, 5, List.of(4))).thenReturn(new PersonDto());
        List<Integer> ids = switch (shape) {
            case "oversized" -> IntStream.rangeClosed(1, 101).boxed().toList();
            case "nonadvancing" -> List.of(1, 1);
            case "unordered" -> List.of(2, 1);
            default -> throw new IllegalArgumentException(shape);
        };
        when(dataSubjectDisclosureMapper.findNoteIds(4, 5, List.of(4), null)).thenReturn(ids);
        assertThrows(IllegalStateException.class, () -> readTransaction.withSnapshot(4, 5, List.of(4), value -> {
            new DataSubjectDisclosureJsonWriter(objectMapper, delivery).measure(value);
            return null;
        }));
        verify(dataSubjectDisclosureMapper, never()).findNotePage(anyInt(), anyInt(), anyList(), anyList());
    }

    @Test
    void noteSourceRefusesAnotherThreadWhileItsSnapshotIsStillActive() {
        when(dataSubjectDisclosureMapper.findPerson(4, 5, List.of(4))).thenReturn(new PersonDto());
        readTransaction.withSnapshot(4, 5, List.of(4), value -> {
            try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                executor.submit(() -> assertThrows(RuntimeException.class,
                    () -> objectMapper.writeValueAsString(value))).get(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
            return null;
        });
        verify(dataSubjectDisclosureMapper, never()).findNoteIds(anyInt(), anyInt(), anyList(), any());
    }

    @Test
    void nullableCursorPreservesTheFullSignedIdRange() {
        when(dataSubjectDisclosureMapper.findPerson(4, 5, List.of(4))).thenReturn(new PersonDto());
        List<Integer> ids = List.of(Integer.MIN_VALUE, 0, Integer.MAX_VALUE);
        when(dataSubjectDisclosureMapper.findNoteIds(4, 5, List.of(4), null)).thenReturn(ids);
        when(dataSubjectDisclosureMapper.findNotePage(4, 5, List.of(4), ids)).thenReturn(ids.stream().map(id -> {
            NoteDto note = new NoteDto();
            note.setId(id);
            return note;
        }).toList());
        readTransaction.withSnapshot(4, 5, List.of(4), value -> {
            var writer = new DataSubjectDisclosureJsonWriter(objectMapper, delivery);
            var measured = writer.measure(value);
            assertEquals(3, measured.noteCount());
            writer.write(value, measured, fingerprint -> java.io.OutputStream.nullOutputStream());
            return null;
        });
        verify(dataSubjectDisclosureMapper, times(2)).findNoteIds(4, 5, List.of(4), Integer.MAX_VALUE);
    }

}
