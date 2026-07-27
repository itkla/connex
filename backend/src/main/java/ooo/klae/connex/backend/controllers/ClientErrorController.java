package ooo.klae.connex.backend.controllers;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.ClientErrorRequest;
import ooo.klae.connex.backend.services.ClientErrorService;

/**
 * Authenticated, CSRF-protected, workspace-scoped client error reporting endpoint.
 */
@RestController
@RequestMapping("/api/client-errors")
@RequiredArgsConstructor
public class ClientErrorController {
    private final ClientErrorService clientErrorService;

    /**
     * Accepts a validated client error report for asynchronous operator diagnosis.
     *
     * @param request the client error report
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void report(@Valid @RequestBody ClientErrorRequest request) {
        clientErrorService.report(request);
    }
}
