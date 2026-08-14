package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.OneTimeLinkFlowMapper;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService.Purpose;
import ooo.klae.connex.backend.util.OneTimeTokenDigest;

/** Owns database claims that serialize final one-time-link operations across replicas. */
@Service
@RequiredArgsConstructor
public class OneTimeLinkFlowClaimService {

    private static final String INVALID_LINK = "This link is invalid or has expired";

    private final OneTimeLinkFlowMapper flowMapper;

    /** Atomically claims a valid tenant flow before its workspace transaction is known. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Claim claim(String grantHash, String exchangeOwnerHash, Purpose purpose) {
        return claimFlow(grantHash, exchangeOwnerHash, purpose);
    }

    /** Atomically claims a control-plane flow inside its domain transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Claim claimInCurrentTransaction(
            String grantHash, String exchangeOwnerHash, Purpose purpose) {
        return claimFlow(grantHash, exchangeOwnerHash, purpose);
    }

    /** Releases an exact claim after its tenant domain transaction fails. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(Claim claim) {
        flowMapper.release(claim.grantHash(), claim.claimHash());
    }

    /** Deletes an exact claim inside the transaction applying its domain mutation. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void completeInCurrentTransaction(Claim claim) {
        if (flowMapper.complete(claim.grantHash(), claim.claimHash()) != 1) {
            throw new IllegalStateException("Claimed one-time-link flow could not be completed");
        }
    }

    /** Durable claim identity and source digest for one final operation. */
    public record Claim(String grantHash, String claimHash, String sourceTokenHash) {
    }

    private Claim claimFlow(String grantHash, String exchangeOwnerHash, Purpose purpose) {
        String claimHash = OneTimeTokenDigest.sha256(OneTimeTokenDigest.generate());
        int claimed = flowMapper.claim(
            grantHash,
            exchangeOwnerHash,
            purpose.name(),
            claimHash);
        if (claimed != 1) {
            throw invalidLink();
        }
        String sourceTokenHash = flowMapper.findClaimedSourceTokenHash(grantHash, claimHash);
        if (sourceTokenHash == null) {
            throw new IllegalStateException("Claimed one-time-link flow could not be resolved");
        }
        return new Claim(grantHash, claimHash, sourceTokenHash);
    }

    private static BadRequestException invalidLink() {
        return new BadRequestException(INVALID_LINK);
    }
}
