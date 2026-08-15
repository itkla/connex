package ooo.klae.connex.backend.controllers;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.dto.DocumentDeliveryDto;
import ooo.klae.connex.backend.dto.SendDeliveryRequest;
import ooo.klae.connex.backend.dto.VoidDocumentDeliveryRequest;
import ooo.klae.connex.backend.services.DocumentDeliveryService;

/** Authenticated REST surface for delivery state under one immutable deal document. */
@RestController
@RequestMapping("/api/deals/{dealId}/documents/{documentId}/delivery")
@RequiredArgsConstructor
public class DocumentDeliveryController {
    private final DocumentDeliveryService deliveryService;

    @PostMapping
    public DocumentDeliveryDto send(
            @PathVariable int dealId,
            @PathVariable int documentId,
            @Valid @RequestBody SendDeliveryRequest request) {
        return deliveryService.send(dealId, documentId, request);
    }

    @GetMapping
    public List<DocumentDeliveryDto> getForDocument(
            @PathVariable int dealId, @PathVariable int documentId) {
        return deliveryService.getForDocument(dealId, documentId);
    }

    @PostMapping("/{deliveryId}/void")
    public DocumentDeliveryDto voidDelivery(
            @PathVariable int dealId,
            @PathVariable int documentId,
            @PathVariable int deliveryId,
            @Valid @RequestBody VoidDocumentDeliveryRequest request) {
        return deliveryService.voidDelivery(
            dealId, documentId, deliveryId, request.reason());
    }

    @PostMapping("/{deliveryId}/recipients/{recipientId}/resend")
    public DocumentDeliveryDto resend(
            @PathVariable int dealId,
            @PathVariable int documentId,
            @PathVariable int deliveryId,
            @PathVariable int recipientId) {
        return deliveryService.resend(dealId, documentId, deliveryId, recipientId);
    }

    @GetMapping("/{deliveryId}/artifacts/{artifactId}")
    public ResponseEntity<StreamingResponseBody> downloadArtifact(
            @PathVariable int dealId,
            @PathVariable int documentId,
            @PathVariable int deliveryId,
            @PathVariable int artifactId) {
        return ManagedContentResponse.attachment(deliveryService.downloadArtifact(
            dealId, documentId, deliveryId, artifactId));
    }
}
