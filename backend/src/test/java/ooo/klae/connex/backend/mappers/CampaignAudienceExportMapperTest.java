package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Campaign;
import ooo.klae.connex.backend.beans.CampaignAudienceExport;
import ooo.klae.connex.backend.beans.CampaignAudienceSnapshot;
import ooo.klae.connex.backend.delivery.provider.list.HttpListConnector;

/**
 * Verifies the workspace-scoped audience-export durability and duplicate fences against MySQL.
 */
class CampaignAudienceExportMapperTest extends AbstractMapperTest {

    private static final long RUNNING_LEASE_MICROS = Duration.ofMinutes(5).toNanos() / 1_000L;

    @Autowired private CampaignMapper campaignMapper;
    @Autowired private CampaignAudienceExportMapper exportMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void existsActiveForSnapshotConnector_matchesTheRollbackCompatibleFenceWithinTheWorkspace() {
        Campaign campaign = newCampaign();
        CampaignAudienceSnapshot snapshot = newSnapshot(campaign.getId());
        String connector = HttpListConnector.PROVIDER_ID;

        assertFalse(exportMapper.existsActiveForSnapshotConnector(
                workspace.getId(), campaign.getId(), snapshot.getId(), connector));

        insertExport(campaign.getId(), snapshot.getId(), connector, "failed");
        assertFalse(exportMapper.existsActiveForSnapshotConnector(
                workspace.getId(), campaign.getId(), snapshot.getId(), connector),
                "a definitively failed zero-push attempt must allow re-export");

        CampaignAudienceSnapshot pushedFailureSnapshot = newSnapshot(campaign.getId(), 2);
        insertExport(campaign.getId(), pushedFailureSnapshot.getId(), connector, "failed", null, 1, 1);
        assertFalse(exportMapper.existsActiveForSnapshotConnector(
                workspace.getId(), campaign.getId(), pushedFailureSnapshot.getId(), connector),
                "the current and rollback fences must classify every failed row identically");

        CampaignAudienceSnapshot reconciliationSnapshot = newSnapshot(campaign.getId(), 3);
        CampaignAudienceExport ambiguous = insertExport(
                campaign.getId(), reconciliationSnapshot.getId(), connector, "running");
        jdbcTemplate.update("""
                UPDATE campaign_audience_export
                SET lease_until = NULL, reconciliation_required_at = UTC_TIMESTAMP(6),
                    outcome_classification = 'ambiguous'
                WHERE workspace_id = ? AND id = ?
                """, workspace.getId(), ambiguous.getId());
        assertTrue(exportMapper.existsActiveForSnapshotConnector(
                workspace.getId(), campaign.getId(), reconciliationSnapshot.getId(), connector),
                "an ambiguous export must block a duplicate");

        CampaignAudienceSnapshot runningSnapshot = newSnapshot(campaign.getId(), 4);
        insertExport(campaign.getId(), runningSnapshot.getId(), connector, "running");
        assertTrue(exportMapper.existsActiveForSnapshotConnector(
                workspace.getId(), campaign.getId(), runningSnapshot.getId(), connector),
                "a running export must block a duplicate");

        assertFalse(exportMapper.existsActiveForSnapshotConnector(
                workspace.getId() + 1, campaign.getId(), snapshot.getId(), connector),
                "the probe must be workspace-scoped");
    }

    @Test
    void staleRunningLeaseBecomesNeedsReconciliationAndRemainsDuplicateBlocking() {
        Campaign campaign = newCampaign();
        CampaignAudienceSnapshot snapshot = newSnapshot(campaign.getId());
        CampaignAudienceExport export = insertExport(
                campaign.getId(), snapshot.getId(), HttpListConnector.PROVIDER_ID,
                "running", "[41]", 1);
        jdbcTemplate.update("""
                UPDATE campaign_audience_export
                SET lease_until = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND)
                WHERE workspace_id = ? AND id = ?
                """, workspace.getId(), export.getId());

        CampaignAudienceExport history = exportMapper.getByCampaign(
                workspace.getId(), campaign.getId()).getFirst();
        CampaignAudienceExport detail = exportMapper.getExport(workspace.getId(), export.getId());

        assertEquals("running", history.getStatus());
        assertEquals("running", detail.getStatus());
        assertNotNull(history.getReconciliationRequiredAt());
        assertNotNull(detail.getReconciliationRequiredAt());
        assertEquals("running", jdbcTemplate.queryForObject("""
                SELECT status
                FROM campaign_audience_export
                WHERE workspace_id = ? AND id = ?
                """, String.class, workspace.getId(), export.getId()));
        assertEquals(1, exportMapper.markStaleRunningNeedsReconciliation(
                workspace.getId(), campaign.getId(), List.of(export.getId())));
        CampaignAudienceExport reconciled = exportMapper.getExport(workspace.getId(), export.getId());

        assertNotNull(reconciled);
        assertEquals("running", reconciled.getStatus());
        assertNull(reconciled.getLeaseUntil());
        assertNotNull(reconciled.getReconciliationRequiredAt());
        assertEquals("ambiguous", reconciled.getOutcomeClassification());
        assertTrue(exportMapper.existsActiveForSnapshotConnector(
                workspace.getId(), campaign.getId(), snapshot.getId(), HttpListConnector.PROVIDER_ID));
    }

    @Test
    void initialAndRefreshedLeasesAreDerivedFromTheDatabaseClock() {
        Campaign campaign = newCampaign();
        CampaignAudienceSnapshot snapshot = newSnapshot(campaign.getId());
        CampaignAudienceExport export = insertExport(
                campaign.getId(), snapshot.getId(), HttpListConnector.PROVIDER_ID,
                "running", "[41]", 1);
        long initialRemainingMicros = jdbcTemplate.queryForObject("""
                SELECT TIMESTAMPDIFF(MICROSECOND, UTC_TIMESTAMP(6), lease_until)
                FROM campaign_audience_export
                WHERE workspace_id = ? AND id = ?
                """, Long.class, workspace.getId(), export.getId());
        long refreshedLeaseMicros = Duration.ofSeconds(48).toNanos() / 1_000L;
        export.setIdempotencyKey("campaign-audience-test-a1");

        assertEquals(1, exportMapper.stagePush(export, refreshedLeaseMicros));
        long refreshedRemainingMicros = jdbcTemplate.queryForObject("""
                SELECT TIMESTAMPDIFF(MICROSECOND, UTC_TIMESTAMP(6), lease_until)
                FROM campaign_audience_export
                WHERE workspace_id = ? AND id = ?
                """, Long.class, workspace.getId(), export.getId());

        assertTrue(initialRemainingMicros > Duration.ofMinutes(4).toNanos() / 1_000L);
        assertTrue(initialRemainingMicros <= RUNNING_LEASE_MICROS);
        assertTrue(refreshedRemainingMicros > Duration.ofSeconds(40).toNanos() / 1_000L);
        assertTrue(refreshedRemainingMicros <= refreshedLeaseMicros);
        assertEquals("campaign-audience-test-a1",
                exportMapper.getExport(workspace.getId(), export.getId()).getIdempotencyKey());
    }

    @Test
    void definitiveFailureReasonIsPersistedAndVisibleInHistory() {
        Campaign campaign = newCampaign();
        CampaignAudienceSnapshot snapshot = newSnapshot(campaign.getId());
        CampaignAudienceExport export = insertExport(
                campaign.getId(), snapshot.getId(), HttpListConnector.PROVIDER_ID,
                "running", "[41]", 1);
        export.setStatus("failed");
        export.setPushedCount(0);
        export.setFailedCount(1);
        export.setFailureReason("resolver_saturated");
        export.setOutcomeClassification("definite_no_side_effect");

        assertEquals(1, exportMapper.updateOutcome(export));

        CampaignAudienceExport history = exportMapper.getByCampaign(
                workspace.getId(), campaign.getId()).getFirst();
        assertEquals("failed", history.getStatus());
        assertEquals("resolver_saturated", history.getFailureReason());
        assertEquals("definite_no_side_effect", history.getOutcomeClassification());
    }

    @Test
    void legacyRunningExportWithoutALeaseIsNotStaleAndRemainsDuplicateBlocking() {
        Campaign campaign = newCampaign();
        CampaignAudienceSnapshot snapshot = newSnapshot(campaign.getId());
        CampaignAudienceExport export = new CampaignAudienceExport();
        export.setWorkspaceId(workspace.getId());
        export.setCampaignId(campaign.getId());
        export.setSnapshotId(snapshot.getId());
        export.setConnector(HttpListConnector.PROVIDER_ID);
        export.setFrozenMemberIdsJson(null);
        export.setPushedMemberIdsJson(null);
        export.setStatus("running");
        export.setAttempt(1);
        export.setLeaseUntil(null);
        export.setTotalMembers(0);
        export.setPushedCount(0);
        export.setFailedCount(0);

        exportMapper.insertExport(export, RUNNING_LEASE_MICROS);
        jdbcTemplate.update("""
                UPDATE campaign_audience_export
                SET lease_until = NULL
                WHERE workspace_id = ? AND id = ?
                """, workspace.getId(), export.getId());

        CampaignAudienceExport history = exportMapper.getExport(workspace.getId(), export.getId());
        assertEquals("running", history.getStatus());
        assertNull(history.getLeaseUntil());
        assertNull(history.getReconciliationRequiredAt());
        assertEquals(0, exportMapper.markStaleRunningNeedsReconciliation(
                workspace.getId(), campaign.getId(), List.of(export.getId())));
        assertTrue(exportMapper.existsActiveForSnapshotConnector(
                workspace.getId(), campaign.getId(), snapshot.getId(), HttpListConnector.PROVIDER_ID));
    }

    @Test
    void frozenMemberIdsRoundTripThroughTheLockingRead() {
        Campaign campaign = newCampaign();
        CampaignAudienceSnapshot snapshot = newSnapshot(campaign.getId());
        CampaignAudienceExport export = insertExport(
                campaign.getId(), snapshot.getId(), HttpListConnector.PROVIDER_ID,
                "running", "[41,73]", 2);

        CampaignAudienceExport locked = exportMapper.getExportForUpdate(workspace.getId(), export.getId());

        assertNotNull(locked);
        assertTrue(locked.getFrozenMemberIdsJson().contains("41"));
        assertTrue(locked.getFrozenMemberIdsJson().contains("73"));
    }

    @Test
    void nextAttemptCountsPriorRequestsForTheExactSnapshotTarget() {
        Campaign campaign = newCampaign();
        CampaignAudienceSnapshot snapshot = newSnapshot(campaign.getId());
        CampaignAudienceExport first = insertExport(
                campaign.getId(), snapshot.getId(), HttpListConnector.PROVIDER_ID,
                "failed", "[41]", 1);
        first.setExternalListId("list-a");
        jdbcTemplate.update("""
                UPDATE campaign_audience_export
                SET external_list_id = ?
                WHERE workspace_id = ? AND id = ?
                """, first.getExternalListId(), workspace.getId(), first.getId());

        assertEquals(2, exportMapper.nextAttemptForSnapshotTarget(
                workspace.getId(), campaign.getId(), snapshot.getId(),
                HttpListConnector.PROVIDER_ID, "list-a"));
        assertEquals(1, exportMapper.nextAttemptForSnapshotTarget(
                workspace.getId(), campaign.getId(), snapshot.getId(),
                HttpListConnector.PROVIDER_ID, "list-b"));
        assertEquals(1, exportMapper.nextAttemptForSnapshotTarget(
                workspace.getId() + 1, campaign.getId(), snapshot.getId(),
                HttpListConnector.PROVIDER_ID, "list-a"));
    }

    @Test
    void resolveReconciliationTransitionsOnlyTheWorkspaceScopedAmbiguousExport() {
        Campaign campaign = newCampaign();
        CampaignAudienceSnapshot snapshot = newSnapshot(campaign.getId());
        CampaignAudienceExport export = insertExport(
                campaign.getId(), snapshot.getId(), HttpListConnector.PROVIDER_ID,
                "running", "[41,73]", 2);
        jdbcTemplate.update("""
                UPDATE campaign_audience_export
                SET lease_until = NULL, reconciliation_required_at = UTC_TIMESTAMP(6),
                    outcome_classification = 'ambiguous'
                WHERE workspace_id = ? AND id = ?
                """, workspace.getId(), export.getId());
        export.setLeaseUntil(null);
        export.setReconciliationRequiredAt(LocalDateTime.now(ZoneOffset.UTC));
        export.setStatus("failed");
        export.setPushedCount(0);
        export.setFailedCount(2);
        export.setOutcomeClassification("operator_not_delivered");

        assertEquals(0, exportMapper.resolveReconciliation(exportForWorkspace(export, workspace.getId() + 1)));
        assertEquals(1, exportMapper.resolveReconciliation(export));
        CampaignAudienceExport resolved = exportMapper.getExport(workspace.getId(), export.getId());

        assertNotNull(resolved);
        assertEquals("failed", resolved.getStatus());
        assertEquals(0, resolved.getPushedCount());
        assertEquals(2, resolved.getFailedCount());
        assertEquals("operator_not_delivered", resolved.getOutcomeClassification());
        assertFalse(exportMapper.existsActiveForSnapshotConnector(
                workspace.getId(), campaign.getId(), snapshot.getId(), HttpListConnector.PROVIDER_ID));
        assertEquals(0, exportMapper.resolveReconciliation(export));
    }

    @Test
    void resolveReconciliationAcceptsOnlyANullLeaseLegacyRunningExport() {
        Campaign campaign = newCampaign();
        CampaignAudienceSnapshot snapshot = newSnapshot(campaign.getId());
        CampaignAudienceExport legacy = insertExport(
                campaign.getId(), snapshot.getId(), HttpListConnector.PROVIDER_ID,
                "running", "[41,73]", 2);
        jdbcTemplate.update("""
                UPDATE campaign_audience_export
                SET lease_until = NULL
                WHERE workspace_id = ? AND id = ?
                """, workspace.getId(), legacy.getId());
        legacy.setLeaseUntil(null);
        legacy.setStatus("failed");
        legacy.setPushedCount(0);
        legacy.setFailedCount(2);
        legacy.setOutcomeClassification("operator_not_delivered");

        assertEquals(1, exportMapper.resolveReconciliation(legacy));
        CampaignAudienceExport resolved = exportMapper.getExport(workspace.getId(), legacy.getId());
        assertEquals("failed", resolved.getStatus());
        assertEquals("operator_not_delivered", resolved.getOutcomeClassification());
        assertEquals(0, exportMapper.resolveReconciliation(legacy));
    }

    @Test
    void lateOutcomeMarkerIsWorkspaceScopedAndVisibleInHistory() {
        Campaign campaign = newCampaign();
        CampaignAudienceSnapshot snapshot = newSnapshot(campaign.getId());
        CampaignAudienceExport export = insertExport(
                campaign.getId(), snapshot.getId(), HttpListConnector.PROVIDER_ID,
                "running", "[41]", 1);

        assertEquals(0, exportMapper.markLateOutcome(
                workspace.getId() + 1, export.getId(), "confirmed_delivery", null));
        assertEquals(1, exportMapper.markLateOutcome(
                workspace.getId(), export.getId(), "confirmed_delivery", null));
        assertEquals(0, exportMapper.markLateOutcome(
                workspace.getId(), export.getId(), "ambiguous", null));

        CampaignAudienceExport history = exportMapper.getByCampaign(
                workspace.getId(), campaign.getId()).getFirst();
        assertEquals("confirmed_delivery", history.getLateOutcome());
    }

    private Campaign newCampaign() {
        Campaign campaign = new Campaign();
        campaign.setWorkspaceId(workspace.getId());
        campaign.setName("Campaign " + unique());
        campaign.setType("email");
        campaign.setStatus("active");
        campaignMapper.insertCampaign(campaign);
        return campaign;
    }

    private CampaignAudienceSnapshot newSnapshot(int campaignId) {
        return newSnapshot(campaignId, 1);
    }

    private CampaignAudienceSnapshot newSnapshot(int campaignId, int version) {
        CampaignAudienceSnapshot snapshot = new CampaignAudienceSnapshot();
        snapshot.setCampaignId(campaignId);
        snapshot.setWorkspaceId(workspace.getId());
        snapshot.setVersion(version);
        snapshot.setRecordType("person");
        snapshot.setDefinitionJson("{}");
        snapshot.setChannel("email");
        snapshot.setPurpose("marketing");
        campaignMapper.insertSnapshot(snapshot);
        return snapshot;
    }

    private CampaignAudienceExport insertExport(int campaignId, int snapshotId, String connector, String status) {
        return insertExport(campaignId, snapshotId, connector, status, "[]", 0);
    }

    private CampaignAudienceExport insertExport(
            int campaignId, int snapshotId, String connector, String status,
            String frozenMemberIdsJson, int totalMembers) {
        return insertExport(
                campaignId, snapshotId, connector, status, frozenMemberIdsJson, totalMembers, 0);
    }

    private CampaignAudienceExport insertExport(
            int campaignId, int snapshotId, String connector, String status,
            String frozenMemberIdsJson, int totalMembers, int pushedCount) {
        CampaignAudienceExport export = new CampaignAudienceExport();
        export.setWorkspaceId(workspace.getId());
        export.setCampaignId(campaignId);
        export.setSnapshotId(snapshotId);
        export.setConnector(connector);
        export.setFrozenMemberIdsJson(frozenMemberIdsJson);
        export.setPushedMemberIdsJson(frozenMemberIdsJson == null ? null : "[]");
        export.setStatus(status);
        if ("failed".equals(status) && frozenMemberIdsJson != null) {
            export.setOutcomeClassification("definite_no_side_effect");
        }
        export.setAttempt(1);
        export.setTotalMembers(totalMembers);
        export.setPushedCount(pushedCount);
        export.setFailedCount(0);
        exportMapper.insertExport(export, RUNNING_LEASE_MICROS);
        return export;
    }

    private static CampaignAudienceExport exportForWorkspace(
            CampaignAudienceExport source, int workspaceId) {
        CampaignAudienceExport copy = new CampaignAudienceExport();
        copy.setId(source.getId());
        copy.setWorkspaceId(workspaceId);
        copy.setStatus(source.getStatus());
        copy.setPushedCount(source.getPushedCount());
        copy.setFailedCount(source.getFailedCount());
        return copy;
    }
}
