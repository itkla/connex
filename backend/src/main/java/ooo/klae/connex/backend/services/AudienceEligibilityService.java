package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.mappers.CampaignMapper;

/**
 * Session-free classification of person eligibility for a marketing channel. Extracted from
 * {@code CampaignService} so both the audience estimate/snapshot flow and the dispatch choke point
 * apply exactly the same precedence: processing restrictions take precedence over workspace-owned
 * suppressions, which take precedence over missing consent. Callers pass the workspace explicitly;
 * this service never reads the request tenant context and is not permission-gated — its callers are.
 */
@Service
@RequiredArgsConstructor
public class AudienceEligibilityService {

    private static final int SQL_BATCH_SIZE = 500;

    private final CampaignMapper campaignMapper;

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
     * The subset of the given people suppressed on the channel by their email address.
     * @param workspaceId the workspace
     * @param personIds candidate person ids
     * @param channel the delivery channel
     * @return the suppressed person ids
     */
    public Set<Integer> suppressedIds(int workspaceId, List<Integer> personIds, String channel) {
        Set<Integer> result = new HashSet<>();
        forEachBatch(personIds, batch -> result.addAll(
                campaignMapper.suppressedPersonIds(workspaceId, batch, channel)));
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
     * restricted &rarr; suppressed &rarr; consent_missing.
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
        Set<Integer> granted = grantedConsentIds(workspaceId, new ArrayList<>(remaining), channel, purpose);
        Set<Integer> consentMissing = new LinkedHashSet<>(remaining);
        consentMissing.removeAll(granted);

        List<Integer> includedIds = new ArrayList<>(candidateIds.size());
        for (int id : candidateIds) {
            if (!restricted.contains(id) && !suppressed.contains(id) && !consentMissing.contains(id)) {
                includedIds.add(id);
            }
        }
        return new AudienceClassification(restricted, suppressed, consentMissing, List.copyOf(includedIds));
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
     * @param consentMissing person ids excluded because they have not granted consent
     * @param includedIds person ids that passed every check, in input order
     */
    public record AudienceClassification(
            Set<Integer> restricted,
            Set<Integer> suppressed,
            Set<Integer> consentMissing,
            List<Integer> includedIds) {

        /**
         * The exclusion reason for a person id, or null when the person is included.
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
            if (consentMissing.contains(personId)) {
                return "consent_missing";
            }
            return null;
        }
    }
}
