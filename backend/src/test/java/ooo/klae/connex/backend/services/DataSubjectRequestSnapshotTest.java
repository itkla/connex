package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.DataSubjectRequest;

class DataSubjectRequestSnapshotTest {
    @Test
    void dsrSnapshotUsesFullStoredState() {
        DataSubjectRequest original = request();
        String token = DataSubjectRequestSnapshot.token(original);
        List<Consumer<DataSubjectRequest>> changes = List.of(
                request -> request.setId(8), request -> request.setOrgId(4),
                request -> request.setRequestType("correction"), request -> request.setStatus("closed"),
                request -> request.setChannel("postal"), request -> request.setRequesterName("Different requester"),
                request -> request.setSubjectName("Different subject"), request -> request.setSubjectEmail("other@example.test"),
                request -> request.setSubjectWorkspaceId(9), request -> request.setSubjectPersonId(12),
                request -> request.setReceivedAt(request.getReceivedAt().plusSeconds(1)),
                request -> request.setIdentityVerifiedAt(request.getReceivedAt()),
                request -> request.setDueAt(request.getReceivedAt()),
                request -> request.setRespondedAt(request.getReceivedAt()),
                request -> request.setClosedAt(request.getReceivedAt()),
                request -> request.setSummary("Revised summary"), request -> request.setResolution("Revised resolution"),
                request -> request.setCreatedBy(14), request -> request.setUpdatedBy(15),
                request -> request.setCreatedAt(request.getCreatedAt().plusSeconds(1)),
                request -> request.setUpdatedAt(request.getUpdatedAt().plusSeconds(1)));
        for (Consumer<DataSubjectRequest> change : changes) {
            DataSubjectRequest changed = request();
            change.accept(changed);
            assertNotEquals(token, DataSubjectRequestSnapshot.token(changed));
        }
        assertTrue(token.matches("dsr1\\.[A-Za-z0-9_-]{43}"));
        assertEquals(token, DataSubjectRequestSnapshot.token(request()));
    }

    @Test
    void snapshotPreservesWhitespaceNullsAndExactStoredDatetime() {
        DataSubjectRequest request = request();
        String original = DataSubjectRequestSnapshot.token(request);
        request.setSubjectName("Subject ");
        assertNotEquals(original, DataSubjectRequestSnapshot.token(request));
        request.setSubjectName("Subject");
        assertEquals(original, DataSubjectRequestSnapshot.token(request));
        request.setResolution("");
        assertNotEquals(original, DataSubjectRequestSnapshot.token(request));
        request.setResolution(null);
        request.setUpdatedAt(request.getUpdatedAt().plusNanos(1000));
        assertNotEquals(original, DataSubjectRequestSnapshot.token(request));
    }

    private static DataSubjectRequest request() {
        DataSubjectRequest request = new DataSubjectRequest();
        request.setId(7);
        request.setOrgId(3);
        request.setRequestType("cease_use");
        request.setStatus("in_progress");
        request.setChannel("email");
        request.setRequesterName("Requester");
        request.setSubjectName("Subject");
        request.setSubjectEmail("subject@example.test");
        request.setSubjectWorkspaceId(5);
        request.setSubjectPersonId(6);
        request.setReceivedAt(LocalDateTime.of(2026, 10, 1, 12, 0));
        request.setSummary("Summary");
        request.setCreatedBy(1);
        request.setUpdatedBy(2);
        request.setCreatedAt(request.getReceivedAt());
        request.setUpdatedAt(request.getReceivedAt());
        return request;
    }
}
