package ooo.klae.connex.backend.services;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;

/**
 * Bounded global and principal-workspace admission for duplicate probing.
 */
@Component
@RequiredArgsConstructor
public class DuplicatePreflightRateLimiter {

    private final DuplicatePreflightProperties properties;
    private final WorkspaceService workspaceService;
    private final Clock clock;
    private final Map<RateKey, Window> windows = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<WorkflowKey, PreviewCredit> previewCredits =
        new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, ReviewProof> reviewProofs =
        new LinkedHashMap<>(16, 0.75f, true);
    private final Map<WorkflowKey, CommitAdmission> activeCommitAdmissions =
        new LinkedHashMap<>(16, 0.75f, true);
    private final SecureRandom secureRandom = new SecureRandom();
    private Window globalWindow;

    /**
     * Consumes positive work units for an interactive preflight.
     *
     * @param workUnits normalized lookup work represented by the request
     */
    public synchronized void requireAllowed(int workUnits) {
        Principal principal = principal();
        charge(principal, workUnits, currentMinute());
    }

    /**
     * Issues or reuses an unclaimed interactive review token for one unchanged result.
     *
     * @param workflowFingerprint SHA-256 fingerprint of the exact proposed values
     * @param resultFingerprint SHA-256 fingerprint of the rendered candidate result
     * @return opaque expiring review token
     */
    public synchronized String issueInteractiveReview(
            String workflowFingerprint,
            String resultFingerprint) {
        Principal principal = principal();
        long minute = currentMinute();
        WorkflowKey key = workflowKey(principal, workflowFingerprint);
        String context = requireFingerprint(workflowFingerprint, "review context");
        String result = requireFingerprint(resultFingerprint, "result fingerprint");
        trimPreviewCredits(minute);
        String reusable = reviewProofs.entrySet().stream()
            .filter(entry -> entry.getValue().workflowKey().equals(key))
            .filter(entry -> entry.getValue().reviewContext().equals(context))
            .filter(entry -> entry.getValue().resultFingerprint()
                .filter(result::equals).isPresent())
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(null);
        if (reusable != null) {
            reviewProofs.get(reusable);
            return reusable;
        }
        return newReviewProof(key, context, Optional.of(result));
    }

    /**
     * Atomically consumes an interactive review token bound to the current principal and result.
     *
     * @param reviewProof opaque token returned by the interactive preflight
     * @param workflowFingerprint SHA-256 fingerprint of the exact proposed values
     * @param resultFingerprint SHA-256 fingerprint of the current candidate result
     * @return whether the token authorized this exact result
     */
    public synchronized boolean consumeInteractiveReview(
            String reviewProof,
            String workflowFingerprint,
            String resultFingerprint) {
        CommitAdmission admission;
        try {
            admission = claimCommitAllowed(reviewProof, workflowFingerprint);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        try {
            String reviewedResult = requireCommitAllowed(
                workflowFingerprint,
                admission);
            return Objects.equals(
                reviewedResult,
                requireFingerprint(resultFingerprint, "result fingerprint"));
        } finally {
            releaseCommitAdmission(admission);
        }
    }

    /**
     * Admits a preview and leaves one credit for the unchanged commit.
     *
     * @param workUnits normalized lookup work represented by the request
     * @param workflowFingerprint SHA-256 fingerprint of the normalized ordered dataset
     * @param reviewContext SHA-256 fingerprint of the complete raw import request
     */
    public synchronized String requirePreviewAllowed(
            int workUnits,
            String workflowFingerprint,
            String reviewContext) {
        Principal principal = principal();
        long minute = currentMinute();
        WorkflowKey key = workflowKey(principal, workflowFingerprint);
        String context = requireFingerprint(reviewContext, "review context");
        trimPreviewCredits(minute);
        if (activeCommitAdmissions.containsKey(key)) {
            throw new ConflictException(
                "This import review is currently being committed; retry the preview shortly");
        }
        PreviewCredit existing = previewCredits.get(key);
        if (existing != null && existing.minute() == minute && existing.refreshAvailable()) {
            previewCredits.put(key, new PreviewCredit(minute, false));
            trimPreviewCredits(minute);
            return newReviewProof(key, context, Optional.empty());
        }
        charge(principal, workUnits, minute);
        boolean firstPreviewThisMinute = existing == null || existing.minute() != minute;
        previewCredits.put(key, new PreviewCredit(minute, firstPreviewThisMinute));
        trimPreviewCredits(minute);
        return newReviewProof(key, context, Optional.empty());
    }

    /**
     * Binds a successful preview result to the proof reserved for that exact preview.
     *
     * @param workflowFingerprint SHA-256 fingerprint of the normalized ordered dataset
     * @param resultFingerprint SHA-256 fingerprint of the ordered duplicate results
     */
    public synchronized void recordPreviewResult(
            String workflowFingerprint,
            String reviewProof,
            String resultFingerprint) {
        Principal principal = principal();
        long minute = currentMinute();
        WorkflowKey key = workflowKey(principal, workflowFingerprint);
        String proof = requireFingerprint(reviewProof, "review proof");
        ReviewProof existing = reviewProofs.get(proof);
        if (existing == null
                || existing.expiresAtEpochSecond() <= clock.instant().getEpochSecond()
                || !existing.workflowKey().equals(key)) {
            return;
        }
        reviewProofs.put(
            proof,
            new ReviewProof(
                existing.expiresAtEpochSecond(),
                key,
                existing.reviewContext(),
                Optional.of(requireFingerprint(resultFingerprint, "result fingerprint"))));
        trimPreviewCredits(minute);
    }

    /**
     * Atomically claims the exact one-use proof before import serialization begins.
     *
     * @param reviewProof opaque proof returned by the preview
     * @param reviewContext SHA-256 fingerprint of the complete raw import request
     * @return opaque admission, or {@code null} when the proof is absent, stale, or unbound
     */
    public synchronized CommitAdmission claimCommitAllowed(
            String reviewProof,
            String reviewContext) {
        Principal principal = principal();
        if (reviewProof == null) {
            return null;
        }
        String proof = requireFingerprint(reviewProof, "review proof");
        String context = requireFingerprint(reviewContext, "review context");
        ReviewProof reviewed = reviewProofs.get(proof);
        if (reviewed == null) {
            return null;
        }
        long now = clock.instant().getEpochSecond();
        if (reviewed.expiresAtEpochSecond() <= now) {
            reviewProofs.remove(proof);
            return null;
        }
        WorkflowKey key = reviewed.workflowKey();
        if (key.workspaceId() != principal.workspaceId()
                || key.userId() != principal.userId()
                || !reviewed.reviewContext().equals(context)
                || reviewed.resultFingerprint().isEmpty()) {
            return null;
        }
        trimPreviewCredits(currentMinute());
        if (activeCommitAdmissions.containsKey(key)
                || activeCommitAdmissions.size() >= properties.getMaxWorkflowCredits()) {
            return null;
        }
        reviewProofs.entrySet().removeIf(
            entry -> entry.getValue().workflowKey().equals(key));
        CommitAdmission admission = new CommitAdmission(
            reviewed.expiresAtEpochSecond(),
            key,
            reviewed.resultFingerprint().orElseThrow());
        activeCommitAdmissions.put(key, admission);
        return admission;
    }

    /**
     * Releases the workflow claim after its import transaction completes.
     *
     * @param admission one-use admission previously returned by {@link #claimCommitAllowed}
     */
    public synchronized void releaseCommitAdmission(CommitAdmission admission) {
        if (admission != null) {
            activeCommitAdmissions.remove(admission.workflowKey, admission);
        }
    }

    /**
     * Releases a proof whose preview failed before its result could be returned.
     *
     * @param workflowFingerprint SHA-256 fingerprint of the normalized ordered dataset
     * @param reviewProof reserved proof for the failed preview
     */
    public synchronized void cancelPreview(
            String workflowFingerprint,
            String reviewProof) {
        Principal principal = principal();
        WorkflowKey key = workflowKey(principal, workflowFingerprint);
        String proof = requireFingerprint(reviewProof, "review proof");
        ReviewProof reviewed = reviewProofs.get(proof);
        if (reviewed != null && reviewed.workflowKey().equals(key)) {
            reviewProofs.remove(proof);
        }
    }

    /**
     * Releases a proof that could not be returned after later import-preview validation failed.
     *
     * @param reviewProof reserved proof for the failed preview
     */
    public synchronized void cancelPreview(String reviewProof) {
        Principal principal = principal();
        String proof = requireFingerprint(reviewProof, "review proof");
        ReviewProof reviewed = reviewProofs.get(proof);
        if (reviewed != null
                && reviewed.workflowKey().workspaceId() == principal.workspaceId()
                && reviewed.workflowKey().userId() == principal.userId()) {
            reviewProofs.remove(proof);
        }
    }

    /**
     * Validates a claimed proof against the complete normalized import workflow.
     *
     * @param workflowFingerprint SHA-256 fingerprint of the normalized ordered dataset
     * @param admission one-use admission claimed before import serialization
     * @return reviewed result fingerprint, or {@code null} when the workflow changed
     */
    public synchronized String requireCommitAllowed(
            String workflowFingerprint,
            CommitAdmission admission) {
        Principal principal = principal();
        WorkflowKey key = workflowKey(principal, workflowFingerprint);
        if (admission == null || admission.consumed) {
            return null;
        }
        admission.consumed = true;
        return admission.expiresAtEpochSecond > clock.instant().getEpochSecond()
                && admission.workflowKey.equals(key)
            ? admission.resultFingerprint
            : null;
    }

    private void charge(Principal principal, int workUnits, long minute) {
        if (workUnits < 1) {
            throw new IllegalArgumentException("Duplicate-preflight work units must be positive");
        }
        Window global = current(globalWindow, minute);
        RateKey key = new RateKey(principal.workspaceId(), principal.userId());
        Window principalWindow = current(windows.get(key), minute);
        if (workUnits > properties.getMaxGlobalRequestsPerMinute() - global.requests()
                || workUnits > properties.getMaxRequestsPerMinute() - principalWindow.requests()) {
            throw new TooManyRequestsException(
                "Duplicate-preflight request limit reached; retry shortly");
        }
        globalWindow = new Window(minute, global.requests() + workUnits);
        windows.put(key, new Window(minute, principalWindow.requests() + workUnits));
        trimWindows();
    }

    private Principal principal() {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = workspaceService.getCurrentUserId();
        if (workspaceId <= 0 || userId <= 0) {
            throw new IllegalStateException("Duplicate-preflight principal is unavailable");
        }
        return new Principal(workspaceId, userId);
    }

    private long currentMinute() {
        return clock.instant().getEpochSecond() / 60;
    }

    private static WorkflowKey workflowKey(
            Principal principal,
            String workflowFingerprint) {
        String fingerprint = Objects.requireNonNull(
            workflowFingerprint, "workflow fingerprint");
        requireFingerprint(fingerprint, "workflow fingerprint");
        return new WorkflowKey(principal.workspaceId(), principal.userId(), fingerprint);
    }

    private static String requireFingerprint(String candidate, String label) {
        String fingerprint = Objects.requireNonNull(candidate, label);
        if (fingerprint.length() != 64
                || fingerprint.chars().anyMatch(character ->
                    character < '0'
                        || character > '9' && character < 'a'
                        || character > 'f')) {
            throw new IllegalArgumentException(
                "Duplicate-preflight " + label + " must be 64 lowercase hexadecimal characters");
        }
        return fingerprint;
    }

    private static Window current(Window candidate, long minute) {
        return candidate != null && candidate.minute() == minute
            ? candidate
            : new Window(minute, 0);
    }

    private void trimWindows() {
        Iterator<RateKey> keys = windows.keySet().iterator();
        while (windows.size() > properties.getMaxRateLimitKeys() && keys.hasNext()) {
            keys.next();
            keys.remove();
        }
    }

    private void trimPreviewCredits(long minute) {
        previewCredits.entrySet().removeIf(entry -> entry.getValue().minute() != minute);
        long now = clock.instant().getEpochSecond();
        reviewProofs.entrySet().removeIf(
            entry -> entry.getValue().expiresAtEpochSecond() <= now);
        Iterator<WorkflowKey> keys = previewCredits.keySet().iterator();
        while (previewCredits.size() > properties.getMaxWorkflowCredits() && keys.hasNext()) {
            keys.next();
            keys.remove();
        }
        Iterator<String> proofs = reviewProofs.keySet().iterator();
        while (reviewProofs.size() > properties.getMaxWorkflowCredits() && proofs.hasNext()) {
            proofs.next();
            proofs.remove();
        }
    }

    private String newReviewProof(
            WorkflowKey workflowKey,
            String reviewContext,
            Optional<String> resultFingerprint) {
        byte[] bytes = new byte[32];
        String proof;
        do {
            secureRandom.nextBytes(bytes);
            proof = HexFormat.of().formatHex(bytes);
        } while (reviewProofs.containsKey(proof));
        reviewProofs.put(
            proof,
            new ReviewProof(
                Math.addExact(
                    clock.instant().getEpochSecond(),
                    properties.getReviewProofTtl().toSeconds()),
                workflowKey,
                reviewContext,
                resultFingerprint));
        trimPreviewCredits(currentMinute());
        return proof;
    }

    private record Principal(int workspaceId, int userId) {
    }

    private record RateKey(int workspaceId, int userId) {
    }

    private record WorkflowKey(int workspaceId, int userId, String fingerprint) {
    }

    private record PreviewCredit(long minute, boolean refreshAvailable) {
    }

    private record ReviewProof(
            long expiresAtEpochSecond,
            WorkflowKey workflowKey,
            String reviewContext,
            Optional<String> resultFingerprint) {
    }

    static final class CommitAdmission {
        private final long expiresAtEpochSecond;
        private final WorkflowKey workflowKey;
        private final String resultFingerprint;
        private boolean consumed;

        private CommitAdmission(
                long expiresAtEpochSecond,
                WorkflowKey workflowKey,
                String resultFingerprint) {
            this.expiresAtEpochSecond = expiresAtEpochSecond;
            this.workflowKey = workflowKey;
            this.resultFingerprint = resultFingerprint;
        }
    }

    private record Window(long minute, int requests) {
    }
}
