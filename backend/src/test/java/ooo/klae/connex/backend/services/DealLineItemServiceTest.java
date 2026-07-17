package ooo.klae.connex.backend.services;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Product;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.dto.DealLineItemDto;
import ooo.klae.connex.backend.dto.DealLineItemRequest;
import ooo.klae.connex.backend.dto.DealLineItemTotalsDto;
import ooo.klae.connex.backend.dto.DealLineItemsResponse;
import ooo.klae.connex.backend.exceptions.BadRequestException;

class DealLineItemServiceTest extends AbstractServiceTest {

    @Autowired DealLineItemService lineItemService;
    @Autowired ProductService productService;

    private Deal jpyDeal() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        return newDeal(pipeline, stage, company);
    }

    private Product catalogProduct(String currency, String unitPrice, String taxRate, String frequency) {
        Product p = new Product();
        p.setSku("sku_" + unique());
        p.setName("Product " + unique());
        p.setUnitPrice(new BigDecimal(unitPrice));
        p.setCurrency(currency);
        if (taxRate != null) p.setTaxRate(new BigDecimal(taxRate));
        p.setBillingFrequency(frequency);
        return productService.create(p);
    }

    private DealLineItemRequest line(Integer productId, String quantity) {
        DealLineItemRequest r = new DealLineItemRequest();
        r.setProductId(productId);
        r.setQuantity(new BigDecimal(quantity));
        return r;
    }

    private static void eq(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
            () -> "expected " + expected + " but was " + actual);
    }

    @Test
    void computesSubtotalTaxAndTotalDeterministically() {
        Deal deal = jpyDeal();
        Product product = catalogProduct("JPY", "100.00", "10.000", "one_time");

        DealLineItemsResponse res = lineItemService.create(deal.getId(), line(product.getId(), "2"));

        DealLineItemDto item = res.items().get(0);
        eq("200.00", item.getLineSubtotal());
        eq("20.00", item.getLineTax());
        eq("220.00", item.getLineTotal());
        DealLineItemTotalsDto totals = res.totals();
        eq("200.00", totals.subtotal());
        eq("20.00", totals.tax());
        eq("220.00", totals.oneTimeTotal());
        eq("0", totals.recurringTotal());
        eq("220.00", totals.grandTotal());
        assertEquals("JPY", totals.currency());
    }

    @Test
    void percentDiscountReducesSubtotalBeforeTax() {
        Deal deal = jpyDeal();
        Product product = catalogProduct("JPY", "100.00", "10.000", "one_time");
        DealLineItemRequest r = line(product.getId(), "2");
        r.setDiscountType("percent");
        r.setDiscountValue(new BigDecimal("10"));

        DealLineItemDto item = lineItemService.create(deal.getId(), r).items().get(0);
        eq("180.00", item.getLineSubtotal());
        eq("18.00", item.getLineTax());
        eq("198.00", item.getLineTotal());
    }

    @Test
    void amountDiscountReducesSubtotal() {
        Deal deal = jpyDeal();
        Product product = catalogProduct("JPY", "100.00", "10.000", "one_time");
        DealLineItemRequest r = line(product.getId(), "2");
        r.setDiscountType("amount");
        r.setDiscountValue(new BigDecimal("50.00"));

        DealLineItemDto item = lineItemService.create(deal.getId(), r).items().get(0);
        eq("150.00", item.getLineSubtotal());
        eq("15.00", item.getLineTax());
        eq("165.00", item.getLineTotal());
    }

    @Test
    void taxRoundsHalfUp() {
        Deal deal = jpyDeal();
        Product product = catalogProduct("JPY", "1.00", "12.500", "one_time");

        DealLineItemDto item = lineItemService.create(deal.getId(), line(product.getId(), "1")).items().get(0);
        eq("1.00", item.getLineSubtotal());
        eq("0.13", item.getLineTax());
        eq("1.13", item.getLineTotal());
    }

    @Test
    void separatesOneTimeAndRecurringTotals() {
        Deal deal = jpyDeal();
        Product oneTime = catalogProduct("JPY", "100.00", null, "one_time");
        Product recurring = catalogProduct("JPY", "30.00", null, "recurring");

        lineItemService.create(deal.getId(), line(oneTime.getId(), "1"));
        DealLineItemsResponse res = lineItemService.create(deal.getId(), line(recurring.getId(), "2"));

        eq("100.00", res.totals().oneTimeTotal());
        eq("60.00", res.totals().recurringTotal());
        eq("160.00", res.totals().grandTotal());
    }

    @Test
    void snapshotSurvivesProductEdit() {
        Deal deal = jpyDeal();
        Product product = catalogProduct("JPY", "100.00", "10.000", "one_time");
        String originalName = product.getName();
        lineItemService.create(deal.getId(), line(product.getId(), "1"));

        product.setName("Renamed");
        product.setUnitPrice(new BigDecimal("999.00"));
        productService.update(product.getId(), product);

        DealLineItemDto item = lineItemService.getForDeal(deal.getId()).items().get(0);
        assertEquals(originalName, item.getName());
        eq("100.00", item.getUnitPrice());
        eq("100.00", item.getLineSubtotal());
    }

    @Test
    void rejectsProductInDifferentCurrency() {
        Deal deal = jpyDeal();
        Product usd = catalogProduct("USD", "100.00", null, "one_time");

        assertThrows(BadRequestException.class,
            () -> lineItemService.create(deal.getId(), line(usd.getId(), "1")));
    }

    @Test
    void adhocLineRequiresNameAndPrice() {
        Deal deal = jpyDeal();
        DealLineItemRequest r = new DealLineItemRequest();
        r.setQuantity(BigDecimal.ONE);

        assertThrows(BadRequestException.class, () -> lineItemService.create(deal.getId(), r));
    }

    @Test
    void deleteRecomputesTotals() {
        Deal deal = jpyDeal();
        Product product = catalogProduct("JPY", "100.00", null, "one_time");
        DealLineItemsResponse afterAdd = lineItemService.create(deal.getId(), line(product.getId(), "1"));
        int itemId = afterAdd.items().get(0).getId();

        DealLineItemsResponse afterDelete = lineItemService.delete(deal.getId(), itemId);
        assertEquals(0, afterDelete.items().size());
        eq("0", afterDelete.totals().grandTotal());
    }
}
