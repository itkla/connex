package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.delivery.ChannelAddressNormalizer;
import ooo.klae.connex.backend.delivery.DeliveryChannel;
import ooo.klae.connex.backend.mappers.CampaignMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;

/**
 * Session-free classification of person eligibility for a marketing channel. Extracted from
 * {@code CampaignService} so both the audience estimate/snapshot flow and the dispatch choke point
 * apply exactly the same precedence: processing restrictions take precedence over workspace-owned
 * suppressions, which take precedence over consent. Callers pass the workspace explicitly; this
 * service never reads the request tenant context and is not permission-gated — its callers are.
 *
 * <p>This is also the one place the {@link ConsentPolicy} is read, so the snapshot classification,
 * the dispatch re-check, and the connector export cannot disagree about what consent means.
 */
@Service
@RequiredArgsConstructor
public class AudienceEligibilityService {

    /**
     * The consent policy every caller inherits. Fixed for this slice, mirroring the dispatch policy
     * defaults: Connex does not require gathered opt-in, so only an explicit revocation blocks.
     * Flipping this single constant to {@link ConsentPolicy#OPT_IN} restores default-deny everywhere.
     */
    public static final ConsentPolicy CONSENT_POLICY = ConsentPolicy.OPT_OUT;

    private static final int SQL_BATCH_SIZE = 500;

    private final CampaignMapper campaignMapper;
    private final PersonMapper personMapper;

    /**
     * The subset of the given people whose processing is restricted.
     * @param workspaceId the workspace
     * @param personIds candidate person ids
     * @return the restricted person ids
     */
    public Set<Integer> restrictedIds(int workspaceId, List<Integer> personIds) {
        Set<Integer> result = new HashSet<>();
        forEachBatch(personIds, batch -> result.addAll(campaignMapper.restrictedPersonIds(workspaceId, batch)));
        return result;
    }

    /**
     * The subset of the given people suppressed on the channel, matched on the address that channel
     * would actually contact them at. The candidates' addresses are resolved and canonicalized with
     * {@link ChannelAddressNormalizer} and then checked against the same
     * {@link #suppressedAddresses(int, String, List)} query the dispatch re-check uses, so a snapshot
     * and a dispatch of the same audience cannot disagree: an SMS audience is matched on phone
     * numbers rather than on email addresses, and a suppression stored in a different but equivalent
     * format still matches. A person with no address the channel can reach is not address-matched
     * here; materialization skips them as {@code no_address}.
     *
     * <p>Suppression is additionally sticky to the person: a candidate is suppressed when a
     * {@code suppression_entry} on this channel carries their {@code person_id}, regardless of the
     * address string it stored, via {@link #suppressedPersonRefIds(int, List, String)}. This keeps an
     * unsubscribe honored even after the person's contact field is later reformatted. The address path
     * is retained so manual, address-only suppressions with no person link still block.
     * @param workspaceId the workspace
     * @param personIds candidate person ids
     * @param channel the delivery channel token
     * @return the suppressed person ids
     */
    public Set<Integer> suppressedIds(int workspaceId, List<Integer> personIds, String channel) {
        if (personIds.isEmpty()) {
            return Set.of();
        }
        DeliveryChannel deliveryChannel = DeliveryChannel.fromToken(channel);
        Map<String, List<Integer>> idsByAddress = new LinkedHashMap<>();
        forEachBatch(personIds, batch -> {
            for (Person person : personMapper.getByIds(workspaceId, batch)) {
                String address = ChannelAddressNormalizer.addressFor(deliveryChannel, person);
                if (address != null) {
                    idsByAddress.computeIfAbsent(address, key -> new ArrayList<>()).add(person.getId());
                }
            }
        });
        Set<Integer> result = new HashSet<>(suppressedPersonRefIds(workspaceId, personIds, channel));
        if (!idsByAddress.isEmpty()) {
            for (String address : suppressedAddresses(workspaceId, channel, new ArrayList<>(idsByAddress.keySet()))) {
                result.addAll(idsByAddress.getOrDefault(address, List.of()));
            }
        }
        return result;
    }

    /**
     * The subset of the given people who carry a person-linked suppression on the channel, matched by
     * {@code person_id} rather than by address. Channel-keyed, so a suppression on one channel never
     * blocks another. This is the person-sticky arm of {@link #suppressedIds(int, List, String)} and
     * the dispatch re-check's per-delivery equivalent: it keeps an unsubscribe honored even after the
     * person's contact field is reformatted into a differently-canonicalized address that the
     * address path alone would no longer match.
     * @param workspaceId the workspace
     * @param personIds candidate person ids
     * @param channel the delivery channel token
     * @return the suppressed person ids
     */
    public Set<Integer> suppressedPersonRefIds(int workspaceId, List<Integer> personIds, String channel) {
        Set<Integer> result = new HashSet<>();
        forEachBatch(personIds, batch -> result.addAll(
                campaignMapper.suppressedPersonRefIds(workspaceId, batch, channel)));
        return result;
    }

    /**
     * The subset of the given people who granted consent for the channel and purpose.
     * @param workspaceId the workspace
     * @param personIds candidate person ids
     * @param channel the delivery channel
     * @param purpose the consent purpose
     * @return the person ids with granted consent
     */
    public Set<Integer> grantedConsentIds(int workspaceId, List<Integer> personIds, String channel, String purpose) {
        Set<Integer> result = new HashSet<>();
        forEachBatch(personIds, batch -> result.addAll(
                campaignMapper.grantedConsentPersonIds(workspaceId, batch, channel, purpose)));
        return result;
    }

    /**
     * The subset of the given people who explicitly revoked consent for the channel and purpose.
     * @param workspaceId the workspace
     * @param personIds candidate person ids
     * @param channel the delivery channel
     * @param purpose the consent purpose
     * @return the person ids with revoked consent
     */
    public Set<Integer> revokedConsentIds(int workspaceId, List<Integer> personIds, String channel, String purpose) {
        Set<Integer> result = new HashSet<>();
        forEachBatch(personIds, batch -> result.addAll(
                campaignMapper.revokedConsentPersonIds(workspaceId, batch, channel, purpose)));
        return result;
    }

    /**
     * The subset of the given people that {@link #CONSENT_POLICY} blocks on consent grounds: under
     * {@link ConsentPolicy#OPT_IN} everyone without a granted record, under {@link ConsentPolicy#OPT_OUT}
     * only those with an explicit revoked record.
     * @param workspaceId the workspace
     * @param personIds candidate person ids
     * @param channel the delivery channel
     * @param purpose the consent purpose
     * @return the person ids blocked by consent under the active policy
     */
    public Set<Integer> consentBlockedIds(int workspaceId, List<Integer> personIds, String channel, String purpose) {
        return switch (CONSENT_POLICY) {
            case OPT_IN -> {
                Set<Integer> blocked = new LinkedHashSet<>(personIds);
                blocked.removeAll(grantedConsentIds(workspaceId, personIds, channel, purpose));
                yield blocked;
            }
            case OPT_OUT -> revokedConsentIds(workspaceId, personIds, channel, purpose);
        };
    }

    /**
     * Whether {@link #CONSENT_POLICY} blocks contacting one person, tolerating a delivery whose person
     * link has been cleared. Such a delivery can be matched to no consent record at all: under
     * {@link ConsentPolicy#OPT_IN} no grant can be proven so it is blocked, under
     * {@link ConsentPolicy#OPT_OUT} no revocation exists so it is allowed — the address-based
     * suppression check remains the backstop for both.
     * @param workspaceId the workspace
     * @param personId the person id, or null when the delivery has no person link
     * @param channel the delivery channel
     * @param purpose the consent purpose
     * @return true when consent blocks contacting the person
     */
    public boolean consentBlocks(int workspaceId, Integer personId, String channel, String purpose) {
        if (personId == null) {
            return CONSENT_POLICY == ConsentPolicy.OPT_IN;
        }
        return consentBlockedIds(workspaceId, List.of(personId), channel, purpose).contains(personId);
    }

    /**
     * The subset of the given addresses suppressed on the channel. Address-based so an unsubscribe or
     * bounce is honored at dispatch even when the person link has changed or is absent.
     * @param workspaceId the workspace
     * @param channel the delivery channel
     * @param addresses normalized candidate addresses
     * @return the suppressed addresses
     */
    public Set<String> suppressedAddresses(int workspaceId, String channel, List<String> addresses) {
        Set<String> result = new HashSet<>();
        forEachBatch(addresses, batch -> result.addAll(
                campaignMapper.suppressedAddresses(workspaceId, channel, batch)));
        return result;
    }

    /**
     * Classifies the candidates into included and per-reason excluded sets, applying the precedence
     * restricted &rarr; suppressed &rarr; consent. Which people the consent step blocks, and the reason
     * it reports, follow {@link #CONSENT_POLICY}.
     * @param workspaceId the workspace
     * @param candidateIds the ordered candidate person ids
     * @param channel the delivery channel
     * @param purpose the consent purpose
     * @return the classification
     */
    public AudienceClassification classify(int workspaceId, List<Integer> candidateIds, String channel, String purpose) {
        LinkedHashSet<Integer> remaining = new LinkedHashSet<>(candidateIds);
        Set<Integer> restricted = restrictedIds(workspaceId, candidateIds);
        remaining.removeAll(restricted);
        Set<Integer> suppressed = suppressedIds(workspaceId, new ArrayList<>(remaining), channel);
        remaining.removeAll(suppressed);
        Set<Integer> consentBlocked =
                consentBlockedIds(workspaceId, new ArrayList<>(remaining), channel, purpose);

        List<Integer> includedIds = new ArrayList<>(candidateIds.size());
        for (int id : candidateIds) {
            if (!restricted.contains(id) && !suppressed.contains(id) && !consentBlocked.contains(id)) {
                includedIds.add(id);
            }
        }
        return new AudienceClassification(restricted, suppressed, consentBlocked, List.copyOf(includedIds));
    }

    private static <T> void forEachBatch(List<T> values, Consumer<List<T>> consumer) {
        for (int offset = 0; offset < values.size(); offset += SQL_BATCH_SIZE) {
            consumer.accept(values.subList(offset, Math.min(values.size(), offset + SQL_BATCH_SIZE)));
        }
    }

    /**
     * The result of classifying an audience against restrictions, suppressions, and consent.
     * @param restricted person ids excluded because their processing is restricted
     * @param suppressed person ids excluded because they are suppressed on the channel
     * @param consentBlocked person ids excluded by consent under {@link #CONSENT_POLICY}
     * @param includedIds person ids that passed every check, in input order
     */
    public record AudienceClassification(
            Set<Integer> restricted,
            Set<Integer> suppressed,
            Set<Integer> consentBlocked,
            List<Integer> includedIds) {

        /**
         * The exclusion reason for a person id, or null when the person is included. The consent reason
         * is the one {@link #CONSENT_POLICY} reports, so it stays in step with the classification.
         * @param personId the person id
         * @return the exclusion reason token, or null
         */
        public String reasonFor(int personId) {
            if (restricted.contains(personId)) {
                return "restricted";
            }
            if (suppressed.contains(personId)) {
                return "suppressed";
            }
            if (consentBlocked.contains(personId)) {
                return CONSENT_POLICY.exclusionReason();
            }
            return null;
        }
    }
}
