package ooo.klae.connex.backend.controllers;

import java.util.List;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.exceptions.DataSubjectDisclosureStreamException;
import ooo.klae.connex.backend.dto.DataSubjectRequestDto;
import ooo.klae.connex.backend.dto.DataSubjectRequestUpsertRequest;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.DataSubjectRequestService;

@RestController
@RequestMapping("/api/orgs/{orgId}/data-subject-requests")
@RequiredArgsConstructor
public class DataSubjectRequestController {
    private final DataSubjectRequestService dataSubjectRequestService;
    private final AuthService authService;

    @GetMapping
    public List<DataSubjectRequestDto> list(@PathVariable int orgId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return dataSubjectRequestService.list(
            orgId, authService.getCurrentUser().getId(), status, limit, offset);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DataSubjectRequestDto create(@PathVariable int orgId,
            @Valid @RequestBody DataSubjectRequestUpsertRequest request) {
        return dataSubjectRequestService.create(orgId, authService.getCurrentUser().getId(), request);
    }

    @GetMapping("/{requestId}")
    public DataSubjectRequestDto get(@PathVariable int orgId, @PathVariable long requestId) {
        return dataSubjectRequestService.get(orgId, requestId, authService.getCurrentUser().getId());
    }

    @PutMapping("/{requestId}")
    public DataSubjectRequestDto update(@PathVariable int orgId, @PathVariable long requestId,
            @Valid @RequestBody DataSubjectRequestUpsertRequest request) {
        return dataSubjectRequestService.update(
            orgId, requestId, authService.getCurrentUser().getId(), request);
    }

    /**
     * Writes disclosure synchronously after its strict audit commits. Explicit retries produce
     * another access event; an interrupted committed response remains incomplete JSON.
     */
    @PostMapping("/{requestId}/disclosure")
    public void disclosure(@PathVariable int orgId, @PathVariable long requestId,
            HttpServletResponse response) {
        try {
            dataSubjectRequestService.disclosure(orgId, requestId, authService.getCurrentUser().getId(),
                fingerprint -> {
                    response.setContentType("application/json");
                    response.setHeader("X-Disclosure-Note-Count", Long.toString(fingerprint.noteCount()));
                    response.setHeader("X-Disclosure-Notes-SHA256", fingerprint.notesSha256());
                    response.setHeader("X-Disclosure-Byte-Length", Long.toString(fingerprint.byteLength()));
                    response.setHeader("X-Disclosure-SHA256", fingerprint.sha256());
                    return response.getOutputStream();
                });
        } catch (RuntimeException failure) {
            if (response.isCommitted() && !(failure instanceof DataSubjectDisclosureStreamException)) {
                throw new DataSubjectDisclosureStreamException(failure);
            }
            throw failure;
        }
    }
}
