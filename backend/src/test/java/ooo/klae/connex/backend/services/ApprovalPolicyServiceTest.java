package ooo.klae.connex.backend.services;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.ApprovalPolicy;
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

class ApprovalPolicyServiceTest extends AbstractServiceTest {

    @Autowired ApprovalPolicyService policyService;
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

    private DocumentTemplate template(String type) {
        DocumentTemplate t = new DocumentTemplate();
        t.setName("Template " + unique());
        t.setType(type);
        t.setLocale("en");
        t.setTitle("Doc for {{company.name}}");
        return templateService.create(t);
    }

    private void addLine(Deal deal, String unitPrice, String quantity, String discountType, String discountValue) {
        Product p = new Product();
        p.setSku("sku_" + unique());
        p.setName("Product " + unique());
        p.setUnitPrice(new BigDecimal(unitPrice));
        p.setCurrency("JPY");
        p.setBillingFrequency("one_time");
        Product product = productService.create(p);
        DealLineItemRequest r = new DealLineItemRequest();
        r.setProductId(product.getId());
        r.setQuantity(new BigDecimal(quantity));
        if (discountType != null) {
            r.setDiscountType(discountType);
            r.setDiscountValue(new BigDecimal(discountValue));
        }
        lineItemService.create(deal.getId(), r);
    }

    private ApprovalPolicy policy(String documentType, String currency, String minTotal, String minDiscountPercent) {
        ApprovalPolicy policy = new ApprovalPolicy();
        policy.setName("Policy " + unique());
        policy.setActive(true);
        policy.setDocumentType(documentType);
        policy.setCurrency(currency);
        if (minTotal != null) policy.setMinTotal(new BigDecimal(minTotal));
        if (minDiscountPercent != null) policy.setMinDiscountPercent(new BigDecimal(minDiscountPercent));
        return policyService.create(policy);
    }

    private DealDocumentDto quote(Deal deal) {
        return documentService.generate(deal.getId(), template("quote").getId());
    }

    @Test
    void minTotalRequiresCurrency() {
        ApprovalPolicy invalid = new ApprovalPolicy();
        invalid.setName("No currency");
        invalid.setActive(true);
        invalid.setMinTotal(new BigDecimal("100"));
        assertThrows(BadRequestException.class, () -> policyService.create(invalid));
    }

    @Test
    void blanketPolicyMatchesEveryDocumentOfItsType() {
        policy("contract", null, null, null);
        Deal deal = jpyDeal();

        assertFalse(quote(deal).requiresApproval());
        DealDocumentDto contract = documentService.generate(deal.getId(), template("contract").getId());
        assertTrue(contract.requiresApproval());
    }

    @Test
    void totalThresholdIsCurrencyExplicit() {
        policy(null, "USD", "100", null);
        Deal deal = jpyDeal();
        addLine(deal, "500.00", "1", null, null);

        assertFalse(quote(deal).requiresApproval());
    }

    @Test
    void totalThresholdMatchesAtTheBoundary() {
        policy(null, "JPY", "500", null);
        Deal below = jpyDeal();
        addLine(below, "499.00", "1", null, null);
        Deal at = jpyDeal();
        addLine(at, "500.00", "1", null, null);

        assertFalse(quote(below).requiresApproval());
        assertTrue(quote(at).requiresApproval());
    }

    @Test
    void discountThresholdUsesEffectiveDiscountAcrossLines() {
        policy(null, null, null, "20");
        Deal deal = jpyDeal();
        addLine(deal, "100.00", "2", "percent", "25");

        assertTrue(quote(deal).requiresApproval());

        Deal mild = jpyDeal();
        addLine(mild, "100.00", "2", "percent", "10");
        assertFalse(quote(mild).requiresApproval());
    }

    @Test
    void inactivePoliciesAreIgnored() {
        ApprovalPolicy p = policy(null, "JPY", "1", null);
        Deal deal = jpyDeal();
        addLine(deal, "100.00", "1", null, null);
        assertTrue(quote(deal).requiresApproval());

        p.setActive(false);
        policyService.update(p.getId(), p);
        assertFalse(quote(deal).requiresApproval());
    }

    @Test
    void mutationsRequireDocumentManagePermission() {
        ApprovalPolicy existing = policy(null, null, null, "50");
        User member = newUser();
        authenticateAs(member, workspace.getId());

        ApprovalPolicy attempt = new ApprovalPolicy();
        attempt.setName("Member policy");
        attempt.setActive(true);
        assertThrows(ForbiddenException.class, () -> policyService.create(attempt));
        assertThrows(ForbiddenException.class, () -> policyService.delete(existing.getId()));
        assertEquals(1, policyService.getAll().size());
    }
}
