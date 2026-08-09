package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HexFormat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.mappers.DealDuplicateReviewProofMapper;

@ExtendWith(MockitoExtension.class)
class DealDuplicateReviewProofServiceTest {
    @Mock private DealDuplicateReviewProofMapper mapper;
    @Mock private WorkspaceService workspaceService;

    private DuplicatePreflightProperties properties;
    private DealDuplicateReviewProofService service;

    @BeforeEach
    void setUp() {
        properties = new DuplicatePreflightProperties();
        service = new DealDuplicateReviewProofService(
            mapper,
            workspaceService,
            properties);
        org.mockito.Mockito.lenient()
            .when(workspaceService.getCurrentWorkspaceId()).thenReturn(5);
        org.mockito.Mockito.lenient()
            .when(workspaceService.getCurrentUserId()).thenReturn(9);
    }

    @Test
    void issuePersistsOnlyHashedTokenMaterialWithTheExactBinding() {
        ArgumentCaptor<byte[]> tokenHash = ArgumentCaptor.forClass(byte[].class);
        when(mapper.insert(any(), eq(5), eq(9), any(), any(), eq(1_800L)))
            .thenReturn(1);

        String token = service.issue("a".repeat(64), "b".repeat(64));

        verify(mapper).deleteExpired(5, 100);
        verify(mapper).insert(
            tokenHash.capture(),
            eq(5),
            eq(9),
            aryEq(HexFormat.of().parseHex("a".repeat(64))),
            aryEq(HexFormat.of().parseHex("b".repeat(64))),
            eq(1_800L));
        assertEquals(64, token.length());
        assertEquals(32, tokenHash.getValue().length);
        assertNotEquals(token, HexFormat.of().formatHex(tokenHash.getValue()));
    }

    @Test
    void consumeBindsTheTokenToWorkspaceActorWorkflowResultAndExpiry() {
        when(mapper.lockConsumable(any(), eq(5), eq(9), any(), any()))
            .thenReturn(1);
        when(mapper.deleteClaimed(any(), eq(5))).thenReturn(1);

        assertTrue(service.consume("c".repeat(64), "a".repeat(64), "b".repeat(64)));

        verify(mapper).lockConsumable(
            any(),
            eq(5),
            eq(9),
            aryEq(HexFormat.of().parseHex("a".repeat(64))),
            aryEq(HexFormat.of().parseHex("b".repeat(64))));
        verify(mapper).deleteClaimed(any(), eq(5));
    }

    @Test
    void malformedTokenFailsBeforePersistenceAccess() {
        assertFalse(service.consume("not-a-token", "a".repeat(64), "b".repeat(64)));
        assertFalse(service.consume("C".repeat(64), "a".repeat(64), "b".repeat(64)));

        verify(mapper, never())
            .lockConsumable(any(), anyInt(), anyInt(), any(), any());
        verify(mapper, never()).deleteClaimed(any(), anyInt());
    }
}
