package ooo.klae.connex.backend.controllers;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.BusinessCardAvailabilityResponse;
import ooo.klae.connex.backend.dto.BusinessCardCompanyAction;
import ooo.klae.connex.backend.dto.BusinessCardContactRequest;
import ooo.klae.connex.backend.dto.BusinessCardImportResponse;
import ooo.klae.connex.backend.dto.BusinessCardImportReservationResponse;
import ooo.klae.connex.backend.dto.BusinessCardPersonAction;
import ooo.klae.connex.backend.dto.BusinessCardScanResponse;
import ooo.klae.connex.backend.services.BusinessCardService;

/**
 * Authenticated multipart endpoints for review-first business-card scanning and import.
 */
@RestController
@RequestMapping("/api/business-cards")
@RequiredArgsConstructor
public class BusinessCardController {
    private final BusinessCardService businessCardService;

    /**
     * Returns active-workspace readiness without exposing provider configuration.
     *
     * @return non-cacheable scan and import readiness
     */
    @GetMapping("/availability")
    public ResponseEntity<BusinessCardAvailabilityResponse> availability() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(businessCardService.availability());
    }

    /**
     * Scans one card image into an editable draft without database mutation.
     *
     * @param image JPEG, PNG, or WebP image
     * @return typed extraction draft
     */
    @PostMapping(path = "/scan", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BusinessCardScanResponse scan(@RequestPart("image") MultipartFile image) {
        return businessCardService.scan(image);
    }

    /**
     * Imports reviewed values and retains the original image as a contact attachment.
     *
     * @param image original JPEG, PNG, or WebP image
     * @param contact reviewed contact JSON part
     * @param personAction explicit person-action JSON part; absent legacy requests create a contact
     * @param companyAction explicit company-action JSON part
     * @param idempotencyKey caller-generated UUID retained across retries
     * @return imported contact, attachment, optional company, and disposition
     */
    @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BusinessCardImportResponse importCard(
            @RequestPart("image") MultipartFile image,
            @Valid @RequestPart("contact") BusinessCardContactRequest contact,
            @Valid @RequestPart(value = "personAction", required = false)
                    BusinessCardPersonAction personAction,
            @Valid @RequestPart("companyAction") BusinessCardCompanyAction companyAction,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        BusinessCardPersonAction resolvedPersonAction =
                personAction == null ? new BusinessCardPersonAction.Create() : personAction;
        return businessCardService.importCard(
                image, contact, resolvedPersonAction, companyAction, idempotencyKey);
    }

    /**
     * Reserves an opaque import key before any private multipart content is submitted.
     *
     * @param idempotencyKey caller-generated UUID retained across retries
     * @return server-defined reservation retention boundary
     */
    @PostMapping("/import/reservation")
    public BusinessCardImportReservationResponse reserveImport(
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return businessCardService.reserveImport(idempotencyKey);
    }

    /**
     * Reconciles a previously submitted import after the client lost its response.
     *
     * @param idempotencyKey caller-generated UUID retained across retries
     * @return the completed import result
     */
    @GetMapping("/import")
    public BusinessCardImportResponse importStatus(
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return businessCardService.importStatus(idempotencyKey);
    }
}
