package ooo.klae.connex.backend.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

import ooo.klae.connex.backend.beans.DataSubjectRequest;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

/** Transient equality token for the exact stored DSR representation; never persisted or audited. */
public final class DataSubjectRequestSnapshot {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

    private DataSubjectRequestSnapshot() {}

    /** Hashes the approved, fixed-order snapshot including explicit nulls and stored strings. */
    public static String token(DataSubjectRequest request) {
        ArrayNode value = JSON.createArrayNode();
        value.add(request.getId()).add(request.getOrgId()).add(request.getRequestType())
                .add(request.getStatus()).add(request.getChannel()).add(request.getRequesterName())
                .add(request.getSubjectName()).add(request.getSubjectEmail());
        integer(value, request.getSubjectWorkspaceId());
        integer(value, request.getSubjectPersonId());
        date(value, request.getReceivedAt());
        date(value, request.getIdentityVerifiedAt());
        date(value, request.getDueAt());
        date(value, request.getRespondedAt());
        date(value, request.getClosedAt());
        value.add(request.getSummary()).add(request.getResolution());
        integer(value, request.getCreatedBy());
        integer(value, request.getUpdatedBy());
        date(value, request.getCreatedAt());
        date(value, request.getUpdatedAt());
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(JSON.writeValueAsString(value).getBytes(StandardCharsets.UTF_8));
            return "dsr1." + Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void integer(ArrayNode value, Integer integer) {
        if (integer == null) {
            value.addNull();
        } else {
            value.add(integer);
        }
    }

    private static void date(ArrayNode value, LocalDateTime date) {
        value.add(date == null ? null : UTC.format(date));
    }
}
