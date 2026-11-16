package ooo.klae.connex.backend.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.OwnershipOffer;

/** Bounded expiry and notice reconciliation; no external I/O occurs under offer locks. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "connex.notifications", name = "scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class OwnershipOfferScheduler {
    private static final Logger log = LoggerFactory.getLogger(OwnershipOfferScheduler.class);
    private final OwnershipOfferService offers;
    private final OwnershipOfferDeliveryService delivery;

    /** Closes expired consent and attempts each pending notice in its own routed transaction. */
    @Scheduled(fixedDelayString = "${connex.ownership-offers.reconciliation-delay-ms:30000}",
        initialDelayString = "${connex.notifications.initial-delay-ms:300000}")
    public void reconcile() {
        offers.expire();
        for (OwnershipOffer offer : delivery.candidates()) {
            try {
                delivery.deliver(offer);
            } catch (RuntimeException failure) {
                log.warn("Ownership notice delivery failed offerId={} exceptionClass={}",
                    offer.getId(), failure.getClass().getSimpleName());
            }
        }
    }
}
