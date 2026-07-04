package ooo.klae.connex.backend.services;

import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.OrgAllowedDomainMapper;

/**
 * The per-organization email-domain allowlist that constrains workspace invites (#316, Option B):
 * the org-level ceiling on who may be added to any workspace in the org. Reads/writes are org
 * admin/owner-gated; {@link #isJoinAllowed} is the reusable gate, returning true when the org has no
 * allowlist so existing organizations stay unrestricted. It is AND-ed with the per-workspace
 * {@link AllowedDomainService} allowlist at each invite/join enforcement point.
 */
@Service
@RequiredArgsConstructor
public class OrgAllowedDomainService {

    private final OrgAllowedDomainMapper orgAllowedDomainMapper;
    private final OrgMemberService orgMemberService;
    private final AuditService auditService;

    public List<String> listDomains(int orgId, int actorId) {
        orgMemberService.requireOrgAdmin(orgId, actorId);
        return orgAllowedDomainMapper.findByOrg(orgId);
    }

    public List<String> addDomain(int orgId, int actorId, String domainRaw) {
        orgMemberService.requireOrgAdmin(orgId, actorId);
        String domain = normalizeDomain(domainRaw);
        orgAllowedDomainMapper.add(orgId, domain);
        auditService.record("org.allowed_domain.add", "organization", orgId, domain,
                "Allowed domain " + domain, null);
        return orgAllowedDomainMapper.findByOrg(orgId);
    }

    public void removeDomain(int orgId, int actorId, String domainRaw) {
        orgMemberService.requireOrgAdmin(orgId, actorId);
        String domain = normalizeDomain(domainRaw);
        orgAllowedDomainMapper.remove(orgId, domain);
        auditService.record("org.allowed_domain.remove", "organization", orgId, domain,
                "Removed allowed domain " + domain, null);
    }

    /**
     * Whether {@code email}'s domain may be invited into the organization {@code orgId}. True when
     * the org has no allowlist (unrestricted — the non-breaking default), otherwise the email's
     * domain must be on the org list. This is the org-level ceiling; callers AND it with the
     * per-workspace allowlist.
     * @param orgId the organization
     * @param email the invitee's email address
     * @return true when the domain is permitted at the org level
     */
    public boolean isJoinAllowed(int orgId, String email) {
        if (orgAllowedDomainMapper.countByOrg(orgId) == 0) {
            return true;
        }
        return orgAllowedDomainMapper.isAllowed(orgId, domainOf(email));
    }

    /**
     * Whether the organization constrains invites to an email-domain allowlist. When it does, the
     * domain gate is only meaningful for a verified email, so self-serve callers pair this with an
     * email-verification check.
     * @param orgId the organization to check
     * @return true when the org has at least one allowed domain configured
     */
    public boolean hasRestrictions(int orgId) {
        return orgAllowedDomainMapper.countByOrg(orgId) > 0;
    }

    private static String normalizeDomain(String domainRaw) {
        if (domainRaw == null || domainRaw.isBlank()) {
            throw new BadRequestException("Domain is required");
        }
        String domain = domainRaw.trim().toLowerCase();
        if (domain.startsWith("@")) {
            domain = domain.substring(1);
        }
        if (domain.isEmpty() || domain.contains("@") || domain.contains(" ") || !domain.contains(".")) {
            throw new BadRequestException("Enter a valid domain such as example.com");
        }
        return domain;
    }

    private static String domainOf(String email) {
        int at = email.lastIndexOf('@');
        return at >= 0 ? email.substring(at + 1).trim().toLowerCase() : "";
    }
}
