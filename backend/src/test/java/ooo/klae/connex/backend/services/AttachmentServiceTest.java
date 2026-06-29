package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.exceptions.BadRequestException;

class AttachmentServiceTest extends AbstractServiceTest {

    @Autowired AttachmentService attachmentService;

    private Attachment attachmentWithUrl(String url) {
        Attachment attachment = new Attachment();
        attachment.setEntityType("company");
        attachment.setEntityId(1);
        attachment.setFileName("file.png");
        attachment.setUrl(url);
        return attachment;
    }

    @Test
    void create_rejectsScriptAndProtocolRelativeUrls() {
        List<String> unsafe = List.of(
            "javascript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "vbscript:msgbox(1)",
            "//evil.com/x",
            "/\\evil.com/x",
            "/" + (char) 0x0A + "/evil.test/x",
            "report.pdf");
        for (String url : unsafe) {
            assertThrows(BadRequestException.class,
                () -> attachmentService.create(attachmentWithUrl(url)), url);
        }
    }

    @Test
    void create_acceptsAppRelativeAndHttpUrls() {
        List<String> safe = List.of(
            "/attachments/company/1-file.png",
            "https://example.com/file.pdf",
            "HTTP://example.com/file.pdf");
        for (String url : safe) {
            assertDoesNotThrow(() -> attachmentService.create(attachmentWithUrl(url)), url);
        }
    }
}
