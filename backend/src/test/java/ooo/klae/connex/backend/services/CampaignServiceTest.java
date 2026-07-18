package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.CampaignAudienceEstimateDto;
import ooo.klae.connex.backend.dto.CampaignAudienceRequest;
import ooo.klae.connex.backend.dto.CampaignAudienceSnapshotDto;
import ooo.klae.connex.backend.dto.CampaignDto;
import ooo.klae.connex.backend.dto.CampaignRequest;
import ooo.klae.connex.backend.dto.ContactChannelConsentRequest;
import ooo.klae.connex.backend.dto.SegmentCondition;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.dto.SuppressionEntryDto;
import ooo.klae.connex.backend.dto.SuppressionEntryRequest;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;

class CampaignServiceTest extends AbstractServiceTest {
    @Autowired private CampaignService campaignService;
    @Autowired private ConsentService consentService;
    @Autowired private SuppressionService suppressionService;
    @Autowired private WorkspaceService workspaceService;

    @Test
    void crudAndTerminalStatusGuard() {
        CampaignDto created = campaignService.create(campaignRequest("Campaign " + unique(), null));
        assertEquals("draft", created.status());
        assertTrue(campaignService.list().stream().anyMatch(campaign -> campaign.id() == created.id()));

        CampaignDto active = campaignService.update(
                created.id(), campaignRequest("Updated campaign", "active"));
        assertEquals("active", active.status());
        CampaignDto completed = campaignService.update(
                created.id(), campaignRequest("Updated campaign", "completed"));
        assertEquals("completed", completed.status());

        assertThrows(BadRequestException.class,
                () -> campaignService.update(
                        created.id(), campaignRequest("Updated campaign", "paused")));
        campaignService.delete(created.id());
        assertThrows(ResourceNotFoundException.class, () -> campaignService.get(created.id()));
    }

    @Test
    void campaignManagementRequiresManagePermissionWhileViewRemainsAvailable() {
        User member = newUser();
        authenticateAs(member, workspace.getId());

        assertTrue(campaignService.list().isEmpty());
        assertThrows(ForbiddenException.class,
                () -> campaignService.create(campaignRequest("Denied", null)));
    }

    /**
     * Consent is opt-out: only an explicit revocation excludes, so the person with no consent record at
     * all is now counted as included. The restricted &rarr; suppressed &rarr; consent precedence and the
     * no-double-counting math are unchanged — the restricted person is also suppressed and is still
     * counted once, as restricted.
     */
    @Test
    void estimateAppliesRestrictedThenSuppressedThenRevokedConsentWithoutDoubleCounting() {
        String prefix = "audience-" + unique();
        Company company = newCompany();
        Person restricted = person(company, prefix + "-restricted", prefix + "-restricted@example.com");
        Person suppressed = person(company, prefix + "-suppressed", prefix + "-suppressed@example.com");
        Person consentRevoked = person(company, prefix + "-revoked", prefix + "-revoked@example.com");
        Person included = person(company, prefix + "-included", prefix + "-included@example.com");
        personMapper.updateProcessingRestrictions(workspace.getId(), restricted.getId(), true, false);
        suppressionService.add(suppression(restricted));
        suppressionService.add(suppression(suppressed));
        consentService.setForPerson(consentRevoked.getId(), revokedConsent());

        CampaignDto campaign = campaignService.create(campaignRequest("Audience math", null));
        campaignService.setAudience(campaign.id(), audience(prefix));

        CampaignAudienceEstimateDto estimate = campaignService.estimateAudience(campaign.id());

        assertEquals(1, estimate.estimatedIncluded());
        assertEquals(1, estimate.excludedRestricted());
        assertEquals(1, estimate.excludedSuppressed());
        assertEquals(1, estimate.excludedConsent());
        assertEquals(3, estimate.excludedTotal());
        assertEquals(List.of(included.getId()),
                estimate.sampleLabels().stream().map(label -> label.getId()).toList());
        assertTrue(estimate.sampleLabels().stream().noneMatch(label -> label.getId() == consentRevoked.getId()));
    }

    @Test
    void snapshotRemainsFrozenAfterActiveAudienceChanges() {
        String prefix = "snapshot-" + unique();
        Person included = person(newCompany(), prefix + "-included", prefix + "@example.com");
        consentService.setForPerson(included.getId(), grantedConsent());
        CampaignDto campaign = campaignService.create(campaignRequest("Snapshot", null));
        campaignService.setAudience(campaign.id(), audience(prefix));

        CampaignAudienceSnapshotDto first = campaignService.snapshotAudience(campaign.id());
        campaignService.setAudience(campaign.id(), audience("no-match-" + unique()));
        CampaignAudienceEstimateDto changedEstimate = campaignService.estimateAudience(campaign.id());
        CampaignAudienceSnapshotDto frozen = campaignService.getSnapshot(campaign.id(), first.version());
        CampaignAudienceSnapshotDto second = campaignService.snapshotAudience(campaign.id());

        assertEquals(1, first.version());
        assertEquals(1, frozen.estimatedIncluded());
        assertEquals(List.of(included.getId()), frozen.members().stream()
                .map(member -> member.recordId()).toList());
        assertEquals(prefix, frozen.definition().getConditions().getFirst().getValue());
        assertEquals(0, changedEstimate.estimatedIncluded());
        assertEquals(2, second.version());
        assertEquals(0, second.members().size());
        assertThrows(BadRequestException.class, () -> campaignService.delete(campaign.id()));
    }

    @Test
    void personAudienceClassificationAlsoRequiresConsentManage() {
        String prefix = "consent-gate-" + unique();
        CampaignDto campaign = campaignService.create(campaignRequest("Consent gate", null));
        campaignService.setAudience(campaign.id(), audience(prefix));
        User member = newUser();
        authenticateAs(member, workspace.getId());

        assertThrows(ForbiddenException.class, () -> campaignService.estimateAudience(campaign.id()));
    }

    @Test
    void otherTenantCannotReadOrMutateCampaignConsentOrSuppression() {
        Person person = person(newCompany(), "tenant-" + unique(), "tenant-" + unique() + "@example.com");
        CampaignDto campaign = campaignService.create(campaignRequest("Tenant A", null));
        consentService.setForPerson(person.getId(), grantedConsent());
        SuppressionEntryDto suppression = suppressionService.add(suppression(person));
        User other = newUser();
        WorkspaceMembershipDto otherWorkspace = workspaceService.createWorkspace("Tenant B", other.getId());
        authenticateAs(other, otherWorkspace.getId());

        assertThrows(ResourceNotFoundException.class, () -> campaignService.get(campaign.id()));
        assertThrows(ResourceNotFoundException.class,
                () -> campaignService.update(campaign.id(), campaignRequest("Cross-tenant", "active")));
        assertThrows(ResourceNotFoundException.class, () -> consentService.getForPerson(person.getId()));
        assertThrows(ResourceNotFoundException.class,
                () -> consentService.setForPerson(person.getId(), grantedConsent()));
        assertTrue(suppressionService.list().isEmpty());
        assertThrows(ResourceNotFoundException.class, () -> suppressionService.remove(suppression.id()));
    }

    private CampaignRequest campaignRequest(String name, String status) {
        return new CampaignRequest(
                name, null, "email", status, currentUser.getId(), null, null,
                null, null, null);
    }

    private Person person(Company company, String name, String email) {
        Person person = new Person();
        person.setWorkspaceId(workspace.getId());
        person.setName(name);
        person.setEmail(email);
        person.setTitle("Marketing contact");
        person.setCompany(company);
        personMapper.insert(person);
        return person;
    }

    private CampaignAudienceRequest audience(String namePrefix) {
        SegmentCondition condition = new SegmentCondition();
        condition.setType("field");
        condition.setField("name");
        condition.setOp("starts_with");
        condition.setValue(namePrefix);
        SegmentDefinition definition = new SegmentDefinition();
        definition.setMatch("all");
        definition.setConditions(List.of(condition));
        return new CampaignAudienceRequest("person", definition);
    }

    private SuppressionEntryRequest suppression(Person person) {
        return new SuppressionEntryRequest(
                "workspace", "email", person.getEmail().toUpperCase(), person.getId(),
                "manual", null);
    }

    private ContactChannelConsentRequest grantedConsent() {
        return new ContactChannelConsentRequest(
                "email", "marketing", "granted", "manual", null, null);
    }

    private ContactChannelConsentRequest revokedConsent() {
        return new ContactChannelConsentRequest(
                "email", "marketing", "revoked", "manual", null, null);
    }
}
