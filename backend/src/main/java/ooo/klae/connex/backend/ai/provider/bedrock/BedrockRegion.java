package ooo.klae.connex.backend.ai.provider.bedrock;

import java.util.Arrays;
import java.util.Locale;

import ooo.klae.connex.backend.ai.provider.AiProviderException;

/**
 * Closed set of Bedrock runtime regions supported by Connex. Hosts are derived from the enum
 * value and are never accepted from tenant input.
 */
public enum BedrockRegion {
    US_EAST_1("us-east-1"),
    US_WEST_2("us-west-2"),
    AP_NORTHEAST_1("ap-northeast-1"),
    AP_SOUTHEAST_1("ap-southeast-1"),
    AP_SOUTHEAST_2("ap-southeast-2"),
    EU_CENTRAL_1("eu-central-1"),
    EU_WEST_1("eu-west-1"),
    EU_WEST_3("eu-west-3");

    private static final String HOST_PREFIX = "bedrock-runtime.";
    private static final String HOST_SUFFIX = ".amazonaws.com";

    private final String regionCode;
    private final String host;

    BedrockRegion(String regionCode) {
        this.regionCode = regionCode;
        this.host = HOST_PREFIX + regionCode + HOST_SUFFIX;
    }

    /**
     * Resolves a configured region code to the supported enum value.
     * @param code the tenant-configured region code
     * @return the supported Bedrock region
     * @throws AiProviderException when the region is unknown
     */
    public static BedrockRegion fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new AiProviderException("Unsupported Bedrock region");
        }
        String normalized = code.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(region -> region.regionCode.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new AiProviderException("Unsupported Bedrock region"));
    }

    public String regionCode() {
        return regionCode;
    }

    public String host() {
        return host;
    }
}
