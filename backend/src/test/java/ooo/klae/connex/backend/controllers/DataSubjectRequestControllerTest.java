package ooo.klae.connex.backend.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.mock.web.MockHttpServletResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.DataSubjectRequestDto;
import ooo.klae.connex.backend.dto.DataSubjectRequestUpsertRequest;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.DataSubjectRequestService;

@ExtendWith(MockitoExtension.class)
class DataSubjectRequestControllerTest {
    @Mock private DataSubjectRequestService dataSubjectRequestService;
    @Mock private AuthService authService;

    private DataSubjectRequestController controller;
    private User user;

    @BeforeEach
    void setUp() {
        controller = new DataSubjectRequestController(dataSubjectRequestService, authService);
        user = new User();
        user.setId(7);
    }

    @Test
    void exposesTheExpectedBasePathAndCreateStatus() throws Exception {
        when(authService.getCurrentUser()).thenReturn(user);
        RequestMapping mapping = DataSubjectRequestController.class.getAnnotation(RequestMapping.class);
        Method list = DataSubjectRequestController.class.getMethod(
            "list", int.class, String.class, int.class, int.class);
        Method create = DataSubjectRequestController.class.getMethod(
            "create", int.class, DataSubjectRequestUpsertRequest.class);
        Method get = DataSubjectRequestController.class.getMethod("get", int.class, long.class);
        Method update = DataSubjectRequestController.class.getMethod(
            "update", int.class, long.class, DataSubjectRequestUpsertRequest.class);
        Method disclosure = DataSubjectRequestController.class.getMethod("disclosure", int.class, long.class, HttpServletResponse.class);

        assertEquals(7, authService.getCurrentUser().getId());
        assertEquals("/api/orgs/{orgId}/data-subject-requests", mapping.value()[0]);
        assertEquals(0, list.getAnnotation(GetMapping.class).value().length);
        assertEquals(0, create.getAnnotation(PostMapping.class).value().length);
        assertEquals(HttpStatus.CREATED, create.getAnnotation(ResponseStatus.class).value());
        assertEquals("/{requestId}", get.getAnnotation(GetMapping.class).value()[0]);
        assertEquals("/{requestId}", update.getAnnotation(PutMapping.class).value()[0]);
        assertEquals("/{requestId}/disclosure", disclosure.getAnnotation(PostMapping.class).value()[0]);
        assertNull(disclosure.getAnnotation(GetMapping.class));
        assertTrue(create.getParameters()[1].isAnnotationPresent(Valid.class));
        assertTrue(update.getParameters()[2].isAnnotationPresent(Valid.class));
    }

    @Test
    void listForwardsOrgActorFilterAndPaging() {
        when(authService.getCurrentUser()).thenReturn(user);
        when(dataSubjectRequestService.list(3, 7, "received", 25, 50)).thenReturn(List.of());
        controller.list(3, "received", 25, 50);
        verify(dataSubjectRequestService).list(3, 7, "received", 25, 50);
    }

    @Test
    void createForwardsOrgActorAndBody() {
        when(authService.getCurrentUser()).thenReturn(user);
        DataSubjectRequestUpsertRequest request = new DataSubjectRequestUpsertRequest();
        when(dataSubjectRequestService.create(3, 7, request)).thenReturn(new DataSubjectRequestDto());
        controller.create(3, request);
        verify(dataSubjectRequestService).create(3, 7, request);
    }

    @Test
    void getUpdateAndDisclosureForwardOrgActorAndRequest() {
        when(authService.getCurrentUser()).thenReturn(user);
        DataSubjectRequestUpsertRequest request = new DataSubjectRequestUpsertRequest();
        when(dataSubjectRequestService.get(3, 9L, 7)).thenReturn(new DataSubjectRequestDto());
        when(dataSubjectRequestService.update(3, 9L, 7, request)).thenReturn(new DataSubjectRequestDto());

        controller.get(3, 9L);
        controller.update(3, 9L, request);
        controller.disclosure(3, 9L, new MockHttpServletResponse());

        verify(dataSubjectRequestService).get(3, 9L, 7);
        verify(dataSubjectRequestService).update(3, 9L, 7, request);
        verify(dataSubjectRequestService).disclosure(eq(3), eq(9L), eq(7), any());
    }
    @Test
    void interruptedCommittedResponseGetsNoErrorSuffix() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.getOutputStream().write("{\"notes\":[".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        response.flushBuffer();
        var handler = new ooo.klae.connex.backend.exceptions.GlobalExceptionHandler(
            org.mockito.Mockito.mock(ooo.klae.connex.backend.observability.ErrorReporter.class),
            new ooo.klae.connex.backend.tenant.TenantContext());
        assertNull(handler.disclosureStream(
            new ooo.klae.connex.backend.exceptions.DataSubjectDisclosureStreamException(
                new java.io.IOException("injected disconnect")), response));
        assertEquals("{\"notes\":[", response.getContentAsString());
    }

    @Test
    void interruptedUncommittedResponseClearsDisclosureBytesAndIntegrityHeaders() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.getOutputStream().write("{\"notes\":[".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        response.setContentType("application/json");
        for (String header : List.of("X-Disclosure-Note-Count", "X-Disclosure-Notes-SHA256",
                "X-Disclosure-Byte-Length", "X-Disclosure-SHA256")) {
            response.setHeader(header, "preflight");
        }
        var handler = new ooo.klae.connex.backend.exceptions.GlobalExceptionHandler(
            org.mockito.Mockito.mock(ooo.klae.connex.backend.observability.ErrorReporter.class),
            new ooo.klae.connex.backend.tenant.TenantContext());
        var error = handler.disclosureStream(
            new ooo.klae.connex.backend.exceptions.DataSubjectDisclosureStreamException(
                new java.io.IOException("injected disconnect")), response);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
        assertEquals("This deployment cannot serve the request", error.getBody());
        assertEquals("", response.getContentAsString());
        assertNull(response.getContentType());
        for (String header : List.of("X-Disclosure-Note-Count", "X-Disclosure-Notes-SHA256",
                "X-Disclosure-Byte-Length", "X-Disclosure-SHA256")) {
            assertNull(response.getHeader(header));
        }
    }

}
