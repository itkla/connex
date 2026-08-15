package ooo.klae.connex.backend.services;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.ApprovalPolicy;
import ooo.klae.connex.backend.beans.ApprovalPolicyStep;
import ooo.klae.connex.backend.beans.ApprovalStepApprover;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DocumentTemplate;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Product;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.ApprovalPolicyImpactDto;
import ooo.klae.connex.backend.dto.DealDocumentDto;
import ooo.klae.connex.backend.dto.DealLineItemRequest;
import ooo.klae.connex.backend.dto.DocumentApprovalDto;
import ooo.klae.connex.backend.exceptions.ApprovalImpactConfirmationRequiredException;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.ApprovalPolicyMapper;
import ooo.klae.connex.backend.mappers.DocumentApprovalMapper;

class ApprovalPolicyServiceTest extends AbstractServiceTest {

    @Autowired ApprovalPolicyService policyService;
    @Autowired DocumentApprovalService approvalService;
    @Autowired DealDocumentService documentService;
    @Autowired DocumentTemplateService templateService;
    @Autowired DealLineItemService lineItemService;
    @Autowired ProductService productService;
    @Autowired ApprovalPolicyMapper policyMapper;
    @Autowired DocumentApprovalMapper approvalMapper;
    @Autowired JdbcTemplate jdbcTemplate;

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
    void currencyComparisonSurvivesCaseAndWhitespaceVariants() {
        ApprovalPolicy saved = policy(null, " jpy ", "100", null);
        assertEquals("JPY", saved.getCurrency());

        Deal deal = jpyDeal();
        addLine(deal, "500.00", "1", null, null);
        assertTrue(quote(deal).requiresApproval());
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
        policyService.update(p.getId(), p, false);
        assertFalse(quote(deal).requiresApproval());
    }

    private ApprovalStepApprover anyApprover() {
        ApprovalStepApprover approver = new ApprovalStepApprover();
        approver.setApproverKind("any_approver");
        return approver;
    }

    private ApprovalStepApprover namedApprover(int userId) {
        ApprovalStepApprover approver = new ApprovalStepApprover();
        approver.setApproverKind("user");
        approver.setUserId(userId);
        return approver;
    }

    private ApprovalPolicyStep step(int requiredCount, String name, ApprovalStepApprover... approvers) {
        ApprovalPolicyStep step = new ApprovalPolicyStep();
        step.setName(name);
        step.setRequiredCount(requiredCount);
        step.setApprovers(List.of(approvers));
        return step;
    }

    private ApprovalPolicy chained(String mode, ApprovalPolicyStep... steps) {
        ApprovalPolicy policy = new ApprovalPolicy();
        policy.setName("Chained " + unique());
        policy.setActive(true);
        policy.setMode(mode);
        policy.setSteps(List.of(steps));
        return policy;
    }

    private User admin() {
        User admin = newUser();
        workspaceMapper.updateMemberRole(workspace.getId(), admin.getId(), "admin");
        return admin;
    }

    private ApprovalPolicy copyPolicy(ApprovalPolicy source) {
        ApprovalPolicy copy = new ApprovalPolicy();
        copy.setId(source.getId());
        copy.setWorkspaceId(source.getWorkspaceId());
        copy.setName(source.getName());
        copy.setActive(source.isActive());
        copy.setDocumentType(source.getDocumentType());
        copy.setCurrency(source.getCurrency());
        copy.setMinTotal(source.getMinTotal());
        copy.setMinDiscountPercent(source.getMinDiscountPercent());
        copy.setMode(source.getMode());
        copy.setSeparationOfDuties(source.getSeparationOfDuties());
        copy.setSteps(source.getSteps().stream().map(this::copyStep).toList());
        return copy;
    }

    private ApprovalPolicyStep copyStep(ApprovalPolicyStep source) {
        ApprovalPolicyStep copy = new ApprovalPolicyStep();
        copy.setId(source.getId());
        copy.setWorkspaceId(source.getWorkspaceId());
        copy.setPolicyId(source.getPolicyId());
        copy.setStepOrder(source.getStepOrder());
        copy.setName(source.getName());
        copy.setRequiredCount(source.getRequiredCount());
        copy.setApprovers(source.getApprovers().stream().map(this::copyApprover).toList());
        return copy;
    }

    private ApprovalStepApprover copyApprover(ApprovalStepApprover source) {
        ApprovalStepApprover copy = new ApprovalStepApprover();
        copy.setId(source.getId());
        copy.setWorkspaceId(source.getWorkspaceId());
        copy.setStepId(source.getStepId());
        copy.setApproverKind(source.getApproverKind());
        copy.setUserId(source.getUserId());
        return copy;
    }

    private ApprovalPolicy classificationPolicy() {
        ApprovalPolicy policy = new ApprovalPolicy();
        policy.setName("Classification policy");
        policy.setActive(true);
        policy.setDocumentType("quote");
        policy.setCurrency("JPY");
        policy.setMinTotal(new BigDecimal("100.00"));
        policy.setMinDiscountPercent(new BigDecimal("10.000"));
        policy.setMode("sequential");
        policy.setSeparationOfDuties("requester");
        ApprovalPolicyStep first = step(1, "First", namedApprover(101), namedApprover(102));
        first.setId(11);
        ApprovalPolicyStep second = step(1, "Second", anyApprover());
        second.setId(12);
        policy.setSteps(List.of(first, second));
        return policy;
    }

    private void assertChange(PolicyChangeClass expected, String label,
            ApprovalPolicy before, Consumer<ApprovalPolicy> mutation) {
        ApprovalPolicy after = copyPolicy(before);
        mutation.accept(after);
        assertEquals(expected, policyService.classify(before, after), label);
    }

    private PendingApproval pendingUnder(ApprovalPolicy policy) {
        Deal deal = jpyDeal();
        DealDocumentDto document = quote(deal);
        DocumentApprovalDto approval = approvalService.requestApproval(
            deal.getId(), document.id(), null);
        assertEquals(policy.getId(), approval.policyId());
        return new PendingApproval(deal, document, approval);
    }

    private record PendingApproval(
        Deal deal,
        DealDocumentDto document,
        DocumentApprovalDto approval
    ) {
    }

    @Test
    void chainRoundTripsAndIsReplacedWholesaleOnUpdate() {
        User one = admin();
        User two = admin();
        ApprovalPolicy saved = policyService.create(chained("parallel",
            step(2, "Managers", namedApprover(one.getId()), namedApprover(two.getId())),
            step(1, "Finance", anyApprover())));

        assertEquals("parallel", saved.getMode());
        assertEquals("strict", saved.getSeparationOfDuties());
        assertEquals(2, saved.getSteps().size());
        assertEquals(1, saved.getSteps().get(0).getStepOrder());
        assertEquals(2, saved.getSteps().get(0).getRequiredCount());
        assertEquals(2, saved.getSteps().get(0).getApprovers().size());
        assertEquals("any_approver", saved.getSteps().get(1).getApprovers().getFirst().getApproverKind());

        ApprovalPolicy replacement = chained("sequential", step(1, "Only", namedApprover(one.getId())));
        replacement.setName(saved.getName());
        ApprovalPolicy updated = policyService.update(saved.getId(), replacement, false);

        assertEquals(1, updated.getSteps().size());
        assertEquals("Only", updated.getSteps().getFirst().getName());
        assertEquals("sequential", updated.getMode());
    }

    @Test
    void aQuorumLargerThanItsApproversIsRefused() {
        User one = admin();
        assertThrows(BadRequestException.class,
            () -> policyService.create(chained("sequential", step(2, "Two", namedApprover(one.getId())))));
    }

    @Test
    void anApproverWhoCannotApproveDocumentsIsRefused() {
        User member = newUser();
        assertThrows(BadRequestException.class,
            () -> policyService.create(chained("sequential", step(1, "Member", namedApprover(member.getId())))));
    }

    @Test
    void mixingAnyApproverWithNamedApproversIsRefused() {
        User one = admin();
        assertThrows(BadRequestException.class, () -> policyService.create(chained("sequential",
            step(1, "Mixed", anyApprover(), namedApprover(one.getId())))));
    }

    @Test
    void listingTheSameApproverTwiceOnAStepIsRefused() {
        User one = admin();
        assertThrows(BadRequestException.class, () -> policyService.create(chained("sequential",
            step(1, "Duplicated", namedApprover(one.getId()), namedApprover(one.getId())))));
    }

    @Test
    void classificationFollowsTightenLoosenRetargetAndNonePrecedence() {
        ApprovalPolicy base = classificationPolicy();
        assertChange(PolicyChangeClass.TIGHTEN, "step added", base, after -> {
            List<ApprovalPolicyStep> steps = new ArrayList<>(after.getSteps());
            steps.add(step(1, "Third", anyApprover()));
            after.setSteps(steps);
        });
        assertChange(PolicyChangeClass.TIGHTEN, "quorum raised", base,
            after -> after.getSteps().getFirst().setRequiredCount(2));
        assertChange(PolicyChangeClass.TIGHTEN, "named approver removed", base,
            after -> after.getSteps().getFirst().setApprovers(List.of(namedApprover(101))));
        assertChange(PolicyChangeClass.TIGHTEN, "any approver narrowed", base,
            after -> after.getSteps().get(1).setApprovers(List.of(namedApprover(101))));
        assertChange(PolicyChangeClass.TIGHTEN, "separation tightened", base,
            after -> after.setSeparationOfDuties("strict"));

        assertChange(PolicyChangeClass.LOOSEN, "step removed", base,
            after -> after.setSteps(List.of(after.getSteps().get(1))));
        ApprovalPolicy higherQuorum = copyPolicy(base);
        higherQuorum.getSteps().getFirst().setRequiredCount(2);
        assertChange(PolicyChangeClass.LOOSEN, "quorum lowered", higherQuorum,
            after -> after.getSteps().getFirst().setRequiredCount(1));
        assertChange(PolicyChangeClass.LOOSEN, "named approver added", base,
            after -> after.getSteps().getFirst().setApprovers(
                List.of(namedApprover(101), namedApprover(102), namedApprover(103))));
        assertChange(PolicyChangeClass.LOOSEN, "named approvers widened", base,
            after -> after.getSteps().getFirst().setApprovers(List.of(anyApprover())));
        assertChange(PolicyChangeClass.LOOSEN, "separation relaxed", base,
            after -> after.setSeparationOfDuties("off"));

        assertChange(PolicyChangeClass.RETARGET, "name", base,
            after -> after.setName("Retargeted"));
        assertChange(PolicyChangeClass.RETARGET, "active", base,
            after -> after.setActive(false));
        assertChange(PolicyChangeClass.RETARGET, "document type", base,
            after -> after.setDocumentType("contract"));
        assertChange(PolicyChangeClass.RETARGET, "currency", base,
            after -> after.setCurrency("USD"));
        assertChange(PolicyChangeClass.RETARGET, "minimum total", base,
            after -> after.setMinTotal(new BigDecimal("200")));
        assertChange(PolicyChangeClass.RETARGET, "minimum discount", base,
            after -> after.setMinDiscountPercent(new BigDecimal("20")));
        assertChange(PolicyChangeClass.RETARGET, "mode", base,
            after -> after.setMode("parallel"));
        assertChange(PolicyChangeClass.TIGHTEN, "tightening wins over retargeting", base, after -> {
            after.setName("Also retargeted");
            after.setSeparationOfDuties("strict");
        });
        assertEquals(PolicyChangeClass.NONE, policyService.classify(base, copyPolicy(base)));
    }

    @Test
    void tighteningWithoutConfirmationLeavesPolicyAndFrozenChainUnchanged() {
        User approver = admin();
        ApprovalPolicy saved = policyService.create(chained("sequential",
            step(1, "Manager", namedApprover(approver.getId()))));
        PendingApproval pending = pendingUnder(saved);
        ApprovalPolicy proposed = copyPolicy(saved);
        List<ApprovalPolicyStep> steps = new ArrayList<>(proposed.getSteps());
        steps.add(step(1, "Finance", anyApprover()));
        proposed.setSteps(steps);

        ApprovalImpactConfirmationRequiredException exception = assertThrows(
            ApprovalImpactConfirmationRequiredException.class,
            () -> policyService.update(saved.getId(), proposed, false));

        assertTrue(exception.getMessage().contains("1 pending approval request"));
        assertEquals(1, policyMapper.getWithStepsById(workspace.getId(), saved.getId()).getSteps().size());
        DocumentApprovalDto reread = approvalService.getForDocument(
            pending.deal().getId(), pending.document().id()).getFirst();
        assertEquals("pending", reread.status());
        assertEquals(1, reread.steps().size());
        assertEquals("pending_approval",
            documentService.getOne(pending.deal().getId(), pending.document().id()).status());
    }

    @Test
    void confirmedTighteningInvalidatesWithoutRewritingTheFrozenChain() {
        User first = admin();
        User second = admin();
        ApprovalPolicy saved = policyService.create(chained("sequential",
            step(2, "Managers", namedApprover(first.getId()), namedApprover(second.getId()))));
        PendingApproval pending = pendingUnder(saved);
        authenticateAs(first, workspace.getId());
        approvalService.decide(
            pending.deal().getId(), pending.document().id(), "approved", null, null);
        authenticateAs(currentUser, workspace.getId());
        ApprovalPolicy proposed = copyPolicy(saved);
        List<ApprovalPolicyStep> steps = new ArrayList<>(proposed.getSteps());
        steps.add(step(1, "Finance", anyApprover()));
        proposed.setSteps(steps);

        policyService.update(saved.getId(), proposed, true);

        DocumentApprovalDto invalidated = approvalService.getForDocument(
            pending.deal().getId(), pending.document().id()).getFirst();
        assertEquals("invalidated", invalidated.status());
        assertEquals("policy_invalidated", invalidated.outcomeReason());
        assertNotNull(invalidated.outcomeDetail());
        assertEquals("cancelled", invalidated.steps().getFirst().status());
        assertEquals(1, invalidated.steps().size());
        assertEquals(2, invalidated.steps().getFirst().approvers().size());
        assertEquals(1, invalidated.steps().getFirst().decisions().size());
        assertEquals("draft",
            documentService.getOne(pending.deal().getId(), pending.document().id()).status());
        assertEquals(2, policyMapper.getWithStepsById(workspace.getId(), saved.getId()).getSteps().size());
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM document_approval_decision WHERE workspace_id = ? AND approval_id = ?",
            Integer.class, workspace.getId(), pending.approval().id()));
    }

    @Test
    void looseningLeavesPendingApprovalAndFrozenChainUntouched() {
        User approver = admin();
        ApprovalPolicy saved = policyService.create(chained("sequential",
            step(1, "Manager", namedApprover(approver.getId())),
            step(1, "Finance", anyApprover())));
        PendingApproval pending = pendingUnder(saved);
        ApprovalPolicy proposed = copyPolicy(saved);
        proposed.setSteps(List.of(proposed.getSteps().getFirst()));

        policyService.update(saved.getId(), proposed, false);

        DocumentApprovalDto reread = approvalService.getForDocument(
            pending.deal().getId(), pending.document().id()).getFirst();
        assertEquals("pending", reread.status());
        assertEquals(2, reread.steps().size());
    }

    @Test
    void retargetingLeavesPendingApprovalAndFrozenChainUntouched() {
        User approver = admin();
        ApprovalPolicy saved = policyService.create(chained("sequential",
            step(1, "Manager", namedApprover(approver.getId()))));
        PendingApproval pending = pendingUnder(saved);
        ApprovalPolicy proposed = copyPolicy(saved);
        proposed.setName("Retargeted " + unique());

        policyService.update(saved.getId(), proposed, false);

        DocumentApprovalDto reread = approvalService.getForDocument(
            pending.deal().getId(), pending.document().id()).getFirst();
        assertEquals("pending", reread.status());
        assertEquals(1, reread.steps().size());
    }

    @Test
    void identicalUpdateLeavesPendingApprovalAndFrozenChainUntouched() {
        User approver = admin();
        ApprovalPolicy saved = policyService.create(chained("sequential",
            step(1, "Manager", namedApprover(approver.getId()))));
        ApprovalPolicy stalePayload = copyPolicy(saved);
        ApprovalPolicy refreshed = policyService.update(
            saved.getId(), copyPolicy(saved), false);
        assertEquals(saved.getSteps().getFirst().getId(),
            refreshed.getSteps().getFirst().getId());
        PendingApproval pending = pendingUnder(refreshed);

        policyService.update(saved.getId(), stalePayload, false);

        DocumentApprovalDto reread = approvalService.getForDocument(
            pending.deal().getId(), pending.document().id()).getFirst();
        assertEquals("pending", reread.status());
        assertEquals(1, reread.steps().size());
    }

    @Test
    void updateRejectsAStaleStepIdentity() {
        User approver = admin();
        ApprovalPolicy saved = policyService.create(chained("sequential",
            step(1, "Manager", namedApprover(approver.getId()))));
        ApprovalPolicy proposed = copyPolicy(saved);
        proposed.getSteps().getFirst().setId(saved.getSteps().getFirst().getId() + 100_000);

        assertThrows(ConflictException.class,
            () -> policyService.update(saved.getId(), proposed, false));

        ApprovalPolicy unchanged = policyMapper.getWithStepsById(workspace.getId(), saved.getId());
        assertEquals(saved.getSteps().getFirst().getId(),
            unchanged.getSteps().getFirst().getId());
    }

    @Test
    void deletingPolicyLeavesPendingApprovalAndNullsPolicyId() {
        User approver = admin();
        ApprovalPolicy saved = policyService.create(chained("sequential",
            step(1, "Manager", namedApprover(approver.getId()))));
        PendingApproval pending = pendingUnder(saved);

        policyService.delete(saved.getId());

        DocumentApprovalDto reread = approvalService.getForDocument(
            pending.deal().getId(), pending.document().id()).getFirst();
        assertEquals("pending", reread.status());
        assertNull(reread.policyId());
        assertEquals(1, reread.steps().size());
    }

    @Test
    void impactReturnsAuthoritativeCountCapsItemsAndHidesForeignPolicy() {
        User approver = admin();
        ApprovalPolicy saved = policyService.create(chained("sequential",
            step(1, "Manager", namedApprover(approver.getId()))));
        Deal deal = jpyDeal();
        DocumentTemplate template = template("quote");
        for (int index = 0; index < 21; index++) {
            DealDocumentDto document = documentService.generate(deal.getId(), template.getId());
            approvalService.requestApproval(deal.getId(), document.id(), null);
        }
        ApprovalPolicy proposed = copyPolicy(saved);
        List<ApprovalPolicyStep> steps = new ArrayList<>(proposed.getSteps());
        steps.add(step(1, "Finance", anyApprover()));
        proposed.setSteps(steps);

        ApprovalPolicyImpactDto impact = policyService.impact(saved.getId(), proposed);

        assertEquals("TIGHTEN", impact.changeClass());
        assertEquals(21, impact.pendingApprovalCount());
        assertEquals("invalidate_pending_approvals", impact.effect());
        assertEquals(20, impact.affected().size());
        assertTrue(impact.affected().stream().allMatch(item -> item.dealId() == deal.getId()));
        assertTrue(impact.affected().stream().allMatch(item -> item.requestedAt() != null));

        Workspace foreignWorkspace = new Workspace();
        foreignWorkspace.setOrgId(workspaceMapper.getOrgId(workspace.getId()));
        foreignWorkspace.setName("Foreign " + unique());
        foreignWorkspace.setSlug("foreign-" + unique());
        workspaceMapper.insert(foreignWorkspace);
        workspaceMapper.addMember(foreignWorkspace.getId(), currentUser.getId(), "owner");
        authenticateAs(currentUser, foreignWorkspace.getId());
        ApprovalPolicy foreign = policyService.create(chained("sequential",
            step(1, "Foreign", anyApprover())));
        authenticateAs(currentUser, workspace.getId());

        assertThrows(ResourceNotFoundException.class,
            () -> policyService.impact(foreign.getId(), copyPolicy(foreign)));
        assertNull(policyMapper.getByIdForUpdate(workspace.getId(), foreign.getId()));
        assertTrue(policyMapper.getAllForUpdate(workspace.getId()).stream()
            .noneMatch(policy -> policy.getId() == foreign.getId()));
        assertTrue(policyMapper.getStepsByPolicyIdsForUpdate(
            workspace.getId(), List.of(foreign.getId())).isEmpty());
        assertTrue(approvalMapper.findPendingByPolicyId(
            foreignWorkspace.getId(), saved.getId()).isEmpty());
        assertEquals(0, approvalMapper.countPendingByPolicyId(
            foreignWorkspace.getId(), saved.getId()));
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
