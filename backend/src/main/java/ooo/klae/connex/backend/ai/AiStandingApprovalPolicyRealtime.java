package ooo.klae.connex.backend.ai;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.mappers.AiStandingApprovalPolicyMapper;
import ooo.klae.connex.backend.notifications.RealtimeRoutingIdentityResolver;

/** Best-effort administrative invalidations carry no approval definitions or execution content. */
@Component
@RequiredArgsConstructor
public class AiStandingApprovalPolicyRealtime {
    private static final Logger LOG = LoggerFactory.getLogger(AiStandingApprovalPolicyRealtime.class);
    private final AiStandingApprovalControlAccess access;
    private final AiStandingApprovalPolicyMapper policies;
    private final RealtimeRoutingIdentityResolver identities;
    private final ObjectProvider<SimpMessagingTemplate> messaging;

    /** Defers outer transactions too; a rolled-back replacement never invalidates clients. */
    public void publishAfterCommit(int orgId) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            publish(orgId);
                        }
                    });
        } else {
            publish(orgId);
        }
    }

    private void publish(int orgId) {
        try {
            access.execute(() -> {
                SimpMessagingTemplate template = messaging.getIfAvailable();
                if (template != null) {
                    for (int recipient : policies.administratorIds(orgId)) {
                        template.convertAndSendToUser(identities.destinationFor(recipient),
                                "/queue/ai-standing-approval-policy", Map.of("kind", "policy_changed"));
                    }
                }
                return null;
            });
        } catch (RuntimeException exception) {
            LOG.warn("Standing approval policy invalidation could not be delivered");
        }
    }
}
