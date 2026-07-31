package ooo.klae.connex.backend.controllers;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.connectedaccounts.capture.ProviderCapturePolicyService;
import ooo.klae.connex.backend.connectedaccounts.capture.ProviderCaptureReviewService;
import ooo.klae.connex.backend.dto.ProviderCaptureApprovalRequest;
import ooo.klae.connex.backend.dto.ProviderCaptureOverviewDto;
import ooo.klae.connex.backend.dto.ProviderCaptureOverviewResponse;
import ooo.klae.connex.backend.dto.ProviderCaptureReviewPage;
import ooo.klae.connex.backend.dto.ProviderCaptureReviewRequest;
import ooo.klae.connex.backend.dto.ProviderCaptureUserPolicyRequest;
import ooo.klae.connex.backend.dto.ProviderCaptureWorkspacePolicyRequest;

/**
 * Current-user and workspace-admin capture policy, health, review, and purge endpoints.
 */
@Validated
@RestController
@RequestMapping("/api/account/connections")
@RequiredArgsConstructor
public class ProviderCaptureController {
    private final ProviderCapturePolicyService policyService;
    private final ProviderCaptureReviewService reviewService;

    @GetMapping("/capture")
    public ProviderCaptureOverviewResponse overview() {
        return policyService.getCurrentOverview();
    }

    @PutMapping("/{provider}/capture-policy")
    public ProviderCaptureOverviewDto updatePolicy(
            @PathVariable String provider,
            @Valid @RequestBody ProviderCaptureUserPolicyRequest request) {
        return policyService.updateUserPolicy(provider, request);
    }

    @PutMapping("/{provider}/workspace-policy")
    public ProviderCaptureOverviewDto updateWorkspacePolicy(
            @PathVariable String provider,
            @Valid @RequestBody ProviderCaptureWorkspacePolicyRequest request) {
        return policyService.updateWorkspacePolicy(provider, request);
    }

    @PostMapping("/{provider}/sync")
    public ProviderCaptureOverviewDto sync(@PathVariable String provider) {
        return policyService.queueCurrent(provider);
    }

    @GetMapping("/{provider}/reviews")
    public ProviderCaptureReviewPage reviews(
            @PathVariable String provider,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return reviewService.page(provider, page, size);
    }

    @PostMapping("/{provider}/reviews/{reviewId}")
    public ProviderCaptureOverviewDto decide(
            @PathVariable String provider,
            @PathVariable long reviewId,
            @Valid @RequestBody ProviderCaptureReviewRequest request) {
        return reviewService.decide(provider, reviewId, request);
    }

    @PostMapping("/{provider}/captured/{interactionId}/approve")
    public ProviderCaptureOverviewDto approve(
            @PathVariable String provider,
            @PathVariable long interactionId,
            @Valid @RequestBody ProviderCaptureApprovalRequest request) {
        return reviewService.approve(provider, interactionId, request.version());
    }

    @DeleteMapping("/{provider}/captured-data")
    public ProviderCaptureOverviewDto.PurgeState purge(
            @PathVariable String provider) {
        return reviewService.purgeCurrent(provider);
    }
}
