package ooo.klae.connex.backend.controllers;

import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.connectedaccounts.nativeflow.NativeCompleteRequest;
import ooo.klae.connex.backend.connectedaccounts.nativeflow.NativeCompleteResponse;
import ooo.klae.connex.backend.connectedaccounts.nativeflow.NativeConnectService;
import ooo.klae.connex.backend.connectedaccounts.nativeflow.NativePairingResponse;
import ooo.klae.connex.backend.connectedaccounts.nativeflow.NativePairingStatusResponse;
import ooo.klae.connex.backend.connectedaccounts.nativeflow.NativePrepareRequest;
import ooo.klae.connex.backend.connectedaccounts.nativeflow.NativePrepareResponse;

/** HTTP boundary for Connex-managed loopback/PKCE connected-account authorization. */
@RestController
@RequestMapping("/api/account/connections/native")
@RequiredArgsConstructor
public class NativeConnectController {
    private final NativeConnectService nativeConnectService;

    @PostMapping("/{provider}/pairing")
    public NativePairingResponse createPairing(@PathVariable String provider) {
        return nativeConnectService.createPairing(provider);
    }

    @GetMapping("/{provider}/pairing")
    public NativePairingStatusResponse pairingStatus(@PathVariable String provider) {
        return nativeConnectService.pairingStatus(provider);
    }

    @DeleteMapping("/{provider}/pairing")
    public ResponseEntity<Void> cancelPairing(@PathVariable String provider) {
        nativeConnectService.cancelPairing(provider);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/helper", produces = MediaType.TEXT_PLAIN_VALUE)
    public String helper() {
        return nativeConnectService.helperScript();
    }

    @PostMapping("/prepare")
    public NativePrepareResponse prepare(@Valid @RequestBody NativePrepareRequest request) {
        return nativeConnectService.prepare(request);
    }

    @PostMapping("/complete")
    public NativeCompleteResponse complete(@Valid @RequestBody NativeCompleteRequest request) {
        return nativeConnectService.complete(request);
    }
}
