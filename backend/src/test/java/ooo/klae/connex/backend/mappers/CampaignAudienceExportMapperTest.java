package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Campaign;
import ooo.klae.connex.backend.beans.CampaignAudienceExport;
import ooo.klae.connex.backend.beans.CampaignAudienceSnapshot;
import ooo.klae.connex.backend.delivery.provider.list.HttpListConnector;

/**
 * Verifies the workspace-scoped active-export idempotency probe against MySQL: a non-failed export
 * blocks a duplicate, a failed prior attempt allows re-export, and the probe is workspace-scoped.
 */
class CampaignAudienceExportMapperTest extends AbstractMapperTest {

    @Autowired private CampaignMapper campaignMapper;
    @Autowired private CampaignAudienceExportMapper exportMapper;

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
                "a failed prior attempt must allow re-export");

        insertExport(campaign.getId(), snapshot.getId(), connector, "running");
        assertTrue(exportMapper.existsActiveForSnapshotConnector(
                workspace.getId(), campaign.getId(), snapshot.getId(), connector),
                "a non-failed export must block a duplicate");

        assertFalse(exportMapper.existsActiveForSnapshotConnector(
                workspace.getId() + 1, campaign.getId(), snapshot.getId(), connector),
                "the probe must be workspace-scoped");
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
        CampaignAudienceSnapshot snapshot = new CampaignAudienceSnapshot();
        snapshot.setCampaignId(campaignId);
        snapshot.setWorkspaceId(workspace.getId());
        snapshot.setVersion(1);
        snapshot.setRecordType("person");
        snapshot.setDefinitionJson("{}");
        campaignMapper.insertSnapshot(snapshot);
        return snapshot;
    }

    private void insertExport(int campaignId, int snapshotId, String connector, String status) {
        CampaignAudienceExport export = new CampaignAudienceExport();
        export.setWorkspaceId(workspace.getId());
        export.setCampaignId(campaignId);
        export.setSnapshotId(snapshotId);
        export.setConnector(connector);
        export.setStatus(status);
        export.setTotalMembers(0);
        export.setPushedCount(0);
        export.setFailedCount(0);
        exportMapper.insertExport(export);
    }
}
