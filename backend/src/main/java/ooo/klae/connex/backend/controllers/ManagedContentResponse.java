package ooo.klae.connex.backend.controllers;

import java.nio.charset.StandardCharsets;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import ooo.klae.connex.backend.storage.ManagedObjectService.ManagedContent;

/**
 * Secure response headers and streaming body for private managed objects.
 */
final class ManagedContentResponse {
    private ManagedContentResponse() {}

    static ResponseEntity<StreamingResponseBody> attachment(ManagedContent content) {
        return response(content, ContentDisposition.attachment()
            .filename(content.fileName(), StandardCharsets.UTF_8)
            .build());
    }

    static ResponseEntity<StreamingResponseBody> inline(ManagedContent content) {
        return response(content, ContentDisposition.inline()
            .filename(content.fileName(), StandardCharsets.UTF_8)
            .build());
    }

    private static ResponseEntity<StreamingResponseBody> response(
            ManagedContent content,
            ContentDisposition disposition) {
        StreamingResponseBody body = output -> {
            try (content) {
                content.inputStream().transferTo(output);
            }
        };
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(content.contentType()))
            .contentLength(content.contentLength())
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .header("X-Content-Type-Options", "nosniff")
            .header("Cross-Origin-Resource-Policy", "same-origin")
            .header("Content-Security-Policy", "default-src 'none'; sandbox; frame-ancestors 'none'; base-uri 'none'")
            .body(body);
    }
}
