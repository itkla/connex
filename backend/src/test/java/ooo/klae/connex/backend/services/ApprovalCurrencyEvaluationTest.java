package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.ApprovalPolicy;
import ooo.klae.connex.backend.beans.DealDocument;
import ooo.klae.connex.backend.dto.ApprovalPolicyDto;
import ooo.klae.connex.backend.dto.DocumentContent;
import ooo.klae.connex.backend.dto.DealLineItemTotalsDto;
import ooo.klae.connex.backend.exceptions.InvalidApprovalCurrencyException;
import ooo.klae.connex.backend.mappers.ApprovalPolicyMapper;
import ooo.klae.connex.backend.mappers.DocumentApprovalMapper;

class ApprovalCurrencyEvaluationTest {
    private final ApprovalPolicyService service = new ApprovalPolicyService(
        mock(ApprovalPolicyMapper.class), mock(DocumentApprovalMapper.class), mock(AuditService.class),
        mock(WorkspaceService.class), null, mock(ApprovalMutationRetryService.class));

    private ApprovalPolicy policy(String currency) {
        ApprovalPolicy policy = new ApprovalPolicy();
        policy.setActive(true);
        policy.setCurrency(currency);
        policy.setMinTotal(BigDecimal.TEN);
        return policy;
    }

    private DealDocument document() {
        DealDocument document = new DealDocument();
        document.setType("quote");
        document.setCurrency("JPY");
        return document;
    }

    private DocumentContent content(String total) {
        BigDecimal amount = new BigDecimal(total);
        return new DocumentContent(null, null, null, null, null, null, null, List.of(),
            new DealLineItemTotalsDto("JPY", amount, BigDecimal.ZERO, amount, BigDecimal.ZERO, amount));
    }

    @Test
    void invalidPoliciesRefuseOnlyMatchingCurrencyBucketsAndThresholds() {
        ApprovalPolicy invalid = policy("YEN");
        invalid.setMinTotal(new BigDecimal("1000000"));
        DealDocument usd = document();
        usd.setCurrency("USD");
        org.junit.jupiter.api.Assertions.assertNull(service.firstMatch(List.of(invalid), usd, content("10")));
        org.junit.jupiter.api.Assertions.assertNull(service.firstMatch(List.of(invalid), usd, content("2000000")));
        DealDocument legacy = document();
        legacy.setCurrency(" yen ");
        org.junit.jupiter.api.Assertions.assertNull(service.firstMatch(List.of(invalid), legacy, content("10")));
        assertThrows(InvalidApprovalCurrencyException.class,
            () -> service.firstMatch(List.of(invalid), legacy, content("2000000")));
        ApprovalPolicy blanket = policy(null);
        blanket.setMinTotal(null);
        assertThrows(InvalidApprovalCurrencyException.class,
            () -> service.firstMatch(List.of(blanket, invalid), legacy, content("2000000")));
        assertFalse(ApprovalPolicyDto.from(invalid).isCurrencyValid());
        assertEquals("YEN", invalid.getCurrency());
    }

    @Test
    void missingCurrencyDoesNotInventABucketAndRemainsVisibleToAdmins() {
        ApprovalPolicy missing = policy(null);
        org.junit.jupiter.api.Assertions.assertNull(service.firstMatch(List.of(missing), document(), content("100")));
        assertFalse(ApprovalPolicyDto.from(missing).isCurrencyValid());
        missing.setMinTotal(null);
        assertTrue(ApprovalPolicyDto.from(missing).isCurrencyValid());
    }

    @Test
    void unrelatedDocumentTypesDoNotBlockEvaluation() {
        ApprovalPolicy invalid = policy("yen");
        invalid.setDocumentType("contract");
        ApprovalPolicy blanket = policy(null);
        blanket.setMinTotal(null);
        assertSame(blanket, service.firstMatch(List.of(invalid, blanket), document(), null));
    }

    @Test
    void validLegacyCaseVariantsAreReportedAsValidWithoutRewritingThem() {
        ApprovalPolicy legacy = policy(" jpy ");
        assertTrue(ApprovalPolicyDto.from(legacy).isCurrencyValid());
        DocumentContent content = new DocumentContent(null, null, null, null, null, null, null, List.of(),
            new DealLineItemTotalsDto("JPY", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN,
                BigDecimal.ZERO, BigDecimal.TEN));
        assertSame(legacy, service.firstMatch(List.of(legacy), document(), content));
        assertEquals(" jpy ", legacy.getCurrency());
    }
}
