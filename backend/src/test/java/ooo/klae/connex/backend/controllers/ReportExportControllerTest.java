package ooo.klae.connex.backend.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ooo.klae.connex.backend.dto.ReportExportCompletionRequest;
import ooo.klae.connex.backend.exceptions.GlobalExceptionHandler;
import ooo.klae.connex.backend.observability.ErrorReporter;
import ooo.klae.connex.backend.services.ReportComposerService;
import ooo.klae.connex.backend.services.ReportService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;
import ooo.klae.connex.backend.tenant.TenantContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Exercises explicit report-export requests through Jackson and the real MVC binding. */
class ReportExportControllerTest {
    @Test
    void snapshotCsvExportsOnceByPostAndReadReplaysCannotReachTheService() throws Exception {
        ReportService service = mock(ReportService.class);
        when(service.exportSnapshotCsv(11, 3)).thenReturn("source_id,widget_id\r\nsource,widget\r\n");
        var mvc = MockMvcBuilders.standaloneSetup(new ReportController(service, mock(ReportComposerService.class)))
            .setControllerAdvice(new GlobalExceptionHandler(mock(ErrorReporter.class), new TenantContext())).build();

        mvc.perform(post("/api/reports/11/snapshots/3/export.csv"))
            .andExpect(status().isOk())
            .andExpect(content().string("\uFEFFsource_id,widget_id\r\nsource,widget\r\n"));
        mvc.perform(get("/api/reports/11/snapshots/3/export.csv"))
            .andExpect(status().isMethodNotAllowed());
        mvc.perform(head("/api/reports/11/snapshots/3/export.csv"))
            .andExpect(status().isMethodNotAllowed());

        verify(service).exportSnapshotCsv(11, 3);
        verifyNoMoreInteractions(service);
        assertEquals(Permission.REPORT_READ, ReportController.class.getMethod("exportSnapshot",
            int.class, int.class).getAnnotation(RequirePermission.class).value());
    }

    @Test
    void completionBodyRejectsUnknownNamesCountsAndIdentifiersEvenWithLenientGlobalJackson() {
        var mapper = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        assertEquals("png", mapper.readValue("{\"format\":\"png\"}", ReportExportCompletionRequest.class).format());
        for (String field : new String[] {"rowCount", "widgetCount", "name", "reportId", "workspaceId"}) {
            assertThrows(RuntimeException.class, () -> mapper.readValue(
                "{\"format\":\"png\",\"" + field + "\":1}", ReportExportCompletionRequest.class));
        }
    }

    @Test
    void validPngCompletionDelegatesOnlyPathIdentityAndFormatUnderReportRead() throws Exception {
        ReportService service = mock(ReportService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new ReportController(service, mock(ReportComposerService.class)))
            .setControllerAdvice(new GlobalExceptionHandler(mock(ErrorReporter.class), new TenantContext())).build();

        mvc.perform(post("/api/reports/11/export-completion").contentType(MediaType.APPLICATION_JSON)
            .content("{\"format\":\"png\"}")).andExpect(status().isNoContent());

        verify(service).completeImageExport(11, "png");
        assertEquals(Permission.REPORT_READ, ReportController.class.getMethod("completeImageExport",
            int.class, ReportExportCompletionRequest.class).getAnnotation(RequirePermission.class).value());
    }

    @Test
    void invalidOrUntrustedCompletionBodiesCannotReachTheService() throws Exception {
        ReportService service = mock(ReportService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new ReportController(service, mock(ReportComposerService.class)))
            .setControllerAdvice(new GlobalExceptionHandler(mock(ErrorReporter.class), new TenantContext())).build();
        for (String body : new String[] {"{}", "{\"format\":\"csv\"}",
                "{\"format\":\"png\",\"rowCount\":42}", "{\"format\":\"png\",\"name\":\"private\"}"}) {
            mvc.perform(post("/api/reports/11/export-completion").contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }
}
