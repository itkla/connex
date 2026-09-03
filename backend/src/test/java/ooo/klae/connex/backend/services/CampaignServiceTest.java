package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.CampaignMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CampaignServiceTest extends CampaignRealDbTestSupport {
    @Autowired private CampaignService campaignService;
    @Autowired private ConsentService consentService;
    @Autowired private SuppressionService suppressionService;
    @Autowired private CampaignMapper campaignMapper;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

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
        Person included = personWithAddresses(
                newCompany(), prefix + "-included", prefix + "@example.com", "+81 90-7777-8888");
        consentService.setForPerson(included.getId(), grantedConsent());
        CampaignDto campaign = campaignService.create(campaignRequest("Snapshot", null));
        campaignService.setAudience(campaign.id(), audience(prefix, "email", "marketing"));

        CampaignAudienceSnapshotDto first = campaignService.snapshotAudience(campaign.id());
        campaignService.setAudience(campaign.id(), audience(prefix, "sms", "product_update"));
        CampaignAudienceEstimateDto changedEstimate = campaignService.estimateAudience(campaign.id());
        CampaignAudienceSnapshotDto frozen = campaignService.getSnapshot(campaign.id(), first.version());
        CampaignAudienceSnapshotDto second = campaignService.snapshotAudience(campaign.id());

        assertEquals(1, first.version());
        assertEquals("email", first.channel());
        assertEquals("marketing", first.purpose());
        assertEquals(1, frozen.estimatedIncluded());
        assertEquals(List.of(included.getId()), frozen.members().stream()
                .map(member -> member.recordId()).toList());
        assertEquals(prefix, frozen.definition().getConditions().getFirst().getValue());
        assertEquals("email", frozen.channel());
        assertEquals("marketing", frozen.purpose());
        assertEquals("sms", changedEstimate.channel());
        assertEquals("product_update", changedEstimate.purpose());
        assertEquals(1, changedEstimate.estimatedIncluded());
        assertEquals(2, second.version());
        assertEquals("sms", second.channel());
        assertEquals("product_update", second.purpose());
        assertEquals(1, second.estimatedIncluded());
        assertThrows(BadRequestException.class, () -> campaignService.delete(campaign.id()));
    }

    @Test
    void smsAudienceUsesItsStoredChannelForEveryExclusionAndFrozenCount() {
        String prefix = "sms-audience-" + unique();
        Company company = newCompany();
        Person smsSuppressed = personWithAddresses(
                company, prefix + "-sms-suppressed", prefix + "-sms@example.com", "+81 90-1111-2222");
        Person emailSuppressed = personWithAddresses(
                company, prefix + "-email-suppressed", prefix + "-email@example.com", "+81 90-2222-3333");
        Person noPhone = person(company, prefix + "-no-phone", prefix + "-no-phone@example.com");
        Person consentRevoked = personWithAddresses(
                company, prefix + "-revoked", prefix + "-revoked@example.com", "+81 90-3333-4444");
        Person included = personWithAddresses(
                company, prefix + "-included", prefix + "-included@example.com", "+81 90-4444-5555");
        suppressionService.add(new SuppressionEntryRequest(
                "workspace", "sms", "+819011112222", smsSuppressed.getId(), "manual", null));
        suppressionService.add(suppression(emailSuppressed));
        consentService.setForPerson(consentRevoked.getId(), new ContactChannelConsentRequest(
                "sms", "marketing", "revoked", "manual", null, null));

        CampaignDto campaign = campaignService.create(campaignRequest("SMS audience", null));
        var stored = campaignService.setAudience(campaign.id(), audience(prefix, "sms", "marketing"));
        CampaignAudienceEstimateDto estimate = campaignService.estimateAudience(campaign.id());
        CampaignAudienceSnapshotDto snapshot = campaignService.snapshotAudience(campaign.id());
        CampaignAudienceSnapshotDto frozen = campaignService.getSnapshot(campaign.id(), snapshot.version());
        var summary = campaignService.listSnapshots(campaign.id()).getFirst();

        assertEquals("sms", stored.channel());
        assertEquals("marketing", stored.purpose());
        assertEquals("sms", estimate.channel());
        assertEquals("marketing", estimate.purpose());
        assertEquals(2, estimate.estimatedIncluded());
        assertEquals(1, estimate.excludedNoAddress());
        assertEquals(1, estimate.excludedSuppressed());
        assertEquals(1, estimate.excludedConsent());
        assertEquals(0, estimate.excludedRestricted());
        assertEquals(3, estimate.excludedTotal());
        assertEquals(List.of(emailSuppressed.getId(), included.getId()),
                estimate.sampleLabels().stream().map(label -> label.getId()).toList());
        assertEquals("sms", frozen.channel());
        assertEquals("marketing", frozen.purpose());
        assertEquals(1, frozen.excludedNoAddress());
        assertEquals("sms", summary.channel());
        assertEquals("marketing", summary.purpose());
        assertEquals(1, summary.excludedNoAddress());
        assertTrue(frozen.members().stream().anyMatch(member ->
                member.recordId() == noPhone.getId() && "no_address".equals(member.exclusionReason())));
        assertTrue(frozen.members().stream().anyMatch(member ->
                member.recordId() == smsSuppressed.getId() && "suppressed".equals(member.exclusionReason())));
        assertTrue(frozen.members().stream().anyMatch(member ->
                member.recordId() == consentRevoked.getId()
                        && "consent_revoked".equals(member.exclusionReason())));
    }

    @Test
    void smsSuppressionDoesNotExcludeTheSameContactFromAnEmailAudience() {
        String prefix = "email-audience-" + unique();
        Person smsSuppressed = personWithAddresses(
                newCompany(), prefix + "-contact", prefix + "@example.com", "+81 90-8888-9999");
        suppressionService.add(new SuppressionEntryRequest(
                "workspace", "sms", "+819088889999", smsSuppressed.getId(), "manual", null));
        CampaignDto campaign = campaignService.create(campaignRequest("Email audience", null));
        campaignService.setAudience(campaign.id(), audience(prefix, "email", "marketing"));

        CampaignAudienceEstimateDto estimate = campaignService.estimateAudience(campaign.id());
        CampaignAudienceSnapshotDto snapshot = campaignService.snapshotAudience(campaign.id());

        assertEquals(1, estimate.estimatedIncluded());
        assertEquals(0, estimate.excludedSuppressed());
        assertEquals(1, snapshot.estimatedIncluded());
        assertEquals(0, snapshot.excludedSuppressed());
        assertEquals("included", snapshot.members().getFirst().status());
    }

    @Test
    void missingEmailTakesTheNoAddressBucketBeforeAPersonLinkedEmailSuppression() {
        String prefix = "no-email-" + unique();
        Person person = personWithAddresses(
                newCompany(), prefix + "-contact", null, "+81 90-5555-6666");
        suppressionService.add(new SuppressionEntryRequest(
                "workspace", "email", prefix + "-old@example.com", person.getId(), "manual", null));
        CampaignDto campaign = campaignService.create(campaignRequest("No email", null));
        campaignService.setAudience(campaign.id(), audience(prefix, "email", "marketing"));

        CampaignAudienceSnapshotDto snapshot = campaignService.snapshotAudience(campaign.id());

        assertEquals(0, snapshot.estimatedIncluded());
        assertEquals(1, snapshot.excludedNoAddress());
        assertEquals(0, snapshot.excludedSuppressed());
        assertEquals("no_address", snapshot.members().getFirst().exclusionReason());
    }

    @Test
    void productUpdateRevocationExcludesTheRecipientFromThatPurposeOnly() {
        String prefix = "product-update-" + unique();
        Person person = person(newCompany(), prefix + "-contact", prefix + "@example.com");
        CampaignDto campaign = campaignService.create(campaignRequest("Product updates", null));
        campaignService.setAudience(campaign.id(), audience(prefix, "email", "product_update"));

        CampaignAudienceSnapshotDto beforeRevocation = campaignService.snapshotAudience(campaign.id());
        consentService.setForPerson(person.getId(), new ContactChannelConsentRequest(
                "email", "product_update", "revoked", "manual", null, null));
        CampaignAudienceEstimateDto afterRevocation = campaignService.estimateAudience(campaign.id());

        assertEquals("product_update", beforeRevocation.purpose());
        assertEquals(1, beforeRevocation.estimatedIncluded());
        assertEquals("product_update", afterRevocation.purpose());
        assertEquals(0, afterRevocation.estimatedIncluded());
        assertEquals(1, afterRevocation.excludedConsent());
    }

    @Test
    void audienceScopeAuditRecordsChannelAndPurposeBeforeAndAfterWithoutDefinitionData() throws Exception {
        String prefix = "audit-scope-" + unique();
        CampaignDto campaign = campaignService.create(campaignRequest("Audience audit", null));
        campaignService.setAudience(campaign.id(), audience(prefix, "email", "marketing"));
        campaignService.setAudience(campaign.id(), audience(prefix, "sms", "product_update"));

        List<String> auditChanges = jdbcTemplate.queryForList("""
                SELECT changes
                FROM audit_log
                WHERE workspace_id = ?
                  AND action = 'campaign.audience.set'
                  AND entity_type = 'campaign'
                  AND entity_id = ?
                ORDER BY id
                """, String.class, workspace.getId(), campaign.id());
        assertEquals(2, auditChanges.size());
        JsonNode changes = objectMapper.readTree(auditChanges.getLast());

        assertEquals("email", changes.path("channel").path("old").asText());
        assertEquals("sms", changes.path("channel").path("new").asText());
        assertEquals("marketing", changes.path("purpose").path("old").asText());
        assertEquals("product_update", changes.path("purpose").path("new").asText());
        assertFalse(changes.toString().contains(prefix));
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
        CampaignActorWorkspace other = newCampaignWorkspaceActor();
        authenticateAs(other.actor(), other.workspace().getId());

        assertThrows(ResourceNotFoundException.class, () -> campaignService.get(campaign.id()));
        assertThrows(ResourceNotFoundException.class,
                () -> campaignService.update(campaign.id(), campaignRequest("Cross-tenant", "active")));
        assertThrows(ResourceNotFoundException.class,
                () -> campaignService.setAudience(campaign.id(), audience("cross-tenant-" + unique())));
        assertThrows(ResourceNotFoundException.class, () -> campaignService.snapshotAudience(campaign.id()));
        assertThrows(ResourceNotFoundException.class, () -> consentService.getForPerson(person.getId()));
        assertThrows(ResourceNotFoundException.class,
                () -> consentService.setForPerson(person.getId(), grantedConsent()));
        assertTrue(suppressionService.list().isEmpty());
        assertThrows(ResourceNotFoundException.class, () -> suppressionService.remove(suppression.id()));
        assertNull(campaignMapper.getAudience(other.workspace().getId(), campaign.id()));
        assertTrue(campaignMapper.getSnapshots(other.workspace().getId(), campaign.id()).isEmpty());

        authenticateAs(currentUser, workspace.getId());
        assertNull(campaignService.getAudience(campaign.id()));
        assertTrue(campaignService.listSnapshots(campaign.id()).isEmpty());
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

    private Person personWithAddresses(Company company, String name, String email, String phone) {
        Person person = person(company, name, email);
        person.setPhone(phone);
        personMapper.update(person);
        return person;
    }

    private CampaignAudienceRequest audience(String namePrefix) {
        return audience(namePrefix, null, null);
    }

    private CampaignAudienceRequest audience(String namePrefix, String channel, String purpose) {
        SegmentCondition condition = new SegmentCondition();
        condition.setType("field");
        condition.setField("name");
        condition.setOp("starts_with");
        condition.setValue(namePrefix);
        SegmentDefinition definition = new SegmentDefinition();
        definition.setMatch("all");
        definition.setConditions(List.of(condition));
        return new CampaignAudienceRequest("person", definition, channel, purpose);
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
