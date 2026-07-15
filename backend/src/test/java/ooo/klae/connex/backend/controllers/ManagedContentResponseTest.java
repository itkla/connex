package ooo.klae.connex.backend.controllers;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import ooo.klae.connex.backend.storage.ManagedObjectService.ManagedContent;
import ooo.klae.connex.backend.storage.StoredObject;

class ManagedContentResponseTest {
    @Test
    void streamsAttachmentWithPrivateDownloadHeaders() throws Exception {
        byte[] bytes = { 1, 2, 3 };
        ManagedContent content = new ManagedContent(
            new StoredObject(new ByteArrayInputStream(bytes), bytes.length),
            "application/pdf",
            "report.pdf");

        ResponseEntity<StreamingResponseBody> response = ManagedContentResponse.attachment(content);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        response.getBody().writeTo(output);

        assertArrayEquals(bytes, output.toByteArray());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        assertEquals("same-origin", response.getHeaders().getFirst("Cross-Origin-Resource-Policy"));
        assertTrue(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).startsWith("attachment"));
        assertTrue(response.getHeaders().getFirst("Content-Security-Policy").contains("default-src 'none'"));
    }
}
