package ooo.klae.connex.backend.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.services.AttachmentQuarantineService;

/** Routes administrative attachment quarantine decisions to the authorized lifecycle service. */
@RestController
@RequestMapping("/api/attachments")
@RequiredArgsConstructor
public class AttachmentQuarantineController {
    private final AttachmentQuarantineService quarantineService;

    /** Retains an attachment while immediately withdrawing permission to read its bytes. */
    @PostMapping("/{id}/quarantine")
    public ResponseEntity<Void> quarantine(@PathVariable int id) {
        quarantineService.quarantine(id);
        return ResponseEntity.noContent().build();
    }

    /** Withdraws the previous verdict and requests a new scan. */
    @PostMapping("/{id}/rescan")
    public ResponseEntity<Void> rescan(@PathVariable int id) {
        quarantineService.rescan(id);
        return ResponseEntity.noContent().build();
    }

    /** Requests release through a fresh scan; this operation never grants a clean verdict. */
    @PostMapping("/{id}/release")
    public ResponseEntity<Void> release(@PathVariable int id) {
        quarantineService.release(id);
        return ResponseEntity.noContent().build();
    }

    /** Removes quarantine metadata and schedules unreferenced bytes for durable deletion. */
    @DeleteMapping("/{id}/quarantine")
    public ResponseEntity<Void> delete(@PathVariable int id) {
        quarantineService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
