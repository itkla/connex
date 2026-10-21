package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import ooo.klae.connex.backend.dto.AttachmentQueryUserFact;
import ooo.klae.connex.backend.exceptions.TenantScopeSnapshotException;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.tenant.ControlPlaneRead;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Pins complete batching and containment without querying a database. */
class AttachmentQueryControlAccessTest {
    private final UserMapper users = mock(UserMapper.class);
    private final TenantWorkScope scope = mock(TenantWorkScope.class);
    private final ObjectMapper json = JsonMapper.builder().build();
    private final AttachmentQueryControlAccess access = new AttachmentQueryControlAccess(users, scope, json);

    private void executeReads() {
        when(scope.unroutedRead(any())).thenAnswer(call -> call.getArgument(0, ControlPlaneRead.class).read());
    }

    @Test
    void emptyCandidatesRequireNoControlRead() {
        assertEquals("[]", access.userFactsJson(List.of(), null));
        verifyNoInteractions(users);
        verify(scope, never()).unroutedRead(any());
    }

    @Test
    void completeUnionIsDeduplicatedAndBatchedWithoutAMatchingCap() {
        executeReads();
        var ids = IntStream.rangeClosed(1, 2_001).boxed().toList();
        when(users.getAttachmentQueryFactsByIds(any(), eq("%name%"))).thenAnswer(call -> {
            List<Integer> batch = call.getArgument(0);
            return batch.stream().map(id -> new AttachmentQueryUserFact(id, "Name " + id, true)).toList();
        });
        var duplicates = new java.util.ArrayList<>(ids);
        duplicates.addAll(ids);

        var result = json.readTree(access.userFactsJson(duplicates, "%name%"));

        assertEquals(2_001, result.size());
        assertEquals(2_001, result.get(2_000).get("id").asInt());
        ArgumentCaptor<List<Integer>> batches = ArgumentCaptor.captor();
        verify(users, times(3)).getAttachmentQueryFactsByIds(batches.capture(), eq("%name%"));
        assertEquals(List.of(1_000, 1_000, 1), batches.getAllValues().stream().map(List::size).toList());
        verify(scope).unroutedRead(any());
    }

    @Test
    void globalForeignAndDepartedNamesArePreservedExactly() {
        executeReads();
        var foreign = new AttachmentQueryUserFact(8, "Foreign \"Émile\" \\ %_", true);
        var departed = new AttachmentQueryUserFact(9, "", false);
        when(users.getAttachmentQueryFactsByIds(List.of(8, 9, 10), "%Émile%"))
            .thenReturn(List.of(foreign, departed));

        var result = json.readTree(access.userFactsJson(List.of(10, 9, 8), "%Émile%"));

        assertEquals(2, result.size());
        assertEquals(foreign.displayName(), result.get(0).get("displayName").asString());
        assertEquals("", result.get(1).get("displayName").asString());
        assertTrue(result.get(0).get("nameMatches").asBoolean());
    }

    @Test
    void escapedOrDuplicateControlFactsRefuseTheWholeOperation() {
        executeReads();
        var row = new AttachmentQueryUserFact(8, "Eight", false);
        when(users.getAttachmentQueryFactsByIds(List.of(7), null)).thenReturn(List.of(row));
        assertThrows(TenantScopeSnapshotException.class, () -> access.userFactsJson(List.of(7), null));
        when(users.getAttachmentQueryFactsByIds(List.of(8), null)).thenReturn(List.of(row, row));
        assertThrows(TenantScopeSnapshotException.class, () -> access.userFactsJson(List.of(8), null));
    }

    @Test
    void laterBatchLookupFailureReturnsNoPartialFacts() {
        executeReads();
        var failure = new org.springframework.dao.DataAccessResourceFailureException("unavailable");
        when(users.getAttachmentQueryFactsByIds(any(), eq(null)))
            .thenReturn(List.of()).thenThrow(failure);
        assertSame(failure, assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
            () -> access.userFactsJson(IntStream.rangeClosed(1, 1_001).boxed().toList(), null)));
    }

    @Test
    void serializationFailureEscapesUnchanged() {
        executeReads();
        when(users.getAttachmentQueryFactsByIds(List.of(7), null))
            .thenReturn(List.of(new AttachmentQueryUserFact(7, "Seven", false)));
        ObjectMapper broken = mock(ObjectMapper.class);
        var failure = new IllegalStateException("serialization unavailable");
        when(broken.writeValueAsString(any())).thenThrow(failure);
        var boundary = new AttachmentQueryControlAccess(users, scope, broken);
        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> boundary.userFactsJson(List.of(7), null)));
    }
}
