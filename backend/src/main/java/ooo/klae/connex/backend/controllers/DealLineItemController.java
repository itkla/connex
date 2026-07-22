package ooo.klae.connex.backend.controllers;

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

import ooo.klae.connex.backend.dto.DealLineItemRequest;
import ooo.klae.connex.backend.dto.DealLineItemsResponse;
import ooo.klae.connex.backend.services.DealLineItemService;

/** REST controller for deal line items; every response carries the recomputed deal totals. */
@RestController
@RequestMapping("/api/deals/{dealId}/line-items")
@RequiredArgsConstructor
public class DealLineItemController {
    private final DealLineItemService lineItemService;

    @GetMapping
    public DealLineItemsResponse getForDeal(@PathVariable int dealId) {
        return lineItemService.getForDeal(dealId);
    }

    @PostMapping
    public DealLineItemsResponse create(@PathVariable int dealId, @Valid @RequestBody DealLineItemRequest request) {
        return lineItemService.create(dealId, request);
    }

    @PutMapping("/{itemId}")
    public DealLineItemsResponse update(
            @PathVariable int dealId,
            @PathVariable int itemId,
            @Valid @RequestBody DealLineItemRequest request) {
        return lineItemService.update(dealId, itemId, request);
    }

    @DeleteMapping("/{itemId}")
    public DealLineItemsResponse delete(@PathVariable int dealId, @PathVariable int itemId) {
        return lineItemService.delete(dealId, itemId);
    }
}
