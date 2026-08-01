package ooo.klae.connex.backend.services;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DealLineItem;
import ooo.klae.connex.backend.beans.Product;
import ooo.klae.connex.backend.dto.DealLineItemDto;
import ooo.klae.connex.backend.dto.DealLineItemRequest;
import ooo.klae.connex.backend.dto.DealLineItemTotalsDto;
import ooo.klae.connex.backend.dto.DealLineItemsResponse;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.DealLineItemMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.ProductMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * Manages line items on a deal and computes their deterministic, currency-safe totals. All money is
 * {@link BigDecimal}; every persisted and returned amount is rounded to 2 decimals with
 * {@link RoundingMode#HALF_UP}. A deal never mixes currencies — line items inherit the deal currency
 * and a catalog product priced in another currency is rejected. Line-item mutations require
 * {@link Permission#DEAL_UPDATE} (they mutate a deal).
 */
@Service
@RequiredArgsConstructor
public class DealLineItemService {
    private final DealLineItemMapper lineItemMapper;
    private final DealMapper dealMapper;
    private final DealValueService dealValueService;
    private final ProductMapper productMapper;
    private final WorkspaceService workspaceService;
    private final AuditService auditService;

    private static final int MONEY_SCALE = 2;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final String RECURRING = "recurring";
    private static final String ONE_TIME = "one_time";

    /** The full line-item view for a deal (items + totals). */
    public DealLineItemsResponse getForDeal(int dealId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Deal deal = requireDeal(workspaceId, dealId);
        return responseFor(workspaceId, deal.getId());
    }

    /** Adds a line item to a deal and returns the refreshed view. */
    @Transactional
    @RequirePermission(Permission.DEAL_UPDATE)
    public DealLineItemsResponse create(int dealId, DealLineItemRequest request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Deal deal = requireDealForUpdate(workspaceId, dealId);
        DealLineItem item = new DealLineItem();
        item.setWorkspaceId(workspaceId);
        item.setDealId(deal.getId());
        applyRequest(workspaceId, deal, item, request, true);
        compute(item);
        lineItemMapper.insert(item);
        dealValueService.reconcileLineItems(workspaceId, deal);
        auditService.record("deal.line_item.create", "deal", deal.getId(), deal.getName(),
            "Added line item " + item.getName() + " to " + deal.getName(), null);
        return responseFor(workspaceId, deal.getId());
    }

    /** Updates a line item on a deal and returns the refreshed view. */
    @Transactional
    @RequirePermission(Permission.DEAL_UPDATE)
    public DealLineItemsResponse update(int dealId, int itemId, DealLineItemRequest request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Deal deal = requireDealForUpdate(workspaceId, dealId);
        DealLineItem item = requireLineItem(workspaceId, deal.getId(), itemId);
        applyRequest(workspaceId, deal, item, request, false);
        compute(item);
        lineItemMapper.update(item);
        dealValueService.reconcileLineItems(workspaceId, deal);
        auditService.record("deal.line_item.update", "deal", deal.getId(), deal.getName(),
            "Updated line item " + item.getName() + " on " + deal.getName(), null);
        return responseFor(workspaceId, deal.getId());
    }

    /** Removes a line item from a deal and returns the refreshed view. */
    @Transactional
    @RequirePermission(Permission.DEAL_UPDATE)
    public DealLineItemsResponse delete(int dealId, int itemId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Deal deal = requireDealForUpdate(workspaceId, dealId);
        DealLineItem item = requireLineItem(workspaceId, deal.getId(), itemId);
        lineItemMapper.delete(workspaceId, itemId);
        dealValueService.reconcileLineItems(workspaceId, deal);
        auditService.record("deal.line_item.delete", "deal", deal.getId(), deal.getName(),
            "Removed line item " + item.getName() + " from " + deal.getName(), null);
        return responseFor(workspaceId, deal.getId());
    }

    /**
     * Populates a line item from the request. On create, catalog values are snapshotted from the
     * product (currency-checked against the deal) or taken from the ad-hoc fields. On update, the
     * stored snapshot ({@code name}/{@code sku}/{@code unit}/{@code unitPrice}/{@code taxRate}/
     * {@code billingFrequency}/{@code productId}) is preserved and the product is never re-read, so a
     * later catalog edit can never leak into an existing line — explicit request fields still
     * override. The line currency is always the deal currency.
     */
    private void applyRequest(int workspaceId, Deal deal, DealLineItem item, DealLineItemRequest request, boolean creating) {
        String dealCurrency = deal.getCurrency() == null || deal.getCurrency().isBlank() ? "USD" : deal.getCurrency();
        if (creating && request.getProductId() != null) {
            Product product = productMapper.getById(workspaceId, request.getProductId());
            if (product == null) throw new ResourceNotFoundException("Product not found with id: " + request.getProductId());
            String productCurrency = product.getCurrency() == null ? "USD" : product.getCurrency();
            if (!productCurrency.equalsIgnoreCase(dealCurrency)) {
                throw new BadRequestException("Product is priced in " + productCurrency + " but the deal is in " + dealCurrency);
            }
            item.setProductId(product.getId());
            item.setName(request.getName() != null ? request.getName() : product.getName());
            item.setSku(request.getSku() != null ? request.getSku() : product.getSku());
            item.setUnit(request.getUnit() != null ? request.getUnit() : product.getUnit());
            item.setUnitPrice(request.getUnitPrice() != null ? request.getUnitPrice() : product.getUnitPrice());
            item.setTaxRate(request.getTaxRate() != null ? request.getTaxRate() : product.getTaxRate());
            item.setBillingFrequency(normalizeFrequency(
                request.getBillingFrequency() != null ? request.getBillingFrequency() : product.getBillingFrequency()));
        } else if (creating) {
            if (request.getName() == null || request.getName().isBlank()) {
                throw new BadRequestException("name is required for an ad-hoc line item");
            }
            if (request.getUnitPrice() == null) {
                throw new BadRequestException("unitPrice is required for an ad-hoc line item");
            }
            item.setProductId(null);
            item.setName(request.getName());
            item.setSku(request.getSku());
            item.setUnit(request.getUnit());
            item.setUnitPrice(request.getUnitPrice());
            item.setTaxRate(request.getTaxRate());
            item.setBillingFrequency(normalizeFrequency(request.getBillingFrequency()));
        } else {
            if (request.getName() != null) item.setName(request.getName());
            if (request.getSku() != null) item.setSku(request.getSku());
            if (request.getUnit() != null) item.setUnit(request.getUnit());
            if (request.getUnitPrice() != null) item.setUnitPrice(request.getUnitPrice());
            if (request.getTaxRate() != null) item.setTaxRate(request.getTaxRate());
            if (request.getBillingFrequency() != null) {
                item.setBillingFrequency(normalizeFrequency(request.getBillingFrequency()));
            }
        }
        item.setQuantity(request.getQuantity() == null ? BigDecimal.ONE : request.getQuantity());
        item.setDiscountType(request.getDiscountType());
        item.setDiscountValue(request.getDiscountValue());
        item.setDescription(request.getDescription());
        item.setServicePeriodStart(request.getServicePeriodStart());
        item.setServicePeriodEnd(request.getServicePeriodEnd());
        if (request.getPosition() != null) item.setPosition(request.getPosition());
        item.setCurrency(dealCurrency);
        if (request.getDiscountType() != null && request.getDiscountValue() == null) {
            throw new BadRequestException("discountValue is required when discountType is set");
        }
    }

    /**
     * Computes the line's subtotal, tax, and total. Rounding is applied at the subtotal and tax steps
     * (HALF_UP, 2dp) so deal-level roll-ups are the exact sum of already-rounded lines.
     */
    private void compute(DealLineItem item) {
        BigDecimal unitPrice = nz(item.getUnitPrice());
        BigDecimal quantity = nz(item.getQuantity());
        BigDecimal gross = unitPrice.multiply(quantity);
        BigDecimal discount = BigDecimal.ZERO;
        if ("amount".equals(item.getDiscountType())) {
            discount = nz(item.getDiscountValue());
        } else if ("percent".equals(item.getDiscountType())) {
            discount = gross.multiply(nz(item.getDiscountValue())).divide(HUNDRED, 10, RoundingMode.HALF_UP);
        }
        if (discount.compareTo(gross) > 0) discount = gross;
        if (discount.signum() < 0) discount = BigDecimal.ZERO;
        BigDecimal subtotal = gross.subtract(discount).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal tax = subtotal.multiply(nz(item.getTaxRate())).divide(HUNDRED, MONEY_SCALE, RoundingMode.HALF_UP);
        item.setLineSubtotal(subtotal);
        item.setLineTax(tax);
        item.setLineTotal(subtotal.add(tax));
    }

    private DealLineItemsResponse responseFor(int workspaceId, int dealId) {
        List<DealLineItem> items = lineItemMapper.getByDealId(workspaceId, dealId);
        List<DealLineItemDto> dtos = items.stream().map(DealLineItemDto::from).toList();
        return new DealLineItemsResponse(dtos, totals(items));
    }

    private DealLineItemTotalsDto totals(List<DealLineItem> items) {
        BigDecimal subtotal = BigDecimal.ZERO.setScale(MONEY_SCALE);
        BigDecimal tax = BigDecimal.ZERO.setScale(MONEY_SCALE);
        BigDecimal oneTime = BigDecimal.ZERO.setScale(MONEY_SCALE);
        BigDecimal recurring = BigDecimal.ZERO.setScale(MONEY_SCALE);
        String currency = null;
        for (DealLineItem item : items) {
            currency = item.getCurrency();
            subtotal = subtotal.add(nz(item.getLineSubtotal()));
            tax = tax.add(nz(item.getLineTax()));
            if (RECURRING.equals(item.getBillingFrequency())) {
                recurring = recurring.add(nz(item.getLineTotal()));
            } else {
                oneTime = oneTime.add(nz(item.getLineTotal()));
            }
        }
        return new DealLineItemTotalsDto(currency, subtotal, tax, oneTime, recurring, oneTime.add(recurring));
    }

    private String normalizeFrequency(String frequency) {
        return RECURRING.equals(frequency) ? RECURRING : ONE_TIME;
    }

    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private Deal requireDeal(int workspaceId, int dealId) {
        Deal deal = dealMapper.getDealById(workspaceId, dealId);
        if (deal == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        return deal;
    }

    private Deal requireDealForUpdate(int workspaceId, int dealId) {
        Deal deal = dealMapper.getDealByIdForUpdate(workspaceId, dealId);
        if (deal == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        return deal;
    }

    private DealLineItem requireLineItem(int workspaceId, int dealId, int itemId) {
        DealLineItem item = lineItemMapper.getById(workspaceId, itemId);
        if (item == null || item.getDealId() != dealId) {
            throw new ResourceNotFoundException("Line item not found with id: " + itemId);
        }
        return item;
    }
}
