package ooo.klae.connex.backend.controllers;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.dto.CompanyDto;
import ooo.klae.connex.backend.dto.DealDto;
import ooo.klae.connex.backend.dto.PersonDto;
import ooo.klae.connex.backend.dto.recordcreation.GuidedCompanyCreateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.GuidedDealCreateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.GuidedPersonCreateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationErrorDto;
import ooo.klae.connex.backend.services.GuidedRecordCreationService;
import ooo.klae.connex.backend.tenant.TenantJournalAttributable;

@RestController
@RequiredArgsConstructor
@TenantJournalAttributable
@ConditionalOnProperty(
    prefix = "connex.record-creation",
    name = "guided-cutover-enabled",
    havingValue = "true")
public class GuidedRecordCreationController {
    private final GuidedRecordCreationService guidedRecordCreationService;

    @PostMapping("/api/persons")
    public PersonDto createPerson(
            @Valid @RequestBody GuidedPersonCreateRequestDto request) {
        return PersonDto.from(guidedRecordCreationService.createPerson(request));
    }

    @PostMapping("/api/companies")
    public CompanyDto createCompany(
            @Valid @RequestBody GuidedCompanyCreateRequestDto request) {
        return CompanyDto.from(guidedRecordCreationService.createCompany(request));
    }

    @PostMapping("/api/deals")
    public DealDto createDeal(
            @Valid @RequestBody GuidedDealCreateRequestDto request) {
        return DealDto.from(guidedRecordCreationService.createDeal(request));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<RecordCreationErrorDto> unreadableMessage() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(requestBodyInvalid(Map.of()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<RecordCreationErrorDto> validation(MethodArgumentNotValidException exception) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
            fieldErrors.put(
                error.getField(),
                error.getDefaultMessage() == null ? "Invalid value" : error.getDefaultMessage()));
        boolean missingNestedBody = fieldErrors.keySet().stream().anyMatch(
            field -> "record".equals(field) || "templateUse".equals(field));
        RecordCreationErrorDto error = missingNestedBody
            ? requestBodyInvalid(fieldErrors)
            : new RecordCreationErrorDto(
                "VALIDATION_FAILED",
                "Please fix the highlighted fields",
                fieldErrors,
                null,
                null,
                null,
                null);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    private static RecordCreationErrorDto requestBodyInvalid(Map<String, String> fieldErrors) {
        return new RecordCreationErrorDto(
            "REQUEST_BODY_INVALID",
            "A nested guided record creation body is required",
            fieldErrors,
            null,
            null,
            null,
            null);
    }
}
