package ooo.klae.connex.backend.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.async.AsyncWebRequest;
import org.springframework.web.context.request.async.WebAsyncUtils;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.TenantTeardownRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.GlobalExceptionHandler;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.observability.ErrorReporter;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.TenantExportService;
import ooo.klae.connex.backend.services.TenantExportService.TenantExportDownload;
import ooo.klae.connex.backend.services.TenantTeardownService;
import ooo.klae.connex.backend.tenant.TenantContext;

@ExtendWith(MockitoExtension.class)
class TenantLifecycleControllerTest {
    @Mock private TenantExportService tenantExportService;
    @Mock private TenantTeardownService tenantTeardownService;
    @Mock private AuthService authService;
    @Mock private TenantExportDownload download;
    @Mock private AsyncWebRequest asyncWebRequest;
    @Mock private ErrorReporter errorReporter;

    private TenantLifecycleController exportController;
    private TenantTeardownController teardownController;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        exportController = new TenantLifecycleController(
            tenantExportService,
            authService);
        teardownController = new TenantTeardownController(
            tenantTeardownService,
            authService);
        mockMvc = MockMvcBuilders.standaloneSetup(exportController, teardownController)
            .setControllerAdvice(new GlobalExceptionHandler(errorReporter, new TenantContext()))
            .build();
        User user = new User();
        user.setId(7);
        when(authService.getCurrentUser()).thenReturn(user);
    }

    @Test
    void exportUsesARequestScopedTimeoutAndStreamsThePreparedDownload() throws Exception {
        when(tenantExportService.prepare(3, 5, 7)).thenReturn(download);
        when(download.filename()).thenReturn("tenant.zip");
        when(download.remainingTimeoutMillis()).thenReturn(1_020_000L);
        MockHttpServletRequest request = new MockHttpServletRequest();
        WebAsyncUtils.getAsyncManager(request).setAsyncWebRequest(asyncWebRequest);
        clearInvocations(asyncWebRequest);

        var response = exportController.export(3, 5, request);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        response.getBody().writeTo(output);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("application/zip", response.getHeaders().getContentType().toString());
        verify(asyncWebRequest).setTimeout(1_020_000L);
        verify(asyncWebRequest).addTimeoutHandler(any(Runnable.class));
        verify(asyncWebRequest).addErrorHandler(any());
        verify(asyncWebRequest).addCompletionHandler(any(Runnable.class));
        verify(download).writeTo(output);
    }

    @Test
    void exportLifecycleHandlersIdempotentlySignalCancellation() {
        when(tenantExportService.prepare(3, 5, 7)).thenReturn(download);
        when(download.filename()).thenReturn("tenant.zip");
        when(download.remainingTimeoutMillis()).thenReturn(1_000L);
        MockHttpServletRequest request = new MockHttpServletRequest();
        WebAsyncUtils.getAsyncManager(request).setAsyncWebRequest(asyncWebRequest);
        clearInvocations(asyncWebRequest);
        ArgumentCaptor<Runnable> timeoutHandler = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Runnable> completionHandler = ArgumentCaptor.forClass(Runnable.class);
        AtomicReference<Consumer<Throwable>> errorHandler = new AtomicReference<>();
        doAnswer(invocation -> {
            errorHandler.set(invocation.getArgument(0));
            return null;
        }).when(asyncWebRequest).addErrorHandler(any());

        exportController.export(3, 5, request);

        verify(asyncWebRequest).addTimeoutHandler(timeoutHandler.capture());
        verify(asyncWebRequest).addCompletionHandler(completionHandler.capture());
        timeoutHandler.getValue().run();
        errorHandler.get().accept(new IllegalStateException("response failed"));
        completionHandler.getValue().run();
        verify(download, times(3)).cancel();
    }

    @Test
    void exportResponseConstructionFailureSignalsNonblockingCancellation() {
        IllegalStateException primary = new IllegalStateException("filename failed");
        when(tenantExportService.prepare(3, 5, 7)).thenReturn(download);
        when(download.filename()).thenThrow(primary);
        when(download.remainingTimeoutMillis()).thenReturn(1_000L);
        MockHttpServletRequest request = new MockHttpServletRequest();
        WebAsyncUtils.getAsyncManager(request).setAsyncWebRequest(asyncWebRequest);

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> exportController.export(3, 5, request));

        assertSame(primary, thrown);
        assertEquals(0, thrown.getSuppressed().length);
        verify(download).cancel();
    }

    @Test
    void exportMapsAuthorizationAndPathIsolationFailures() throws Exception {
        doThrow(new ForbiddenException("Forbidden"))
            .doThrow(new ResourceNotFoundException("Workspace not found"))
            .when(tenantExportService)
            .prepare(3, 5, 7);

        mockMvc.perform(get("/api/orgs/3/workspaces/5/export"))
            .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/orgs/3/workspaces/5/export"))
            .andExpect(status().isNotFound());
    }

    @Test
    void teardownMapsOwnerAndConfirmationFailures() throws Exception {
        doThrow(new ForbiddenException("Forbidden"))
            .when(tenantTeardownService)
            .teardownWorkspace(3, 5, 7, "workspace");

        mockMvc.perform(delete("/api/orgs/3/workspaces/5")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmation\":\"workspace\"}"))
            .andExpect(status().isForbidden());

        doThrow(new BadRequestException("Tenant confirmation does not match its slug"))
            .when(tenantTeardownService)
            .teardownOrganization(3, 7, "wrong");

        mockMvc.perform(delete("/api/orgs/3")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmation\":\"wrong\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void teardownValidatesConfirmationAndReturnsNoContent() throws Exception {
        mockMvc.perform(delete("/api/orgs/3/workspaces/5")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmation\":\"workspace\"}"))
            .andExpect(status().isNoContent());
        verify(tenantTeardownService).teardownWorkspace(3, 5, 7, "workspace");

        mockMvc.perform(delete("/api/orgs/3")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmation\":\"\"}"))
            .andExpect(status().isBadRequest());

        String oversized = "x".repeat(256);
        mockMvc.perform(delete("/api/orgs/3")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmation\":\"" + oversized + "\"}"))
            .andExpect(status().isBadRequest());

        mockMvc.perform(delete("/api/orgs/3")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void teardownControllerForwardsOrganizationConfirmation() {
        TenantTeardownRequest request = new TenantTeardownRequest("organization");

        teardownController.teardownOrganization(3, request);

        verify(tenantTeardownService).teardownOrganization(3, 7, "organization");
    }
}
