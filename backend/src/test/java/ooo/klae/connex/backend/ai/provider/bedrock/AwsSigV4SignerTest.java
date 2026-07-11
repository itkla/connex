package ooo.klae.connex.backend.ai.provider.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.provider.AiCredentials;

class AwsSigV4SignerTest {
    private static final AiCredentials AWS_EXAMPLE_CREDENTIALS = AiCredentials.of(Map.of(
            "accessKeyId", "AKIDEXAMPLE",
            "secretAccessKey", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"));

    @Test
    void awsPublishedIamVector_matchesCanonicalRequestStringToSignAndSignature() {
        AwsSigV4Signer.SignedRequest signed = AwsSigV4Signer.signForService(
                "GET",
                "iam.amazonaws.com",
                "/",
                "Action=ListUsers&Version=2010-05-08",
                "application/x-www-form-urlencoded; charset=utf-8",
                new byte[0],
                "us-east-1",
                "iam",
                AWS_EXAMPLE_CREDENTIALS,
                Instant.parse("2015-08-30T12:36:00Z"));

        String expectedCanonicalRequest = String.join("\n",
                "GET",
                "/",
                "Action=ListUsers&Version=2010-05-08",
                "content-type:application/x-www-form-urlencoded; charset=utf-8\n"
                        + "host:iam.amazonaws.com\n"
                        + "x-amz-date:20150830T123600Z\n",
                "content-type;host;x-amz-date",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        String expectedStringToSign = String.join("\n",
                "AWS4-HMAC-SHA256",
                "20150830T123600Z",
                "20150830/us-east-1/iam/aws4_request",
                "f536975d06c0309214f805bb90ccff089219ecd68b2577efef23edd43b7e1a59");

        assertEquals(expectedCanonicalRequest, signed.canonicalRequest());
        assertEquals(expectedStringToSign, signed.stringToSign());
        assertEquals("5d672d79c15b13162d9279b0855cfba6789a8edb4c82c400e06b5924a6f2b5d7",
                signed.signature());
        assertEquals("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/iam/aws4_request, "
                + "SignedHeaders=content-type;host;x-amz-date, "
                + "Signature=5d672d79c15b13162d9279b0855cfba6789a8edb4c82c400e06b5924a6f2b5d7",
                signed.authorization());
    }

    @Test
    void bedrockPathWithColon_isDoubleEncodedCanonicallyAndSingleEncodedOnWire() {
        AwsSigV4Signer.SignedRequest signed = AwsSigV4Signer.sign(
                "POST",
                "bedrock-runtime.us-east-1.amazonaws.com",
                "/model/anthropic.claude-3-sonnet-20240229-v1:0/invoke",
                "",
                "{}".getBytes(StandardCharsets.UTF_8),
                "us-east-1",
                AWS_EXAMPLE_CREDENTIALS,
                Instant.parse("2026-07-10T01:02:03Z"));

        assertEquals("/model/anthropic.claude-3-sonnet-20240229-v1%3A0/invoke",
                signed.encodedPath());
        assertTrue(signed.canonicalRequest().startsWith("POST\n"
                + "/model/anthropic.claude-3-sonnet-20240229-v1%253A0/invoke\n\n"));
        assertTrue(signed.authorization().contains("Credential=AKIDEXAMPLE/20260710/us-east-1/bedrock/aws4_request"));
    }

    @Test
    void sessionToken_isIncludedInCanonicalAndSignedHeaders() {
        AiCredentials temporaryCredentials = AiCredentials.of(Map.of(
                "accessKeyId", "AKIDEXAMPLE",
                "secretAccessKey", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
                "sessionToken", "SESSION_TOKEN"));

        AwsSigV4Signer.SignedRequest signed = AwsSigV4Signer.sign(
                "POST",
                "bedrock-runtime.us-east-1.amazonaws.com",
                "/model/anthropic.claude-3-sonnet-20240229-v1:0/invoke",
                "",
                "{}".getBytes(StandardCharsets.UTF_8),
                "us-east-1",
                temporaryCredentials,
                Instant.parse("2026-07-10T01:02:03Z"));

        assertEquals("SESSION_TOKEN", signed.securityToken());
        assertEquals("content-type;host;x-amz-date;x-amz-security-token", signed.signedHeaders());
        assertTrue(signed.canonicalRequest().contains("x-amz-security-token:SESSION_TOKEN\n"));
        assertTrue(signed.authorization().contains("SignedHeaders=content-type;host;x-amz-date;x-amz-security-token"));
        assertEquals("SignedRequest[redacted]", signed.toString());
    }
}
