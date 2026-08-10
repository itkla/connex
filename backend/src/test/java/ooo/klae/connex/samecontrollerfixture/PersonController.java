package ooo.klae.connex.samecontrollerfixture;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import ooo.klae.connex.backend.tenant.TenantJournalAttributable;

@Controller
@RequestMapping("/api/persons")
@TenantJournalAttributable
public final class PersonController {
    @GetMapping
    public void list() {
    }
}
