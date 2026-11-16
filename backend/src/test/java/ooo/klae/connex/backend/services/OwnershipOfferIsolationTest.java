package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import java.sql.Connection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;
import ooo.klae.connex.backend.beans.OwnershipOffer;
import ooo.klae.connex.backend.mappers.OwnershipOfferMapper;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.notifications.NotificationDelivery;

/** An annotation cannot upgrade an enclosing transaction: refusal precedes every read. */
@ExtendWith(MockitoExtension.class)
class OwnershipOfferIsolationTest {
    @Mock private OwnershipOfferMapper mapper;
    @Mock private OrgMemberMapper orgMemberMapper;
    @Mock private WorkspaceMapper workspaceMapper;
    @Mock private OrganizationMapper organizationMapper;
    @Mock private UserMapper userMapper;
    @Mock private WorkspaceService workspaceService;
    @Mock private OrgMemberService orgMemberService;
    @Mock private OwnershipOfferWriter writer;
    @Mock private OwnershipOfferEligibility eligibility;
    @Mock private OwnershipMembershipWriter membershipWriter;
    @Mock private SessionSecurityService sessionSecurityService;
    @Mock private PrivilegedAccountService privilegedAccountService;
    @Mock private AuditService auditService;
    @Mock private NotificationDelivery delivery;
    @InjectMocks private OwnershipOfferTransaction transitions;
    @InjectMocks private OwnershipOfferDeliveryTransaction notices;

    @BeforeEach void enclosingRepeatableRead() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(Connection.TRANSACTION_REPEATABLE_READ);
    }
    @AfterEach void clear() { TransactionSynchronizationManager.clear(); }
    @Test void afterCommitLifecycleRoutingSuspendsTheCompletedTransactionsResources() throws Exception {
        var method = OwnershipOfferLifecycleListener.class.getMethod("fenced", OwnershipOfferOrganizationFenced.class);
        org.junit.jupiter.api.Assertions.assertEquals(org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED,
            method.getAnnotation(Transactional.class).propagation());
    }
    @Test void everyTransitionRejectsRepeatableReadBeforeAnyDependency() {
        assertThrows(IllegalStateException.class, () -> transitions.accept(1L, 2, false));
        assertThrows(IllegalStateException.class, () -> transitions.decline(1L, 2));
        assertThrows(IllegalStateException.class, () -> transitions.revoke(1L, 2, "WORKSPACE", 3));
        assertThrows(IllegalStateException.class, () -> transitions.reconcile(1L));
        assertThrows(IllegalStateException.class, () -> transitions.lockNotice(1L, 2));
        assertThrows(IllegalStateException.class, () -> transitions.mutateNotice(1L, 2, () -> true));
        assertThrows(IllegalStateException.class, () -> notices.deliver(new OwnershipOffer()));
        verifyNoInteractions(mapper, orgMemberMapper, workspaceMapper, organizationMapper, userMapper,
                workspaceService, orgMemberService, writer, eligibility, membershipWriter,
                sessionSecurityService, privilegedAccountService, auditService, delivery);
    }
}
