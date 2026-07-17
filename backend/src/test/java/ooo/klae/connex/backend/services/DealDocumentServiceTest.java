package ooo.klae.connex.backend.services;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DocumentTemplate;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Product;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.dto.DealDocumentDto;
import ooo.klae.connex.backend.dto.DealLineItemRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;

class DealDocumentServiceTest extends AbstractServiceTest {

    @Autowired DealDocumentService documentService;
    @Autowired DocumentTemplateService templateService;
    @Autowired DealLineItemService lineItemService;
    @Autowired ProductService productService;

    private Deal jpyDeal() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        return newDeal(pipeline, stage, company);
    }

    private Product product(String unitPrice, String taxRate) {
        Product p = new Product();
        p.setSku("sku_" + unique());
        p.setName("Product " + unique());
        p.setUnitPrice(new BigDecimal(unitPrice));
        p.setCurrency("JPY");
        if (taxRate != null) p.setTaxRate(new BigDecimal(taxRate));
        p.setBillingFrequency("one_time");
        return productService.create(p);
    }

    private DealLineItemRequest line(int productId, String quantity) {
        DealLineItemRequest r = new DealLineItemRequest();
        r.setProductId(productId);
        r.setQuantity(new BigDecimal(quantity));
        return r;
    }

    private DocumentTemplate template() {
        DocumentTemplate t = new DocumentTemplate();
        t.setName("Quote template " + unique());
        t.setType("quote");
        t.setLocale("en");
        t.setTitle("Quote for {{company.name}}");
        t.setIntro("Prepared by {{owner.name}} at {{workspace.name}} on {{date}}.");
        t.setTerms("Grand total: {{total}}. Deal: {{deal.name}} ({{deal.currency}}).");
        t.setFooter("{{company.address}}");
        return templateService.create(t);
    }

    @Test
    void generatesImmutableSnapshotWithResolvedTokens() {
        Deal deal = jpyDeal();
        Product product = product("100.00", "10.000");
        lineItemService.create(deal.getId(), line(product.getId(), "2"));
        DocumentTemplate tpl = template();

        DealDocumentDto doc = documentService.generate(deal.getId(), tpl.getId());

        assertEquals(1, doc.version());
        assertEquals("draft", doc.status());
        assertEquals("quote", doc.type());
        assertEquals("JPY", doc.currency());
        assertNotNull(doc.content());
        assertEquals("Quote for " + doc.content().company().name(), doc.content().sections().title());
        assertTrue(doc.content().sections().terms().contains("JPY 220.00"),
            () -> "terms should carry resolved total, was: " + doc.content().sections().terms());
        assertTrue(doc.content().sections().intro().contains(currentUser.getDisplayName()));
        assertEquals(1, doc.content().lineItems().size());
        assertEquals(0, new BigDecimal("220.00").compareTo(doc.content().totals().grandTotal()));
    }

    @Test
    void snapshotSurvivesTemplateAndLineItemEdits() {
        Deal deal = jpyDeal();
        Product product = product("100.00", "10.000");
        var added = lineItemService.create(deal.getId(), line(product.getId(), "2"));
        DocumentTemplate tpl = template();
        DealDocumentDto doc = documentService.generate(deal.getId(), tpl.getId());
        String frozenTerms = doc.content().sections().terms();

        tpl.setTerms("COMPLETELY DIFFERENT {{total}}");
        templateService.update(tpl.getId(), tpl);
        lineItemService.delete(deal.getId(), added.items().get(0).getId());

        DealDocumentDto reread = documentService.getOne(deal.getId(), doc.id());
        assertEquals(frozenTerms, reread.content().sections().terms());
        assertEquals(1, reread.content().lineItems().size());
        assertEquals(0, new BigDecimal("220.00").compareTo(reread.content().totals().grandTotal()));
    }

    @Test
    void versionsIncrementPerDeal() {
        Deal deal = jpyDeal();
        DocumentTemplate tpl = template();

        assertEquals(1, documentService.generate(deal.getId(), tpl.getId()).version());
        assertEquals(2, documentService.generate(deal.getId(), tpl.getId()).version());
        List<DealDocumentDto> all = documentService.getForDeal(deal.getId());
        assertEquals(2, all.size());
        assertEquals(2, all.get(0).version());
    }

    @Test
    void statusTransitionsAreGuarded() {
        Deal deal = jpyDeal();
        DocumentTemplate tpl = template();
        DealDocumentDto doc = documentService.generate(deal.getId(), tpl.getId());

        DealDocumentDto finalized = documentService.updateStatus(deal.getId(), doc.id(), "final");
        assertEquals("final", finalized.status());
        assertThrows(BadRequestException.class,
            () -> documentService.updateStatus(deal.getId(), doc.id(), "draft"));
        assertThrows(BadRequestException.class,
            () -> documentService.updateStatus(deal.getId(), doc.id(), "bogus"));
        assertEquals("superseded",
            documentService.updateStatus(deal.getId(), doc.id(), "superseded").status());
    }

    @Test
    void noOpStatusChangeIsIdempotent() {
        Deal deal = jpyDeal();
        DocumentTemplate tpl = template();
        DealDocumentDto doc = documentService.generate(deal.getId(), tpl.getId());

        DealDocumentDto same = documentService.updateStatus(deal.getId(), doc.id(), "draft");
        assertEquals("draft", same.status());
        assertEquals(doc.version(), same.version());
    }

    @Test
    void onlyDraftDocumentsCanBeDeleted() {
        Deal deal = jpyDeal();
        DocumentTemplate tpl = template();
        DealDocumentDto doc = documentService.generate(deal.getId(), tpl.getId());
        documentService.updateStatus(deal.getId(), doc.id(), "final");

        assertThrows(BadRequestException.class, () -> documentService.delete(deal.getId(), doc.id()));

        DealDocumentDto draft = documentService.generate(deal.getId(), tpl.getId());
        documentService.delete(deal.getId(), draft.id());
        assertEquals(1, documentService.getForDeal(deal.getId()).size());
    }

    @Test
    void documentIsScopedToItsDeal() {
        Deal deal = jpyDeal();
        Deal other = jpyDeal();
        DocumentTemplate tpl = template();
        DealDocumentDto doc = documentService.generate(deal.getId(), tpl.getId());

        assertThrows(ResourceNotFoundException.class,
            () -> documentService.getOne(other.getId(), doc.id()));
        assertThrows(ResourceNotFoundException.class,
            () -> documentService.updateStatus(other.getId(), doc.id(), "final"));
    }

    @Test
    void generatesWithoutLineItems() {
        Deal deal = jpyDeal();
        DocumentTemplate tpl = template();

        DealDocumentDto doc = documentService.generate(deal.getId(), tpl.getId());
        assertEquals(0, doc.content().lineItems().size());
        assertEquals(0, new BigDecimal("0").compareTo(doc.content().totals().grandTotal()));
    }
}
