package ooo.klae.connex.backend.controllers;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.ShareDto;
import ooo.klae.connex.backend.dto.ShareRequest;
import ooo.klae.connex.backend.services.ShareService;

/**
 * Cross-workspace sharing of a record. {@code type} is company / person /
 * pipeline. Disabled instance-wide by {@code connex.sharing.enabled=false}.
 */
@RestController
@RequestMapping("/api/shares/{type}/{id}")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "connex.sharing.enabled", havingValue = "true", matchIfMissing = true)
public class ShareController {
    private final ShareService shareService;

    @GetMapping
    public List<ShareDto> list(@PathVariable String type, @PathVariable int id) {
        return shareService.listShares(type, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void share(@PathVariable String type, @PathVariable int id, @Valid @RequestBody ShareRequest request) {
        shareService.share(type, id, request.getWorkspaceId(), request.isCanEdit());
    }

    @DeleteMapping("/{workspaceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unshare(@PathVariable String type, @PathVariable int id, @PathVariable int workspaceId) {
        shareService.unshare(type, id, workspaceId);
    }
}
