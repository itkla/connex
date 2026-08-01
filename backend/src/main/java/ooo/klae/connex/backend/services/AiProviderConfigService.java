package ooo.klae.connex.backend.services;

import java.net.URI;
import java.security.KeyFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.AiGenerationProfile;
import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.AiProviderReadiness;
import ooo.klae.connex.backend.ai.AiProviderSecretCipher;
import ooo.klae.connex.backend.ai.egress.AiEndpointAddressValidator;
import ooo.klae.connex.backend.ai.provider.AiCredentials;
import ooo.klae.connex.backend.ai.provider.AiImageInputSupport;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.ai.provider.ResolvedAiProvider;
import ooo.klae.connex.backend.beans.AiProviderConfig;
import ooo.klae.connex.backend.dto.AiProviderConfigDto;
import ooo.klae.connex.backend.dto.AiProviderConfigRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.AiProviderConfigMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Org-administrator management of an organization's BYOP AI provider settings.
 * The request is addressed by an acting workspace, but authorization is
 * organization-scoped through {@code OrgMemberService}. Provider credentials are
 * stored as one envelope-encrypted JSON bundle and never returned to clients.
 */
@Service
@RequiredArgsConstructor
public class AiProviderConfigService implements AiProviderReadiness {
    private static final String PROVIDER_BEDROCK = "bedrock";
    private static final String PROVIDER_AZURE_OPENAI = "azure_openai";
    private static final String PROVIDER_VERTEX = "vertex";
    private static final String PROVIDER_OPENAI_COMPATIBLE = "openai_compatible";
    private static final String GOOGLE_TOKEN_URI = "https://oauth2.googleapis.com/token";
    private static final String PRIVATE_KEY_BEGIN = "-----BEGIN PRIVATE KEY-----";
    private static final String PRIVATE_KEY_END = "-----END PRIVATE KEY-----";
    private static final Set<String> SUPPORTED_PROVIDERS = Set.of(
            PROVIDER_BEDROCK,
            PROVIDER_AZURE_OPENAI,
            PROVIDER_VERTEX,
            PROVIDER_OPENAI_COMPATIBLE);
    private static final Set<String> ADAPTER_SUPPORTED_PROVIDERS = Set.of(
            PROVIDER_BEDROCK,
            PROVIDER_AZURE_OPENAI,
            PROVIDER_VERTEX,
            PROVIDER_OPENAI_COMPATIBLE);
    private static final Pattern BEDROCK_MODEL_ID = Pattern.compile(
            "^(?!.*\\.\\.)([a-z]{2,4}\\.)?[A-Za-z0-9]([A-Za-z0-9._-]{0,80})?(:[A-Za-z0-9.\\-]{1,20})?$");
    private static final Pattern ACCESS_KEY_ID = Pattern.compile("^[A-Za-z0-9]{8,128}$");
    private static final Pattern SECRET_ACCESS_KEY = Pattern.compile("^[A-Za-z0-9+/=]{1,255}$");
    private static final Pattern SESSION_TOKEN = Pattern.compile("^[A-Za-z0-9+/=._\\-]{1,4096}$");
    private static final Pattern AZURE_DEPLOYMENT = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");
    private static final Pattern AZURE_API_VERSION = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}(-preview)?$");
    private static final Pattern AZURE_API_KEY = Pattern.compile("^[A-Za-z0-9_\\-]{8,256}$");
    private static final Pattern VERTEX_PROJECT_ID = Pattern.compile("^[a-z][a-z0-9-]{4,28}[a-z0-9]$");
    private static final Pattern VERTEX_REGION = Pattern.compile("^[a-z]+-[a-z]+[0-9]{1,2}$");
    private static final Pattern VERTEX_MODEL_ID = Pattern.compile("^[a-z0-9._@\\-]{1,128}$");
    private static final Pattern GENERIC_API_KEY = Pattern.compile("^[\\x21-\\x7e]{1,512}$");
    private static final Set<String> BEDROCK_REGIONS = Set.of(
            "us-east-1",
            "us-west-2",
            "ap-northeast-1",
            "ap-southeast-1",
            "ap-southeast-2",
            "eu-central-1",
            "eu-west-1",
            "eu-west-3");
    private static final TypeReference<Map<String, String>> CREDENTIAL_MAP_TYPE = new TypeReference<>() {
    };

    private final AiProperties aiProperties;
    private final AiProviderConfigMapper aiProviderConfigMapper;
    private final OrganizationMapper organizationMapper;
    private final UserMapper userMapper;
    private final WorkspaceMapper workspaceMapper;
    private final OrgMemberService orgMemberService;
    private final AiProviderSecretCipher aiProviderSecretCipher;
    private final AiEndpointAddressValidator aiEndpointAddressValidator;
    private final AuditService auditService;
    private final SessionSecurityService sessionSecurityService;
    private final ObjectMapper objectMapper;

    /**
     * Returns the AI provider settings for the acting workspace's organization.
     * @param workspaceId the acting workspace
     * @param actorId the requesting user
     * @return the masked provider settings view
     */
    public AiProviderConfigDto getForWorkspace(int workspaceId, int actorId) {
        int orgId = requireAdministrableOrg(workspaceId, actorId);
        return AiProviderConfigDto.from(aiProviderConfigMapper.findByOrg(orgId));
    }

    /**
     * Creates or updates the AI provider settings for the acting workspace's
     * organization. Blank credentials preserve the stored credential reference
     * when the provider id is unchanged.
     * @param workspaceId the acting workspace
     * @param actorId the requesting user
     * @param request the submitted provider settings
     * @return the saved masked provider settings view
     */
    @Transactional
    public AiProviderConfigDto save(int workspaceId, int actorId, AiProviderConfigRequest request) {
        int orgId = requireAdministrableOrg(workspaceId, actorId);
        sessionSecurityService.requireRecentAuthentication(actorId);
        if (request == null) {
            throw new BadRequestException("AI provider configuration is required");
        }

        String provider = resolveProvider(request.getProvider());
        AiProviderConfig existing = lockCurrentConfig(orgId, actorId);
        boolean sameProvider = existing != null && provider.equals(existing.getProvider());
        AiProviderConfig config = new AiProviderConfig();
        config.setOrgId(orgId);
        config.setProvider(provider);
        config.setCredentialRef(sameProvider ? existing.getCredentialRef() : null);
        config.setCredentialLast4(sameProvider ? existing.getCredentialLast4() : null);

        String credentialJson = switch (provider) {
            case PROVIDER_BEDROCK -> validateAndPopulateBedrock(request, config);
            case PROVIDER_AZURE_OPENAI -> validateAndPopulateAzureOpenAi(request, config);
            case PROVIDER_VERTEX -> validateAndPopulateVertex(request, config);
            case PROVIDER_OPENAI_COMPATIBLE -> validateAndPopulateOpenAiCompatible(request, config);
            default -> throw new BadRequestException("Unsupported AI provider");
        };
        if (credentialJson == null && !isBlank(config.getCredentialRef())
                && !sameCredentialScope(existing, config)) {
            config.setCredentialRef(null);
            config.setCredentialLast4(null);
        }
        boolean hasCredential = credentialJson != null || !isBlank(config.getCredentialRef());
        if (request.isEnabled() && !request.isNoTrainingAttested()) {
            throw new BadRequestException("A no-training/no-retention attestation is required before enabling AI");
        }
        if (request.isEnabled() && requiresCredential(provider) && !hasCredential) {
            throw new BadRequestException("Stored provider credentials are required before enabling AI");
        }
        if (credentialJson != null) {
            config.setCredentialRef(aiProviderSecretCipher.encryptCredential(orgId, credentialJson));
        }
        config.setNoTrainingAttested(request.isNoTrainingAttested());
        config.setAttestedAt(resolveAttestedAt(existing, request.isNoTrainingAttested()));
        config.setEnabled(request.isEnabled());

        aiProviderConfigMapper.upsert(config);
        deleteReplacedSecretReference(existing, config);
        auditService.record("org.ai_provider.save", "organization", orgId, provider,
                "Updated AI provider configuration", null);
        return getForWorkspace(workspaceId, actorId);
    }

    /**
     * Removes the AI provider settings and deletes the stored credential reference.
     * @param workspaceId the acting workspace
     * @param actorId the requesting user
     */
    @Transactional
    public void revoke(int workspaceId, int actorId) {
        int orgId = requireAdministrableOrg(workspaceId, actorId);
        sessionSecurityService.requireRecentAuthentication(actorId);
        AiProviderConfig existing = lockCurrentConfig(orgId, actorId);
        aiProviderConfigMapper.deleteByOrg(orgId);
        if (existing != null && !isBlank(existing.getCredentialRef())) {
            aiProviderSecretCipher.deleteCredentialReference(orgId, existing.getCredentialRef());
        }
        auditService.record("org.ai_provider.revoke", "organization", orgId, null,
                "Revoked AI provider configuration", null);
    }

    @Override
    public boolean isReadyForOrg(int orgId) {
        AiProviderConfig config = aiProviderConfigMapper.findByOrg(orgId);
        return isReady(config);
    }

    @Override
    public boolean isImageInputReadyForOrg(int orgId) {
        AiProviderConfig config = aiProviderConfigMapper.findByOrg(orgId);
        return isReady(config) && supportsImageInput(config);
    }

    /**
     * Reads the credential-free provider generation profile used for cache hashing. This path does
     * not lock user or organization rows and never decrypts credentials. A configuration change
     * between this read and leader invocation can cache one output under the earlier profile hash;
     * the next request reads the new profile and self-corrects with a cache miss.
     * @param orgId organization whose provider profile is required
     * @param maxTokens feature output token cap
     * @param temperature feature sampling temperature
     * @return credential-free generation profile
     */
    public AiGenerationProfile profileForOrg(int orgId, int maxTokens, double temperature) {
        AiProviderConfig config = aiProviderConfigMapper.findByOrg(orgId);
        if (!isReady(config)) {
            throw new ForbiddenException("AI provider is not available for this organization");
        }
        return new AiGenerationProfile(
                config.getProvider(), config.getRegion(), config.getModelId(), config.getEndpoint(),
                config.getDeployment(), config.getApiVersion(), config.getProjectId(),
                maxTokens, temperature);
    }

    /**
     * Resolves the configured organization provider and decrypts credentials for provider use.
     * @param orgId the organization
     * @param actorId the authenticated user resolving the provider
     * @return resolved provider configuration with decrypted credentials
     */
    @Transactional
    public ResolvedAiProvider resolveForOrg(int orgId, int actorId) {
        if (userMapper.lockByIdForShare(actorId) == null
                || organizationMapper.lockByIdForShare(orgId) == null) {
            throw new ForbiddenException("AI provider is not available for this organization");
        }
        AiProviderConfig config = aiProviderConfigMapper.findByOrg(orgId);
        if (!isReady(config)) {
            throw new ForbiddenException("AI provider is not available for this organization");
        }
        return new ResolvedAiProvider(
                config.getProvider(),
                config.getRegion(),
                config.getModelId(),
                config.getEndpoint(),
                config.getApiVersion(),
                config.getDeployment(),
                config.getProjectId(),
                effectiveAllowInternalEndpoint(config),
                supportsImageInput(config),
                decryptCredentials(orgId, config.getCredentialRef()));
    }

    private boolean effectiveAllowInternalEndpoint(AiProviderConfig config) {
        return config.isAllowInternalEndpoint() && aiProperties.isAllowInternalEndpoints();
    }

    private boolean isReady(AiProviderConfig config) {
        return config != null
                && config.isEnabled()
                && config.isNoTrainingAttested()
                && !isBlank(config.getProvider())
                && ADAPTER_SUPPORTED_PROVIDERS.contains(config.getProvider())
                && isProviderConfigurationComplete(config)
                && (!requiresCredential(config.getProvider()) || !isBlank(config.getCredentialRef()))
                && aiProviderSecretCipher.isAvailable();
    }

    private boolean isProviderConfigurationComplete(AiProviderConfig config) {
        return switch (config.getProvider()) {
            case PROVIDER_BEDROCK -> BEDROCK_REGIONS.contains(config.getRegion())
                    && isSupportedBedrockClaudeModelId(config.getModelId())
                    && isBlank(config.getEndpoint())
                    && isBlank(config.getApiVersion())
                    && isBlank(config.getDeployment())
                    && isBlank(config.getProjectId());
            case PROVIDER_AZURE_OPENAI -> isValidAzureEndpoint(config.getEndpoint())
                    && matches(AZURE_DEPLOYMENT, config.getDeployment())
                    && matches(AZURE_API_VERSION, config.getApiVersion())
                    && hasBoundedText(config.getModelId(), 128);
            case PROVIDER_VERTEX -> matches(VERTEX_PROJECT_ID, config.getProjectId())
                    && matches(VERTEX_REGION, config.getRegion())
                    && isSupportedVertexModelId(config.getModelId())
                    && isBlank(config.getEndpoint());
            case PROVIDER_OPENAI_COMPATIBLE -> isValidGenericEndpointShape(config.getEndpoint(),
                    effectiveAllowInternalEndpoint(config))
                    && hasBoundedText(config.getModelId(), 128);
            default -> false;
        };
    }

    private static boolean supportsImageInput(AiProviderConfig config) {
        return config != null && AiImageInputSupport.supports(
                config.getProvider(), config.getModelId(), config.getRegion());
    }

    private AiCredentials decryptCredentials(int orgId, String credentialRef) {
        if (isBlank(credentialRef)) {
            return AiCredentials.of(Map.of());
        }
        try {
            String bundleJson = aiProviderSecretCipher.decryptCredential(orgId, credentialRef);
            if (isBlank(bundleJson)) {
                throw new AiProviderException("AI provider credentials are invalid");
            }
            Map<String, String> bundle = objectMapper.readValue(bundleJson, CREDENTIAL_MAP_TYPE);
            if (bundle == null) {
                throw new AiProviderException("AI provider credentials are invalid");
            }
            return AiCredentials.of(bundle);
        } catch (AiProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AiProviderException("AI provider credentials are invalid");
        }
    }

    private String validateAndPopulateBedrock(AiProviderConfigRequest request, AiProviderConfig config) {
        requireBlank(request.getEndpoint(), "Bedrock endpoint must be blank");
        requireBlank(request.getApiVersion(), "Bedrock API version must be blank");
        requireBlank(request.getDeployment(), "Bedrock deployment must be blank");
        requireBlank(request.getProjectId(), "Bedrock project id must be blank");
        config.setRegion(resolveBedrockRegion(request.getRegion()));
        config.setModelId(resolveBedrockClaudeModelId(request.getModelId()));
        config.setAllowInternalEndpoint(false);
        if (isBlank(request.getSecretAccessKey())) {
            return null;
        }
        String accessKeyId = request.getAccessKeyId();
        String secretAccessKey = request.getSecretAccessKey();
        String sessionToken = trimToNull(request.getSessionToken());
        requireValidAwsCredentials(accessKeyId, secretAccessKey, sessionToken);
        config.setCredentialLast4(last4(secretAccessKey));
        Map<String, String> bundle = new LinkedHashMap<>();
        bundle.put("accessKeyId", accessKeyId);
        bundle.put("secretAccessKey", secretAccessKey);
        if (sessionToken != null) {
            bundle.put("sessionToken", sessionToken);
        }
        return credentialBundleJson(bundle);
    }

    private String validateAndPopulateAzureOpenAi(AiProviderConfigRequest request, AiProviderConfig config) {
        config.setEndpoint(resolveAzureEndpoint(request.getEndpoint()));
        config.setDeployment(resolveAzureDeployment(request.getDeployment()));
        config.setApiVersion(resolvePatternValue(request.getApiVersion(), AZURE_API_VERSION,
                "Invalid Azure OpenAI API version"));
        config.setModelId(resolveRequiredText(request.getModelId(), 128,
                "An Azure OpenAI model id is required"));
        config.setAllowInternalEndpoint(false);
        if (isBlank(request.getApiKey())) {
            return null;
        }
        String apiKey = request.getApiKey();
        if (!AZURE_API_KEY.matcher(apiKey).matches()) {
            throw new BadRequestException("Invalid Azure OpenAI API key");
        }
        config.setCredentialLast4(last4(apiKey));
        return credentialBundleJson(Map.of("apiKey", apiKey));
    }

    private String validateAndPopulateVertex(AiProviderConfigRequest request, AiProviderConfig config) {
        requireBlank(request.getEndpoint(), "Vertex endpoint must be blank");
        config.setProjectId(resolvePatternValue(request.getProjectId(), VERTEX_PROJECT_ID,
                "Invalid Vertex project id"));
        config.setRegion(resolvePatternValue(request.getRegion(), VERTEX_REGION,
                "Invalid Vertex region"));
        String modelId = resolvePatternValue(request.getModelId(), VERTEX_MODEL_ID,
                "Invalid Vertex model id");
        if (!isSupportedVertexModelId(modelId)) {
            throw new BadRequestException("Invalid Vertex model id");
        }
        config.setModelId(modelId);
        config.setAllowInternalEndpoint(false);
        if (isBlank(request.getServiceAccountJson())) {
            return null;
        }
        String serviceAccountJson = request.getServiceAccountJson().trim();
        if (serviceAccountJson.length() > 8192) {
            throw new BadRequestException("Invalid Vertex service account credential");
        }
        String clientEmail = validateVertexServiceAccount(serviceAccountJson);
        config.setCredentialLast4(last4(clientEmail));
        return credentialBundleJson(Map.of("serviceAccountJson", serviceAccountJson));
    }

    private String validateAndPopulateOpenAiCompatible(AiProviderConfigRequest request, AiProviderConfig config) {
        boolean allowInternal = request.isAllowInternalEndpoint();
        if (allowInternal && !aiProperties.isAllowInternalEndpoints()) {
            throw new BadRequestException("Private AI endpoints are disabled on this instance");
        }
        config.setEndpoint(resolveGenericEndpoint(request.getEndpoint(), allowInternal));
        config.setModelId(resolveRequiredText(request.getModelId(), 128,
                "An OpenAI-compatible model id is required"));
        config.setAllowInternalEndpoint(allowInternal);
        if (isBlank(request.getApiKey())) {
            return null;
        }
        String apiKey = request.getApiKey();
        if (!GENERIC_API_KEY.matcher(apiKey).matches()) {
            throw new BadRequestException("Invalid OpenAI-compatible API key");
        }
        config.setCredentialLast4(last4(apiKey));
        return credentialBundleJson(Map.of("apiKey", apiKey));
    }

    private String validateVertexServiceAccount(String serviceAccountJson) {
        try {
            JsonNode root = objectMapper.readTree(serviceAccountJson);
            if (root == null || !root.isObject()
                    || !root.path("type").isString()
                    || !"service_account".equals(root.path("type").asString(null))
                    || !root.path("client_email").isString()
                    || !root.path("private_key").isString()
                    || !root.path("token_uri").isString()) {
                throw new BadRequestException("Invalid Vertex service account credential");
            }
            String clientEmail = root.path("client_email").asString(null);
            String privateKey = root.path("private_key").asString(null);
            String tokenUri = root.path("token_uri").asString(null);
            if (isBlank(clientEmail) || isBlank(privateKey)
                    || tokenUri == null || !GOOGLE_TOKEN_URI.equals(tokenUri.trim())) {
                throw new BadRequestException("Invalid Vertex service account credential");
            }
            requireParsableRsaPrivateKey(privateKey);
            return clientEmail.trim();
        } catch (BadRequestException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BadRequestException("Invalid Vertex service account credential");
        }
    }

    private int requireAdministrableOrg(int workspaceId, int actorId) {
        Integer orgId = workspaceMapper.getOrgId(workspaceId);
        if (orgId == null) {
            throw new ForbiddenException("Requires an organization administrator role");
        }
        orgMemberService.requireOrgAdmin(orgId, actorId);
        return orgId;
    }

    private AiProviderConfig lockCurrentConfig(int orgId, int actorId) {
        if (userMapper.lockByIdForShare(actorId) == null) {
            throw new ForbiddenException("Requires an organization administrator role");
        }
        if (organizationMapper.lockById(orgId) == null) {
            throw new ForbiddenException("Requires an organization administrator role");
        }
        orgMemberService.requireOrgAdminForUpdate(orgId, actorId);
        return aiProviderConfigMapper.findByOrgForUpdate(orgId);
    }

    private static String resolveProvider(String requested) {
        if (isBlank(requested)) {
            throw new BadRequestException("AI provider is required");
        }
        String provider = requested.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_PROVIDERS.contains(provider)) {
            throw new BadRequestException("Unsupported AI provider");
        }
        return provider;
    }

    private static String resolveBedrockRegion(String requested) {
        if (isBlank(requested)) {
            throw new BadRequestException("A Bedrock region is required");
        }
        String region = requested.trim().toLowerCase(Locale.ROOT);
        if (!BEDROCK_REGIONS.contains(region)) {
            throw new BadRequestException("Unsupported Bedrock region");
        }
        return region;
    }

    private static String resolveBedrockClaudeModelId(String requested) {
        if (isBlank(requested)) {
            throw new BadRequestException("A Bedrock Claude model id is required");
        }
        String modelId = requested.trim();
        if (!isSupportedBedrockClaudeModelId(modelId)) {
            throw new BadRequestException(modelId.toLowerCase(Locale.ROOT).contains("claude")
                    ? "Invalid Bedrock model id"
                    : "The Bedrock provider supports Claude models only");
        }
        return modelId;
    }

    private static boolean isSupportedBedrockClaudeModelId(String modelId) {
        return !isBlank(modelId)
                && BEDROCK_MODEL_ID.matcher(modelId).matches()
                && modelId.toLowerCase(Locale.ROOT).contains("claude");
    }

    private static boolean isSupportedVertexModelId(String modelId) {
        return matches(VERTEX_MODEL_ID, modelId) && !modelId.contains("..") && isVertexModelFamily(modelId);
    }

    private static boolean isVertexModelFamily(String modelId) {
        return modelId.startsWith("gemini") || modelId.startsWith("claude");
    }

    private static void requireParsableRsaPrivateKey(String privateKeyPem) {
        String normalized = privateKeyPem.trim();
        if (!normalized.startsWith(PRIVATE_KEY_BEGIN) || !normalized.endsWith(PRIVATE_KEY_END)) {
            throw new BadRequestException("Invalid Vertex service account credential");
        }
        String encoded = normalized
                .substring(PRIVATE_KEY_BEGIN.length(), normalized.length() - PRIVATE_KEY_END.length())
                .replaceAll("\\s", "");
        if (encoded.isEmpty()) {
            throw new BadRequestException("Invalid Vertex service account credential");
        }
        try {
            byte[] keyBytes = Base64.getDecoder().decode(encoded);
            KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        } catch (Exception exception) {
            throw new BadRequestException("Invalid Vertex service account credential");
        }
    }

    private static String resolveAzureDeployment(String requested) {
        String value = resolvePatternValue(requested, AZURE_DEPLOYMENT, "Invalid Azure OpenAI deployment");
        if (value.equals(".") || value.contains("..")) {
            throw new BadRequestException("Invalid Azure OpenAI deployment");
        }
        return value;
    }

    private static String resolveAzureEndpoint(String requested) {
        String endpoint = resolveRequiredText(requested, 512, "An Azure OpenAI endpoint is required");
        if (!isValidAzureEndpoint(endpoint)) {
            throw new BadRequestException("Invalid Azure OpenAI endpoint");
        }
        return endpoint;
    }

    private String resolveGenericEndpoint(String requested, boolean allowInternal) {
        String endpoint = resolveRequiredText(requested, 512, "An OpenAI-compatible endpoint is required");
        if (!isValidGenericEndpoint(endpoint, allowInternal)) {
            throw new BadRequestException("Invalid OpenAI-compatible endpoint");
        }
        return endpoint;
    }

    private static boolean isValidAzureEndpoint(String endpoint) {
        URI uri = parseAbsoluteEndpoint(endpoint);
        return uri != null
                && "https".equalsIgnoreCase(uri.getScheme())
                && uri.getHost().toLowerCase(Locale.ROOT).endsWith(".openai.azure.com");
    }

    private boolean isValidGenericEndpoint(String endpoint, boolean allowInternal) {
        if (!isValidGenericEndpointShape(endpoint, allowInternal)) {
            return false;
        }
        URI uri = parseAbsoluteEndpoint(endpoint);
        return uri != null && aiEndpointAddressValidator.isFetchable(uri.getHost(), allowInternal);
    }

    private static boolean isValidGenericEndpointShape(String endpoint, boolean allowInternal) {
        URI uri = parseAbsoluteEndpoint(endpoint);
        return uri != null && ("https".equalsIgnoreCase(uri.getScheme())
                || allowInternal && "http".equalsIgnoreCase(uri.getScheme()));
    }

    private static URI parseAbsoluteEndpoint(String endpoint) {
        if (isBlank(endpoint)) {
            return null;
        }
        try {
            URI uri = URI.create(endpoint.trim());
            if (!uri.isAbsolute() || isBlank(uri.getHost()) || uri.getUserInfo() != null
                    || uri.getFragment() != null || uri.getRawQuery() != null) {
                return null;
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String resolvePatternValue(String requested, Pattern pattern, String error) {
        if (isBlank(requested)) {
            throw new BadRequestException(error);
        }
        String value = requested.trim();
        if (!pattern.matcher(value).matches()) {
            throw new BadRequestException(error);
        }
        return value;
    }

    private static String resolveRequiredText(String requested, int maxLength, String error) {
        if (isBlank(requested)) {
            throw new BadRequestException(error);
        }
        String value = requested.trim();
        if (value.length() > maxLength) {
            throw new BadRequestException(error);
        }
        return value;
    }

    private static boolean hasBoundedText(String value, int maxLength) {
        return !isBlank(value) && value.length() <= maxLength;
    }

    private static boolean matches(Pattern pattern, String value) {
        return !isBlank(value) && pattern.matcher(value).matches();
    }

    private static void requireBlank(String value, String error) {
        if (!isBlank(value)) {
            throw new BadRequestException(error);
        }
    }

    private static void requireValidAwsCredentials(String accessKeyId, String secretAccessKey,
            String sessionToken) {
        if (isBlank(accessKeyId) || !ACCESS_KEY_ID.matcher(accessKeyId).matches()
                || isBlank(secretAccessKey) || !SECRET_ACCESS_KEY.matcher(secretAccessKey).matches()
                || sessionToken != null && !SESSION_TOKEN.matcher(sessionToken).matches()) {
            throw new BadRequestException("Invalid provider credentials");
        }
    }

    private String credentialBundleJson(Map<String, String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception exception) {
            throw new BadRequestException("Invalid provider credentials");
        }
    }

    private static boolean sameCredentialScope(AiProviderConfig existing, AiProviderConfig incoming) {
        if (existing == null) {
            return false;
        }
        return switch (incoming.getProvider()) {
            case PROVIDER_BEDROCK, PROVIDER_VERTEX -> true;
            case PROVIDER_AZURE_OPENAI -> Objects.equals(existing.getEndpoint(), incoming.getEndpoint());
            case PROVIDER_OPENAI_COMPATIBLE ->
                    Objects.equals(existing.getEndpoint(), incoming.getEndpoint())
                            && existing.isAllowInternalEndpoint() == incoming.isAllowInternalEndpoint();
            default -> false;
        };
    }

    private static boolean requiresCredential(String provider) {
        return PROVIDER_BEDROCK.equals(provider)
                || PROVIDER_AZURE_OPENAI.equals(provider)
                || PROVIDER_VERTEX.equals(provider);
    }

    private static LocalDateTime resolveAttestedAt(AiProviderConfig existing, boolean requestedAttestation) {
        if (!requestedAttestation) {
            return null;
        }
        if (existing != null && existing.isNoTrainingAttested() && existing.getAttestedAt() != null) {
            return existing.getAttestedAt();
        }
        return LocalDateTime.now();
    }

    private void deleteReplacedSecretReference(AiProviderConfig existing, AiProviderConfig replacement) {
        if (existing == null || isBlank(existing.getCredentialRef())
                || Objects.equals(existing.getCredentialRef(), replacement.getCredentialRef())) {
            return;
        }
        aiProviderSecretCipher.deleteCredentialReference(existing.getOrgId(), existing.getCredentialRef());
    }

    private static String last4(String value) {
        if (value == null || value.length() < 8) {
            return null;
        }
        return value.substring(value.length() - 4);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
