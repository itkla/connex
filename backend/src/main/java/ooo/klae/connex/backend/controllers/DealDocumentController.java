package ooo.klae.connex.backend.controllers;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.dto.DealDocumentDto;
import ooo.klae.connex.backend.dto.DocumentStatusRequest;
import ooo.klae.connex.backend.dto.GenerateDocumentRequest;
import ooo.klae.connex.backend.services.DealDocumentService;

/** REST controller for generated commercial documents on a deal. */
@RestController
@RequestMapping("/api/deals/{dealId}/documents")
@RequiredArgsConstructor
public class DealDocumentController {
    private final DealDocumentService documentService;

    @GetMapping
    public List<DealDocumentDto> getForDeal(@PathVariable int dealId) {
        return documentService.getForDeal(dealId);
    }

    @GetMapping("/{documentId}")
    public DealDocumentDto getOne(@PathVariable int dealId, @PathVariable int documentId) {
        return documentService.getOne(dealId, documentId);
    }

    @PostMapping
    public DealDocumentDto generate(@PathVariable int dealId, @Valid @RequestBody GenerateDocumentRequest request) {
        return documentService.generate(dealId, request.getTemplateId());
    }

    @PutMapping("/{documentId}/status")
    public DealDocumentDto updateStatus(
            @PathVariable int dealId,
            @PathVariable int documentId,
            @Valid @RequestBody DocumentStatusRequest request) {
        return documentService.updateStatus(dealId, documentId, request.getStatus());
    }

    @DeleteMapping("/{documentId}")
    public void delete(@PathVariable int dealId, @PathVariable int documentId) {
        documentService.delete(dealId, documentId);
    }
}
