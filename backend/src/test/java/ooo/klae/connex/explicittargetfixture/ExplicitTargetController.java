package ooo.klae.connex.explicittargetfixture;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import ooo.klae.connex.backend.tenant.TenantJournalAttributable;

@Controller
@RequestMapping("/api/organizations")
@TenantJournalAttributable
public final class ExplicitTargetController {
    @GetMapping("/{id}")
    public void get(@PathVariable int id) {
    }
}
