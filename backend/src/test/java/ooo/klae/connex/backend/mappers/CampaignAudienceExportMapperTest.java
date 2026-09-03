package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Autowired private CampaignMapper campaignMapper;
    @Autowired private CampaignAudienceExportMapper exportMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void existsActiveForSnapshotConnector_matchesNonFailedExportsWithinTheWorkspace() {
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
        insertExport(campaign.getId(), pushedFailureSnapshot.getId(), connector, "failed", "[]", 1, 1);
        assertTrue(exportMapper.existsActiveForSnapshotConnector(
                workspace.getId(), campaign.getId(), pushedFailureSnapshot.getId(), connector),
                "a failed export that already pushed members must block a duplicate");

        CampaignAudienceSnapshot reconciliationSnapshot = newSnapshot(campaign.getId(), 3);
        insertExport(campaign.getId(), reconciliationSnapshot.getId(), connector, "needs_reconciliation");
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

        assertEquals("needs_reconciliation", history.getStatus());
        assertEquals("needs_reconciliation", detail.getStatus());
        assertEquals("running", jdbcTemplate.queryForObject("""
                SELECT status
                FROM campaign_audience_export
                WHERE workspace_id = ? AND id = ?
                """, String.class, workspace.getId(), export.getId()));
        assertEquals(1, exportMapper.markStaleRunningNeedsReconciliation(
                workspace.getId(), campaign.getId(), List.of(export.getId())));
        CampaignAudienceExport reconciled = exportMapper.getExport(workspace.getId(), export.getId());

        assertNotNull(reconciled);
        assertEquals("needs_reconciliation", reconciled.getStatus());
        assertNull(reconciled.getLeaseUntil());
        assertTrue(exportMapper.existsActiveForSnapshotConnector(
                workspace.getId(), campaign.getId(), snapshot.getId(), HttpListConnector.PROVIDER_ID));
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

        exportMapper.insertExport(export);

        CampaignAudienceExport history = exportMapper.getExport(workspace.getId(), export.getId());
        assertEquals("running", history.getStatus());
        assertNull(history.getLeaseUntil());
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
                "needs_reconciliation", "[41,73]", 2);
        export.setStatus("failed");
        export.setPushedCount(0);
        export.setFailedCount(2);

        assertEquals(0, exportMapper.resolveReconciliation(exportForWorkspace(export, workspace.getId() + 1)));
        assertEquals(1, exportMapper.resolveReconciliation(export));
        CampaignAudienceExport resolved = exportMapper.getExport(workspace.getId(), export.getId());

        assertNotNull(resolved);
        assertEquals("failed", resolved.getStatus());
        assertEquals(0, resolved.getPushedCount());
        assertEquals(2, resolved.getFailedCount());
        assertFalse(exportMapper.existsActiveForSnapshotConnector(
                workspace.getId(), campaign.getId(), snapshot.getId(), HttpListConnector.PROVIDER_ID));
        assertEquals(0, exportMapper.resolveReconciliation(export));
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
        export.setPushedMemberIdsJson("[]");
        export.setStatus(status);
        export.setAttempt(1);
        if ("running".equals(status)) {
            export.setLeaseUntil(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5));
        }
        export.setTotalMembers(totalMembers);
        export.setPushedCount(pushedCount);
        export.setFailedCount(0);
        exportMapper.insertExport(export);
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
