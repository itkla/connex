package ooo.klae.connex.backend.exceptions;

import java.util.Map;

import org.springframework.http.HttpStatus;

import ooo.klae.connex.backend.dto.recordcreation.RecordCreationErrorDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationImpactDto;

public class RecordCreationTemplateException extends RuntimeException {
    private final HttpStatus status;
    private final RecordCreationErrorDto error;

    public RecordCreationTemplateException(
            HttpStatus status,
            String code,
            String message,
            Map<String, String> fieldErrors,
            Integer currentSetRevision,
            Integer currentTemplateRevision,
            Integer currentTemplateVersion,
            RecordCreationImpactDto impact) {
        super(message);
        this.status = status;
        this.error = new RecordCreationErrorDto(
            code,
            message,
            fieldErrors == null ? Map.of() : Map.copyOf(fieldErrors),
            currentSetRevision,
            currentTemplateRevision,
            currentTemplateVersion,
            impact);
    }

    public static RecordCreationTemplateException of(
            HttpStatus status,
            String code,
            String message) {
        return new RecordCreationTemplateException(
            status, code, message, Map.of(), null, null, null, null);
    }

    public static RecordCreationTemplateException stale(
            String code,
            String message,
            Integer setRevision,
            Integer templateRevision,
            Integer templateVersion) {
        return new RecordCreationTemplateException(
            HttpStatus.CONFLICT,
            code,
            message,
            Map.of(),
            setRevision,
            templateRevision,
            templateVersion,
            null);
    }

    public static RecordCreationTemplateException impact(RecordCreationImpactDto impact) {
        return new RecordCreationTemplateException(
            HttpStatus.CONFLICT,
            "TEMPLATE_IMPACT_CONFIRMATION_REQUIRED",
            "Confirm the impact before applying this template change",
            Map.of(),
            null,
            null,
            null,
            impact);
    }

    public HttpStatus status() {
        return status;
    }

    public RecordCreationErrorDto error() {
        return error;
    }
}
