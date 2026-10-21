package ooo.klae.connex.backend.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.services.AttachmentService;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.util.LikePattern;

/** Pins the existing paging inputs and wire output while page/count become one service operation. */
class AttachmentControllerPageTest {
    @Test
    void pageRetainsBoundsEscapingAndTheExactPairedTotal() {
        var service = mock(AttachmentService.class);
        var controller = new AttachmentController(service, mock(AuthService.class));
        var attachment = new Attachment();
        attachment.setId(42);
        attachment.setFileName("record.pdf");
        String pattern = LikePattern.containing("Ém%_");
        when(service.getPage(pattern, "name", null, null, null, true, 100, 100))
            .thenReturn(new PageResponse<>(List.of(attachment), 234));
        var page = controller.getAttachmentsPage(2, 200, "Ém%_", "name", null, null, null, true);
        assertEquals(234, page.total());
        assertEquals(42, page.items().getFirst().getId());
        verify(service).getPage(pattern, "name", null, null, null, true, 100, 100);
        verifyNoMoreInteractions(service);
    }

    @Test
    void emptyQueryAndNonpositiveBoundsRetainTheirNormalization() {
        var service = mock(AttachmentService.class);
        var controller = new AttachmentController(service, mock(AuthService.class));
        when(service.getPage(null, null, null, null, null, null, 1, 0))
            .thenReturn(new PageResponse<>(List.of(), 0));
        assertEquals(0, controller.getAttachmentsPage(0, 0, " ", null, null, null, null, null).total());
        verify(service).getPage(null, null, null, null, null, null, 1, 0);
    }
}
