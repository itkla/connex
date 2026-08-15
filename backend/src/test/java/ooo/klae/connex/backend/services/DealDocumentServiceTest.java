package ooo.klae.connex.backend.services;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DocumentTemplate;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Product;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.DealDocumentDto;
import ooo.klae.connex.backend.dto.DealLineItemRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;

class DealDocumentServiceTest extends AbstractServiceTest {

    @Autowired DealDocumentService documentService;
    @Autowired DocumentTemplateService templateService;
    @Autowired DealLineItemService lineItemService;
    @Autowired ProductService productService;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoSpyBean RuleTriggerPublisher ruleTriggers;

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
        assertEquals(currentUser.getId(), doc.createdBy());
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
    void nonCreatorMemberCannotDeleteAnotherMembersDraft() {
        Deal deal = jpyDeal();
        DealDocumentDto doc = documentService.generate(deal.getId(), template().getId());
        User member = newUser();
        authenticateAs(member, workspace.getId());

        assertThrows(ForbiddenException.class, () -> documentService.delete(deal.getId(), doc.id()));

        authenticateAs(currentUser, workspace.getId());
        assertEquals(doc.id(), documentService.getOne(deal.getId(), doc.id()).id());
    }

    @Test
    void adminCanDeleteAnotherMembersDraft() {
        Deal deal = jpyDeal();
        DealDocumentDto doc = documentService.generate(deal.getId(), template().getId());
        User admin = newUser();
        workspaceMapper.updateMemberRole(workspace.getId(), admin.getId(), "admin");
        authenticateAs(admin, workspace.getId());

        documentService.delete(deal.getId(), doc.id());

        assertThrows(ResourceNotFoundException.class, () -> documentService.getOne(deal.getId(), doc.id()));
    }

    @Test
    void nullCreatorIsAdminOnly() {
        Deal deal = jpyDeal();
        DealDocumentDto doc = documentService.generate(deal.getId(), template().getId());
        jdbcTemplate.update(
                "UPDATE deal_document SET created_by = NULL WHERE workspace_id = ? AND id = ?",
                workspace.getId(), doc.id());
        User member = newUser();
        authenticateAs(member, workspace.getId());

        assertThrows(ForbiddenException.class, () -> documentService.delete(deal.getId(), doc.id()));

        workspaceMapper.updateMemberRole(workspace.getId(), member.getId(), "admin");
        authenticateAs(member, workspace.getId());
        documentService.delete(deal.getId(), doc.id());
        assertThrows(ResourceNotFoundException.class, () -> documentService.getOne(deal.getId(), doc.id()));
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
    void resolvesBlockBodyTokensAndMergeNodes() {
        Deal deal = jpyDeal();
        Product product = product("100.00", "10.000");
        lineItemService.create(deal.getId(), line(product.getId(), "2"));
        DocumentTemplate tpl = new DocumentTemplate();
        tpl.setName("Body template " + unique());
        tpl.setType("quote");
        tpl.setLocale("en");
        tpl.setTitle("Body doc");
        tpl.setBody("{\"type\":\"doc\",\"content\":["
            + "{\"type\":\"paragraph\",\"attrs\":{\"textAlign\":\"right\"},\"content\":["
            + "{\"type\":\"text\",\"text\":\"Quote for {{company.name}} by \"},"
            + "{\"type\":\"mergeToken\",\"attrs\":{\"token\":\"owner.name\"}}]},"
            + "{\"type\":\"lineItems\"},"
            + "{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"Total {{total}}\"}]}]}");
        DocumentTemplate saved = templateService.create(tpl);

        DealDocumentDto doc = documentService.generate(deal.getId(), saved.getId());

        assertNotNull(doc.content().body());
        String body = doc.content().body().toString();
        assertTrue(body.contains(doc.content().company().name()), () -> "body should carry company name: " + body);
        assertTrue(body.contains(currentUser.getDisplayName()), () -> "mergeToken should resolve to owner: " + body);
        assertTrue(body.contains("JPY 220.00"), () -> "body should carry resolved total: " + body);
        assertTrue(body.contains("\"textAlign\":\"right\""), () -> "block alignment must be preserved: " + body);
        assertFalse(body.contains("{{"), () -> "no unresolved tokens should remain: " + body);
        assertFalse(body.contains("mergeToken"), () -> "merge nodes must be flattened to text: " + body);
    }

    @Test
    void rejectsInvalidTemplateBody() {
        DocumentTemplate tpl = new DocumentTemplate();
        tpl.setName("Bad body " + unique());
        tpl.setType("quote");
        tpl.setLocale("en");
        tpl.setBody("{ not valid json");
        assertThrows(BadRequestException.class, () -> templateService.create(tpl));
    }

    @Test
    void rejectsNonDocumentTemplateBody() {
        DocumentTemplate tpl = new DocumentTemplate();
        tpl.setName("Non-doc body " + unique());
        tpl.setType("quote");
        tpl.setLocale("en");
        tpl.setBody("{\"type\":\"paragraph\",\"content\":[]}");
        assertThrows(BadRequestException.class, () -> templateService.create(tpl));
    }

    @Test
    void rejectsTooDeeplyNestedTemplateBody() {
        String node = "{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"x\"}]}";
        for (int i = 0; i < 60; i++) {
            node = "{\"type\":\"bulletList\",\"content\":[" + node + "]}";
        }
        DocumentTemplate tpl = new DocumentTemplate();
        tpl.setName("Deep body " + unique());
        tpl.setType("quote");
        tpl.setLocale("en");
        tpl.setBody("{\"type\":\"doc\",\"content\":[" + node + "]}");
        assertThrows(BadRequestException.class, () -> templateService.create(tpl));
    }

    @Test
    void finalizingPublishesFinalizedTrigger() {
        Deal deal = jpyDeal();
        DealDocumentDto doc = documentService.generate(deal.getId(), template().getId());
        clearInvocations(ruleTriggers);

        documentService.updateStatus(deal.getId(), doc.id(), "final");

        verify(ruleTriggers).publish(
            workspace.getId(), "document", doc.id(), "document.finalized");
    }

    @Test
    void supersedingPublishesSupersededTrigger() {
        Deal deal = jpyDeal();
        DealDocumentDto doc = documentService.generate(deal.getId(), template().getId());
        clearInvocations(ruleTriggers);

        documentService.updateStatus(deal.getId(), doc.id(), "superseded");

        verify(ruleTriggers).publish(
            workspace.getId(), "document", doc.id(), "document.superseded");
    }

    @Test
    void noOpStatusChangePublishesNoTrigger() {
        Deal deal = jpyDeal();
        DealDocumentDto doc = documentService.generate(deal.getId(), template().getId());
        documentService.updateStatus(deal.getId(), doc.id(), "final");
        clearInvocations(ruleTriggers);

        documentService.updateStatus(deal.getId(), doc.id(), "final");

        verify(ruleTriggers, never()).publish(anyInt(), anyString(), anyInt(), anyString());
    }

    @Test
    void generatePublishesNoTrigger() {
        Deal deal = jpyDeal();
        clearInvocations(ruleTriggers);

        documentService.generate(deal.getId(), template().getId());

        verify(ruleTriggers, never()).publish(
            anyInt(), eq("document"), anyInt(), anyString());
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
