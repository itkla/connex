package ooo.klae.connex.backend.ai.provider.bedrock;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import ooo.klae.connex.backend.ai.provider.AiCredentials;
import ooo.klae.connex.backend.ai.provider.AiProviderException;

/**
 * Pure AWS Signature Version 4 signer for Bedrock runtime requests. The signer performs no I/O,
 * accepts no endpoint override, and implements the SigV4 primitives directly: RFC3986-style path
 * encoding, SHA-256 payload and canonical-request hashing, the AWS4 HMAC-SHA256 key derivation
 * chain, lowercase hexadecimal output, and an Authorization header over the fixed signed headers
 * used by Bedrock JSON POST requests. Bedrock follows the non-S3 SigV4 rule: the canonical URI is
 * double-encoded while the outbound wire path is encoded once.
 */
public final class AwsSigV4Signer {
    static final String ALGORITHM = "AWS4-HMAC-SHA256";
    static final String SERVICE = "bedrock";
    static final String TERMINATOR = "aws4_request";
    static final String CONTENT_TYPE = "application/json";

    private static final DateTimeFormatter AMZ_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private AwsSigV4Signer() {
    }

    /**
     * Signs a Bedrock POST request.
     * @param method HTTP method
     * @param host Bedrock runtime host
     * @param rawPath raw absolute request path
     * @param canonicalQueryString canonical query string, empty for Bedrock InvokeModel
     * @param requestBody request payload bytes
     * @param region AWS region code
     * @param credentials AWS credentials
     * @param timestamp signing timestamp
     * @return header values and canonical material for the signed request
     */
    public static SignedRequest sign(
            String method,
            String host,
            String rawPath,
            String canonicalQueryString,
            byte[] requestBody,
            String region,
            AiCredentials credentials,
            Instant timestamp) {
        return signForService(method, host, rawPath, canonicalQueryString, CONTENT_TYPE, requestBody, region,
                SERVICE, credentials, timestamp);
    }

    static SignedRequest signForService(
            String method,
            String host,
            String rawPath,
            String canonicalQueryString,
            String contentType,
            byte[] requestBody,
            String region,
            String service,
            AiCredentials credentials,
            Instant timestamp) {
        requireText(method, "method");
        requireText(host, "host");
        requireText(region, "region");
        requireText(service, "service");
        requireCredentials(credentials);
        Objects.requireNonNull(timestamp, "timestamp");

        String amzDate = AMZ_DATE_FORMAT.format(timestamp);
        String date = DATE_FORMAT.format(timestamp);
        String encodedPath = encodeCanonicalPath(rawPath);
        String canonicalPath = encodeCanonicalPath(encodedPath);
        String payloadHash = sha256Hex(requestBody == null ? new byte[0] : requestBody);
        Map<String, String> headers = signedHeaders(host, contentType, amzDate, credentials.sessionToken());
        String canonicalHeaders = canonicalHeaders(headers);
        String signedHeaders = signedHeaderNames(headers);
        String query = canonicalQueryString == null ? "" : canonicalQueryString;
        String canonicalRequest = method + "\n"
                + canonicalPath + "\n"
                + query + "\n"
                + canonicalHeaders + "\n"
                + signedHeaders + "\n"
                + payloadHash;
        String credentialScope = date + "/" + region + "/" + service + "/" + TERMINATOR;
        String stringToSign = ALGORITHM + "\n"
                + amzDate + "\n"
                + credentialScope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        String signature = hex(hmac(signingKey(credentials.secretAccessKey(), date, region, service),
                stringToSign.getBytes(StandardCharsets.UTF_8)));
        String authorization = ALGORITHM
                + " Credential=" + credentials.accessKeyId() + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature;
        return new SignedRequest(authorization, amzDate, credentials.sessionToken(), canonicalRequest,
                stringToSign, signature, encodedPath, signedHeaders);
    }

    /**
     * Percent-encodes each path segment according to SigV4 canonical URI rules while preserving
     * slash characters as path separators.
     * @param rawPath raw absolute path
     * @return canonical encoded path
     */
    public static String encodeCanonicalPath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return "/";
        }
        String path = rawPath.startsWith("/") ? rawPath : "/" + rawPath;
        String[] segments = path.split("/", -1);
        StringBuilder encoded = new StringBuilder(path.length());
        for (int index = 0; index < segments.length; index++) {
            if (index > 0) {
                encoded.append('/');
            }
            encoded.append(encodeSegment(segments[index]));
        }
        return encoded.toString();
    }

    private static Map<String, String> signedHeaders(String host, String contentType, String amzDate,
            String sessionToken) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (contentType != null && !contentType.isBlank()) {
            headers.put("content-type", contentType);
        }
        headers.put("host", host);
        headers.put("x-amz-date", amzDate);
        if (sessionToken != null && !sessionToken.isBlank()) {
            headers.put("x-amz-security-token", sessionToken.trim());
        }
        return headers;
    }

    private static String canonicalHeaders(Map<String, String> headers) {
        return headers.entrySet().stream()
                .map(entry -> entry.getKey() + ":" + normalizeHeaderValue(entry.getValue()) + "\n")
                .collect(Collectors.joining());
    }

    private static String signedHeaderNames(Map<String, String> headers) {
        return String.join(";", headers.keySet());
    }

    private static String normalizeHeaderValue(String value) {
        return value.trim().replaceAll("\\s+", " ");
    }

    private static byte[] signingKey(String secretAccessKey, String date, String region, String service) {
        byte[] kDate = hmac(("AWS4" + secretAccessKey).getBytes(StandardCharsets.UTF_8),
                date.getBytes(StandardCharsets.UTF_8));
        byte[] kRegion = hmac(kDate, region.getBytes(StandardCharsets.UTF_8));
        byte[] kService = hmac(kRegion, service.getBytes(StandardCharsets.UTF_8));
        return hmac(kService, TERMINATOR.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmac(byte[] key, byte[] value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return mac.doFinal(value);
        } catch (Exception exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    private static String sha256Hex(byte[] value) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String encodeSegment(String segment) {
        byte[] bytes = segment.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length);
        for (byte raw : bytes) {
            int value = raw & 0xFF;
            if (isUnreserved(value)) {
                encoded.append((char) value);
            } else {
                encoded.append('%');
                encoded.append(Character.toUpperCase(Character.forDigit((value >> 4) & 0xF, 16)));
                encoded.append(Character.toUpperCase(Character.forDigit(value & 0xF, 16)));
            }
        }
        return encoded.toString();
    }

    private static boolean isUnreserved(int value) {
        return value >= 'A' && value <= 'Z'
                || value >= 'a' && value <= 'z'
                || value >= '0' && value <= '9'
                || value == '-' || value == '.' || value == '_' || value == '~';
    }

    private static String hex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xFF;
            chars[index * 2] = HEX[value >>> 4];
            chars[index * 2 + 1] = HEX[value & 0x0F];
        }
        return new String(chars);
    }

    private static void requireCredentials(AiCredentials credentials) {
        Objects.requireNonNull(credentials, "credentials");
        requireText(credentials.accessKeyId(), "accessKeyId");
        requireText(credentials.secretAccessKey(), "secretAccessKey");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new AiProviderException("AWS SigV4 " + name + " is required");
        }
    }

    /**
     * Values to apply to the outbound request after signing, plus canonical material used by
     * deterministic unit tests.
     * @param authorization Authorization header
     * @param amzDate X-Amz-Date header
     * @param securityToken optional X-Amz-Security-Token header
     * @param canonicalRequest canonical request string
     * @param stringToSign SigV4 string-to-sign
     * @param signature lowercase hexadecimal signature
     * @param encodedPath single-encoded outbound wire path
     * @param signedHeaders semicolon-separated signed header names
     */
    public record SignedRequest(
            String authorization,
            String amzDate,
            String securityToken,
            String canonicalRequest,
            String stringToSign,
            String signature,
            String encodedPath,
            String signedHeaders) {

        @Override
        public String toString() {
            return "SignedRequest[redacted]";
        }
    }
}
