package ooo.klae.connex.backend.controllers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.GlobalExceptionHandler;
import ooo.klae.connex.backend.exceptions.MalwareDetectedException;
import ooo.klae.connex.backend.exceptions.RequestBodyTooLargeException;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.exceptions.UnsupportedUploadMediaTypeException;
import ooo.klae.connex.backend.observability.ErrorReporter;
import ooo.klae.connex.backend.services.AttachmentService;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.storage.UploadSource;
import ooo.klae.connex.backend.storage.malware.EicarTestFixture;
import ooo.klae.connex.backend.tenant.TenantContext;

@ExtendWith(MockitoExtension.class)
class AttachmentControllerUploadTest {
    @Mock private AttachmentService attachmentService;
    @Mock private AuthService authService;
    @Mock private ErrorReporter errorReporter;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new AttachmentController(attachmentService, authService))
            .setControllerAdvice(new GlobalExceptionHandler(errorReporter, new TenantContext()))
            .build();
    }

    @Test
    void inlineImageRejectionReturnsTheStableSanitizedSurface() throws Exception {
        User user = new User();
        when(authService.getCurrentUser()).thenReturn(user);
        when(attachmentService.uploadInlineImage(
                eq("person"), eq(42), any(UploadSource.class), same(user)))
            .thenThrow(new UnsupportedUploadMediaTypeException(
                "decoder detail at /tmp/private-upload"));
        MockMultipartFile spoofed = new MockMultipartFile(
            "file",
            "portrait.png",
            "image/png",
            "<script>alert(1)</script>".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/attachments/upload-image")
                .file(spoofed)
                .param("entityType", "person")
                .param("entityId", "42"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(content().string("Upload a supported file type"));

        verify(attachmentService).uploadInlineImage(
            eq("person"), eq(42), any(UploadSource.class), same(user));
    }

    @Test
    void canonicalImageExpansionReturnsPayloadTooLarge() throws Exception {
        User user = new User();
        when(authService.getCurrentUser()).thenReturn(user);
        when(attachmentService.uploadInlineImage(
                eq("person"), eq(42), any(UploadSource.class), same(user)))
            .thenThrow(new RequestBodyTooLargeException(1024));
        MockMultipartFile image = new MockMultipartFile(
            "file", "portrait.jpg", "image/jpeg", new byte[] {1, 2, 3});

        mockMvc.perform(multipart("/api/attachments/upload-image")
                .file(image)
                .param("entityType", "person")
                .param("entityId", "42"))
            .andExpect(status().isPayloadTooLarge())
            .andExpect(content().string("Request body is too large"));
    }

    @Test
    void malwareDetectionReturnsStableUnprocessableEntity() throws Exception {
        User user = new User();
        when(authService.getCurrentUser()).thenReturn(user);
        when(attachmentService.upload(
                eq("person"), eq(42), any(UploadSource.class), same(user)))
            .thenThrow(new MalwareDetectedException());
        MockMultipartFile file = new MockMultipartFile(
            "file", "eicar.txt", "text/plain", EicarTestFixture.bytes());

        mockMvc.perform(multipart("/api/attachments/upload")
                .file(file)
                .param("entityType", "person")
                .param("entityId", "42"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json("{\"code\":\"MALWARE_DETECTED\","
                    + "\"message\":\"This file was rejected by security scanning.\"}"));
    }

    @Test
    void inlineImageMalwareDetectionReturnsStableUnprocessableEntity() throws Exception {
        User user = new User();
        when(authService.getCurrentUser()).thenReturn(user);
        when(attachmentService.uploadInlineImage(
                eq("person"), eq(42), any(UploadSource.class), same(user)))
            .thenThrow(new MalwareDetectedException());
        MockMultipartFile file = new MockMultipartFile(
            "file", "image.png", "image/png", new byte[] {1});

        mockMvc.perform(multipart("/api/attachments/upload-image")
                .file(file)
                .param("entityType", "person")
                .param("entityId", "42"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json("{\"code\":\"MALWARE_DETECTED\","
                    + "\"message\":\"This file was rejected by security scanning.\"}"));
    }

    @Test
    void scannerUnavailabilityReturnsGenericServiceUnavailable() throws Exception {
        User user = new User();
        when(authService.getCurrentUser()).thenReturn(user);
        when(attachmentService.upload(
                eq("person"), eq(42), any(UploadSource.class), same(user)))
            .thenThrow(new ServiceUnavailableException("private scanner detail"));
        MockMultipartFile file = new MockMultipartFile(
            "file", "report.txt", "text/plain", new byte[] {1});

        mockMvc.perform(multipart("/api/attachments/upload")
                .file(file)
                .param("entityType", "person")
                .param("entityId", "42"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().string("This deployment cannot serve the request"));
    }
}
