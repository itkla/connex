package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.AiProviderConfig;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;

class AiProviderConfigMapperTest extends AbstractMapperTest {
    private static final LocalDateTime ATTESTED_AT =
            LocalDateTime.of(2026, 8, 13, 12, 0);
    private static final String LEGACY_UPSERT = """
            INSERT INTO ai_provider_config
              (org_id, provider, region, endpoint, api_version, deployment, project_id,
               allow_internal_endpoint, model_id, credential_ref, credential_last4,
               no_training_attested, attested_at, enabled)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              provider = VALUES(provider),
              region = VALUES(region),
              endpoint = VALUES(endpoint),
              api_version = VALUES(api_version),
              deployment = VALUES(deployment),
              project_id = VALUES(project_id),
              allow_internal_endpoint = VALUES(allow_internal_endpoint),
              model_id = VALUES(model_id),
              credential_ref = VALUES(credential_ref),
              credential_last4 = VALUES(credential_last4),
              no_training_attested = VALUES(no_training_attested),
              attested_at = VALUES(attested_at),
              enabled = VALUES(enabled)
            """;

    @Autowired private AiProviderConfigMapper mapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @ParameterizedTest
    @ValueSource(strings = {
        "provider",
        "endpoint",
        "deployment",
        "project_id",
        "region",
        "model_id",
        "allow_internal_endpoint"
    })
    void legacyDestinationChangesInvalidateZdrAttestation(String changedField) {
        User attester = newUser();
        Organization organization = organization();
        AiProviderConfig config = attestedConfig(organization.getId(), attester.getId());
        mapper.upsert(config);
        applyDestinationChange(config, changedField);

        legacyUpsert(config);

        AiProviderConfig stored = mapper.findByOrg(organization.getId());
        assertNotNull(stored);
        assertFalse(stored.isZeroDataRetentionAttested());
        assertNull(stored.getZdrAttestedByUserId());
        assertNull(stored.getZdrAttestedAt());
        assertNull(stored.getZdrAttestationVersion());
    }

    @Test
    void legacyNonDestinationUpdatePreservesZdrAttestation() {
        User attester = newUser();
        Organization organization = organization();
        AiProviderConfig config = attestedConfig(organization.getId(), attester.getId());
        mapper.upsert(config);
        config.setEnabled(false);

        legacyUpsert(config);

        AiProviderConfig stored = mapper.findByOrg(organization.getId());
        assertNotNull(stored);
        assertTrue(stored.isZeroDataRetentionAttested());
        assertEquals(attester.getId(), stored.getZdrAttestedByUserId());
        assertEquals(ATTESTED_AT, stored.getZdrAttestedAt());
        assertEquals(1, stored.getZdrAttestationVersion());
    }

    @Test
    void currentUpsertCanChangeDestinationWithAnExplicitAttestationTransition() {
        User attester = newUser();
        Organization organization = organization();
        AiProviderConfig config = attestedConfig(organization.getId(), attester.getId());
        mapper.upsert(config);
        LocalDateTime renewedAt = ATTESTED_AT.plusHours(1);
        config.setModelId("gpt-5.3");
        config.setZdrAttestedAt(renewedAt);
        config.setZdrAttestationVersion(2);

        mapper.upsert(config);

        AiProviderConfig stored = mapper.findByOrg(organization.getId());
        assertNotNull(stored);
        assertEquals("gpt-5.3", stored.getModelId());
        assertTrue(stored.isZeroDataRetentionAttested());
        assertEquals(attester.getId(), stored.getZdrAttestedByUserId());
        assertEquals(renewedAt, stored.getZdrAttestedAt());
        assertEquals(2, stored.getZdrAttestationVersion());
    }

    private Organization organization() {
        String suffix = unique();
        Organization organization = new Organization();
        organization.setName("AI Provider " + suffix);
        organization.setSlug("ai-provider-" + suffix);
        organizationMapper.insert(organization);
        return organization;
    }

    private static AiProviderConfig attestedConfig(int orgId, int attesterId) {
        AiProviderConfig config = new AiProviderConfig();
        config.setOrgId(orgId);
        config.setProvider("azure_openai");
        config.setRegion("eastus");
        config.setEndpoint("https://connex.openai.azure.com");
        config.setApiVersion("2025-01-01-preview");
        config.setDeployment("contacts-prod");
        config.setProjectId("connex-project");
        config.setModelId("gpt-5.2");
        config.setNoTrainingAttested(true);
        config.setAttestedAt(ATTESTED_AT);
        config.setZeroDataRetentionAttested(true);
        config.setZdrAttestedByUserId(attesterId);
        config.setZdrAttestedAt(ATTESTED_AT);
        config.setZdrAttestationVersion(1);
        config.setEnabled(true);
        return config;
    }

    private static void applyDestinationChange(AiProviderConfig config, String changedField) {
        switch (changedField) {
            case "provider" -> config.setProvider("vertex");
            case "endpoint" -> config.setEndpoint("https://next.openai.azure.com");
            case "deployment" -> config.setDeployment("contacts-next");
            case "project_id" -> config.setProjectId("connex-project-next");
            case "region" -> config.setRegion("westus");
            case "model_id" -> config.setModelId("gpt-5.3");
            case "allow_internal_endpoint" -> config.setAllowInternalEndpoint(true);
            default -> throw new IllegalArgumentException("Unknown destination field");
        }
    }

    private void legacyUpsert(AiProviderConfig config) {
        jdbcTemplate.update(
                LEGACY_UPSERT,
                config.getOrgId(),
                config.getProvider(),
                config.getRegion(),
                config.getEndpoint(),
                config.getApiVersion(),
                config.getDeployment(),
                config.getProjectId(),
                config.isAllowInternalEndpoint(),
                config.getModelId(),
                config.getCredentialRef(),
                config.getCredentialLast4(),
                config.isNoTrainingAttested(),
                config.getAttestedAt(),
                config.isEnabled());
    }
}
