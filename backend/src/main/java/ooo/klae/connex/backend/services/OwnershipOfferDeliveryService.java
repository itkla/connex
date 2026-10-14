package ooo.klae.connex.backend.services;

import java.util.List;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.OwnershipOffer;
import ooo.klae.connex.backend.mappers.OwnershipOfferMapper;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Reconciles ordinary best-effort notices; the durable self-offer card is authoritative. */
@Service
@RequiredArgsConstructor
public class OwnershipOfferDeliveryService {
    private final OwnershipOfferMapper mapper;
    private final TenantWorkScope workScope;
    private final OwnershipOfferService offers;
    private final OwnershipOfferDeliveryTransaction transaction;

    /** Reads bounded candidates from control without placing a notification in a foreign catalog. */
    public List<OwnershipOffer> candidates() {
        return workScope.unroutedRead(() -> mapper.deliveryCandidates(100));
    }

    /** Routes before opening the worker transaction, whose offer lock serializes every closure. */
    public void deliver(OwnershipOffer offer) {
        offers.routedDelivery(offer, offer.getOffereeUserId(), () -> { transaction.deliver(offer); return true; });
    }
}
