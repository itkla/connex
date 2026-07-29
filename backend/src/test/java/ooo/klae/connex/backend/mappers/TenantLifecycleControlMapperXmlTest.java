package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** Pins fail-closed lifecycle lease, admission, and subject-link control SQL. */
class TenantLifecycleControlMapperXmlTest {

    @Test
    void admissionAndEveryPersistedLeaseRemainExactAndAgeIndependent() throws Exception {
        String resource = "mappers/TenantLifecycleControlMapper.xml";
        String xml;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(xml.contains("id=\"countOperationLeases\""));
        assertTrue(xml.contains("id=\"countOperationLeasesInOrg\""));
        assertTrue(xml.contains("WHERE org_id = #{orgId}"));
        assertTrue(xml.contains("id=\"lockActiveOrganizationForShare\""));
        assertTrue(xml.contains("id=\"lockWorkspaceForShare\""));
        assertTrue(xml.contains("id=\"lockExportAdmissionCapacityNowait\""));
        assertTrue(xml.contains("FROM tenant_export_admission_control"));
        assertTrue(xml.contains("FOR UPDATE NOWAIT"));
        assertTrue(xml.contains("id=\"countGlobalExportLeases\""));
        assertTrue(xml.contains("WHERE lease_kind = 'export'"));
        assertTrue(xml.contains("id=\"countAllOperationLeases\""));
        int workspaceStart = xml.indexOf("id=\"lockWorkspaceForShare\"");
        int workspaceEnd = xml.indexOf("</select>", workspaceStart);
        String workspaceLock = xml.substring(workspaceStart, workspaceEnd);
        assertFalse(workspaceLock.contains("JOIN organization"));
        assertFalse(workspaceLock.contains("#{orgId}"));
        assertTrue(workspaceLock.contains("FOR SHARE"));
        int exclusiveWorkspaceStart = xml.indexOf("id=\"lockWorkspaceInOrg\"");
        int exclusiveWorkspaceEnd = xml.indexOf("</select>", exclusiveWorkspaceStart);
        String exclusiveWorkspaceLock =
            xml.substring(exclusiveWorkspaceStart, exclusiveWorkspaceEnd);
        assertFalse(exclusiveWorkspaceLock.contains("JOIN organization"));
        assertFalse(exclusiveWorkspaceLock.contains("#{orgId}"));
        assertTrue(exclusiveWorkspaceLock.contains("w.org_id AS orgId"));
        assertTrue(xml.contains("id=\"lockOrgAdminMembershipForUpdate\""));
        assertTrue(xml.contains("FROM org_member"));
        assertTrue(xml.contains("org_role IN ('admin', 'owner')"));
        int membershipStart = xml.indexOf("id=\"lockOrgAdminMembershipForUpdate\"");
        int membershipEnd = xml.indexOf("</select>", membershipStart);
        String membershipLock = xml.substring(membershipStart, membershipEnd);
        assertFalse(membershipLock.contains("JOIN organization"));
        int identityPageStart =
            xml.indexOf("id=\"findFederatedIdentityBatch\"");
        int identityPageEnd = xml.indexOf("</select>", identityPageStart);
        String identityPage = xml.substring(identityPageStart, identityPageEnd);
        assertTrue(identityPage.contains("SELECT id, user_id"));
        assertTrue(identityPage.contains("ORDER BY id"));
        int identityDeleteStart = xml.indexOf("id=\"deleteFederatedIdentityBatch\"");
        int identityDeleteEnd = xml.indexOf("</delete>", identityDeleteStart);
        String identityDelete = xml.substring(identityDeleteStart, identityDeleteEnd);
        assertTrue(identityDelete.contains("id IN"));
        assertTrue(identityDelete.contains("collection=\"identityIds\""));
        assertTrue(xml.contains("id=\"clearSubjectRequestWorkspaceLinks\""));
        assertFalse(xml.contains("countRecentOperationLeasesInOrg"));
        assertFalse(xml.contains("deleteStaleOperationLeases"));
        assertFalse(xml.contains("created_at &lt;"));
    }

    @Test
    void ssoChallengeRevalidationTakesTheOperationRowLock() throws Exception {
        String resource = "mappers/SsoLinkChallengeMapper.xml";
        String xml;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        int lockStart = xml.indexOf("id=\"lockByTokenHash\"");
        int lockEnd = xml.indexOf("</select>", lockStart);
        String challengeLock = xml.substring(lockStart, lockEnd);
        assertTrue(challengeLock.contains("consumed_at IS NULL"));
        assertTrue(challengeLock.contains("expires_at > UTC_TIMESTAMP()"));
        assertTrue(challengeLock.contains("FOR UPDATE"));
    }
}
