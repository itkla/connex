package ooo.klae.connex.backend.controllers;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.ProductImportPreviewResult;
import ooo.klae.connex.backend.dto.ProductImportRequest;
import ooo.klae.connex.backend.dto.ProductImportResult;
import ooo.klae.connex.backend.services.ProductImportService;

/**
 * HTTP boundary for product-catalog CSV imports.
 */
@RestController
@RequestMapping("/api/imports/products")
@RequiredArgsConstructor
public class ProductImportController {

    private final ProductImportService importService;

    /** Previews a catalog import. */
    @PostMapping("/preview")
    public ProductImportPreviewResult previewProducts(
            @Valid @RequestBody ProductImportRequest request) {
        return importService.previewProducts(request);
    }

    /** Commits a catalog import. */
    @PostMapping
    public ProductImportResult importProducts(
            @Valid @RequestBody ProductImportRequest request) {
        return importService.commitProducts(request);
    }
}
