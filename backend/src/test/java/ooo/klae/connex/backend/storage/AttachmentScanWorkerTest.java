package ooo.klae.connex.backend.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.AttachmentScanMapper;
import ooo.klae.connex.backend.services.PlacementRegistry;
import ooo.klae.connex.backend.services.SystemActor;
import ooo.klae.connex.backend.storage.malware.MalwareScanProperties;
import ooo.klae.connex.backend.storage.malware.MalwareScannerClient;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Pins bounded catalog rotation independently of scanner/provider latency. */
class AttachmentScanWorkerTest {
    @Test
    void catalogCursorsAdvanceThroughIdleWorkspacesAndWrapDespiteNewArrivals() {
        AttachmentScanMapper scans = mock(AttachmentScanMapper.class);
        PlacementRegistry placements = mock(PlacementRegistry.class);
        TenantWorkScope scope = mock(TenantWorkScope.class);
        SystemActor actor = mock(SystemActor.class);
        User user = new User();
        user.setId(99);
        when(actor.user()).thenReturn(user);
        when(placements.activeCatalogs()).thenReturn(List.of("a", "b"));
        AtomicReference<String> catalog = new AtomicReference<>();
        Map<String, TreeSet<Integer>> ids = Map.of(
            "a", new TreeSet<>(IntStream.rangeClosed(1, 26).boxed().toList()),
            "b", new TreeSet<>(List.of(101, 102)));
        when(scope.unrouted(any())).thenAnswer(invocation -> {
            if (invocation.getArgument(0) instanceof Supplier<?> work) {
                return work.get();
            }
            throw new IllegalArgumentException("Expected scope supplier");
        });
        when(scope.withCatalog(anyString(), any())).thenAnswer(invocation -> {
            catalog.set(invocation.getArgument(0, String.class));
            if (invocation.getArgument(1) instanceof Supplier<?> work) {
                return work.get();
            }
            throw new IllegalArgumentException("Expected catalog supplier");
        });
        doAnswer(invocation -> {
            if (invocation.getArgument(1) instanceof Runnable work) {
                work.run();
                return null;
            }
            throw new IllegalArgumentException("Expected workspace operation");
        }).when(scope).inWorkspace(anyInt(), any(Runnable.class));
        when(scans.lastWorkspaceId()).thenAnswer(invocation -> ids.get(catalog.get()).last());
        when(scans.nextWorkspaceId(anyInt(), anyInt())).thenAnswer(invocation -> {
            Integer next = ids.get(catalog.get()).higher(invocation.getArgument(0, Integer.class));
            return next != null && next <= invocation.getArgument(1, Integer.class) ? next : null;
        });
        List<Integer> visited = new ArrayList<>();
        when(scans.findDue(anyInt(), anyInt())).thenAnswer(invocation -> {
            visited.add(invocation.getArgument(0, Integer.class));
            return List.of();
        });
        MalwareScanProperties properties = new MalwareScanProperties();
        properties.setEnabled(true);
        AttachmentScanWorker worker = new AttachmentScanWorker(scans, mock(AttachmentScanTransactions.class),
            mock(ManagedObjectService.class), mock(ObjectStorage.class), mock(ManagedObjectReadAdmissionService.class),
            mock(MalwareScannerClient.class), properties, placements, scope, actor);

        worker.sweep();
        List<Integer> firstPage = new ArrayList<>(IntStream.rangeClosed(1, 25).boxed().toList());
        firstPage.addAll(List.of(101, 102));
        assertEquals(firstPage, visited);
        ids.get("a").addAll(List.of(27, 28, 29, 30));
        visited.clear();
        worker.sweep();
        assertEquals(List.of(26, 101, 102), visited);
        visited.clear();
        worker.sweep();
        assertEquals(firstPage, visited);
        visited.clear();
        worker.sweep();
        assertEquals(List.of(26, 27, 28, 29, 30, 101, 102), visited);
        properties.setEnabled(false);
        visited.clear();
        worker.sweep();
        assertEquals(List.of(), visited);
    }
}
