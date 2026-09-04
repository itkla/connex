package ooo.klae.connex.backend.controllers;

import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.config.OneTimeLinkFlowCookie;
import ooo.klae.connex.backend.dto.DeliveryUnsubscribeDto;
import ooo.klae.connex.backend.dto.OneTimeLinkExchangeRequest;
import ooo.klae.connex.backend.services.DeliveryUnsubscribeService;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService.IssuedGrant;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService.Purpose;

/**
 * Public unsubscribe endpoints. The emailed fragment bearer is exchanged once for a purpose-bound
 * browser grant; preview and confirmation read only that grant cookie, and the workspace and
 * recipient are resolved from the delivery row the exchanged digest identifies, never from the
 * request. These routes are unauthenticated but CSRF-protected because the cookie is the authority.
 */
@RestController
@RequestMapping("/api/delivery/unsubscribe")
@RequiredArgsConstructor
public class DeliveryUnsubscribeController {
    private final DeliveryUnsubscribeService deliveryUnsubscribeService;
    private final OneTimeLinkFlowService oneTimeLinkFlowService;
    private final OneTimeLinkFlowCookie oneTimeLinkFlowCookie;

    @PostMapping("/exchange")
    public void exchange(
            @Valid @RequestBody OneTimeLinkExchangeRequest dto,
            HttpServletRequest request,
            HttpServletResponse response) {
        String tokenHash = deliveryUnsubscribeService.exchange(dto.getToken());
        IssuedGrant grant = oneTimeLinkFlowService.issue(
            request, Purpose.DELIVERY_UNSUBSCRIBE, tokenHash);
        oneTimeLinkFlowCookie.set(
            response, Purpose.DELIVERY_UNSUBSCRIBE, grant.value(), grant.lifetime());
        response.setStatus(HttpServletResponse.SC_SEE_OTHER);
        response.setHeader("Location", "/unsubscribe");
    }

    @GetMapping
    public DeliveryUnsubscribeDto preview(
            @CookieValue(name = OneTimeLinkFlowCookie.DELIVERY_UNSUBSCRIBE, required = false)
            String grant,
            HttpServletRequest request) {
        return deliveryUnsubscribeService.preview(
            oneTimeLinkFlowService.require(request, Purpose.DELIVERY_UNSUBSCRIBE, grant));
    }

    @PostMapping
    public DeliveryUnsubscribeDto unsubscribe(
            @CookieValue(name = OneTimeLinkFlowCookie.DELIVERY_UNSUBSCRIBE, required = false)
            String grant,
            HttpServletRequest request) {
        return deliveryUnsubscribeService.unsubscribe(
            oneTimeLinkFlowService.require(request, Purpose.DELIVERY_UNSUBSCRIBE, grant));
    }
}
