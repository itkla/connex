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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import ooo.klae.connex.backend.exceptions.ConflictException;
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
        r.setConfirmValueReplacement(true);
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
        assertTrue(doc.content().sections().terms().contains("¥220"),
            () -> "terms should carry resolved total, was: " + doc.content().sections().terms());
        assertTrue(doc.content().sections().intro().contains(currentUser.getDisplayName()));
        assertEquals(1, doc.content().lineItems().size());
        assertEquals(0, new BigDecimal("220.00").compareTo(doc.content().totals().grandTotal()));
    }

    @ParameterizedTest
    @CsvSource(value = {
        "en|JPY|3174499.50|¥3,174,500",
        "ja|JPY|3174499.50|￥3,174,500",
        "en|JPY|3174498.50|¥3,174,499",
        "ja|JPY|3174498.50|￥3,174,499",
        "en|JPY|0.49|¥0",
        "ja|JPY|0.50|￥1",
        "en|KRW|3174499.50|₩3,174,500",
        "ja|KRW|3174499.50|₩3,174,500",
        "en|USD|3174499.50|$3,174,499.50",
        "ja|USD|3174499.50|$3,174,499.50",
        "en|USD|220.00|$220.00",
        "ja|USD|220.00|$220.00",
        "en|EUR|3174499.50|€3,174,499.50",
        "ja|EUR|3174499.50|€3,174,499.50",
        "en|CHF|3174499.50|CHF\u00a03,174,499.50",
        "ja|CHF|3174499.50|CHF\u00a03,174,499.50",
        "en|YEN|3174499.50|YEN 3174499.50",
        "ja|YEN|3174499.50|YEN 3174499.50"
    }, delimiter = '|')
    void formatsTotalTokensWithoutChangingNumericSnapshot(
            String locale, String currency, String amount, String expected) {
        Deal deal = jpyDeal();
        deal.setCurrency(currency);
        dealMapper.update(deal);
        DealLineItemRequest request = new DealLineItemRequest();
        request.setConfirmValueReplacement(true);
        request.setName("Quoted item");
        request.setUnitPrice(new BigDecimal(amount));
        request.setQuantity(BigDecimal.ONE);
        lineItemService.create(deal.getId(), request);
        DocumentTemplate tpl = template();
        tpl.setLocale(locale);
        tpl.setTitle("{{total}}");
        tpl.setIntro("{{total}}");
        tpl.setTerms("{{total}}");
        tpl.setFooter("{{total}}");
        tpl.setBody("""
            {"type":"doc","content":[
              {"type":"paragraph","content":[{"type":"text","text":"Total {{total}}"}]},
              {"type":"lineItems"},
              {"type":"paragraph","content":[{"type":"mergeToken","attrs":{"token":"total"}}]}
            ]}
            """);
        templateService.update(tpl.getId(), tpl);

        DealDocumentDto doc = documentService.generate(deal.getId(), tpl.getId());

        assertEquals(locale, doc.locale());
        assertEquals(currency, doc.currency());
        assertEquals(expected, doc.title());
        assertEquals(expected, doc.content().sections().title());
        assertEquals(expected, doc.content().sections().intro());
        assertEquals(expected, doc.content().sections().terms());
        assertEquals(expected, doc.content().sections().footer());
        assertEquals("Total " + expected,
            doc.content().body().path("content").get(0).path("content").get(0).path("text").asString());
        assertEquals(expected,
            doc.content().body().path("content").get(2).path("content").get(0).path("text").asString());
        assertEquals(0, new BigDecimal(amount).compareTo(doc.content().totals().grandTotal()));
        assertEquals(0, new BigDecimal(amount).compareTo(doc.content().lineItems().getFirst().getLineTotal()));
        assertEquals(0, new BigDecimal(amount).compareTo(
            dealMapper.getDealById(workspace.getId(), deal.getId()).getValue()));
        DealDocumentDto reread = documentService.getOne(deal.getId(), doc.id());
        assertEquals(doc.content(), reread.content());
    }

    @Test
    void leavesPreviouslyGeneratedRawTotalSnapshotsUnchanged() {
        Deal deal = jpyDeal();
        lineItemService.create(deal.getId(), line(product("100.00", "10.000").getId(), "2"));
        DocumentTemplate tpl = template();
        DealDocumentDto doc = documentService.generate(deal.getId(), tpl.getId());
        String legacyTerms = "Grand total: JPY 220.00.";
        jdbcTemplate.update(
            "UPDATE deal_document SET content = JSON_SET(content, '$.sections.terms', ?) WHERE workspace_id = ? AND id = ?",
            legacyTerms, workspace.getId(), doc.id());
        String frozenContent = jdbcTemplate.queryForObject(
            "SELECT content FROM deal_document WHERE workspace_id = ? AND id = ?",
            String.class, workspace.getId(), doc.id());

        documentService.generate(deal.getId(), tpl.getId());

        DealDocumentDto reread = documentService.getOne(deal.getId(), doc.id());
        assertEquals(legacyTerms, reread.content().sections().terms());
        assertEquals(0, new BigDecimal("220.00").compareTo(reread.content().totals().grandTotal()));
        assertEquals(frozenContent, jdbcTemplate.queryForObject(
            "SELECT content FROM deal_document WHERE workspace_id = ? AND id = ?",
            String.class, workspace.getId(), doc.id()));
    }

    /**
     * Deactivating a template retires it: the deal documents picker hides it, and a direct call with
     * its id is refused before any version is written (#1927).
     */
    @Test
    void aDeactivatedTemplateCannotGenerateADocument() {
        Deal deal = jpyDeal();
        DocumentTemplate tpl = template();
        tpl.setActive(false);
        templateService.update(tpl.getId(), tpl);

        assertThrows(ConflictException.class, () -> documentService.generate(deal.getId(), tpl.getId()));

        assertTrue(documentService.getForDeal(deal.getId()).isEmpty());
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
        assertTrue(body.contains("¥220"), () -> "body should carry resolved total: " + body);
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
        DocumentTemplate tpl = new DocumentTemplate();
        tpl.setName("Deep body " + unique());
        tpl.setType("quote");
        tpl.setLocale("en");
        tpl.setBody(nestedBlockquoteBody(49));
        BadRequestException refusal = assertThrows(BadRequestException.class, () -> templateService.create(tpl));
        assertEquals("Document body is nested too deeply", refusal.getMessage());
    }

    @Test
    void acceptsTemplateBodyAtMaximumDepth() {
        DocumentTemplate tpl = new DocumentTemplate();
        tpl.setName("Boundary-depth body " + unique());
        tpl.setType("quote");
        tpl.setLocale("en");
        tpl.setBody(nestedBlockquoteBody(48));

        assertNotNull(templateService.create(tpl).getBody());
    }

    private String nestedBlockquoteBody(int levels) {
        String node = "{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"x\"}]}";
        for (int i = 0; i < levels; i++) {
            node = "{\"type\":\"blockquote\",\"content\":[" + node + "]}";
        }
        return "{\"type\":\"doc\",\"content\":[" + node + "]}";
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
        assertTrue(doc.content().sections().terms().contains("¥0"));
    }
}
