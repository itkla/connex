package ooo.klae.connex.backend.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;
import ooo.klae.connex.backend.beans.Sequence;
import ooo.klae.connex.backend.beans.SequenceVersion;
import ooo.klae.connex.backend.dto.sequence.SequenceStepDto;
import ooo.klae.connex.backend.dto.sequence.SequenceVersionDto;
import ooo.klae.connex.backend.exceptions.SequenceException;
import ooo.klae.connex.backend.mappers.SequenceMapper;
import ooo.klae.connex.backend.mappers.SequenceVersionMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/** Publishes and reads content-addressed immutable sequence versions. */
@Service
@RequiredArgsConstructor
public class SequenceVersionService {
    private static final int MAX_DEFINITION_BYTES = 262_144;

    private final SequenceVersionMapper versionMapper;
    private final SequenceMapper sequenceMapper;
    private final SequenceService sequenceService;
    private final WorkspaceService workspaceService;
    private final AuthService authService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    /** Publishes the current ordered draft as the next immutable version. */
    @Transactional
    @RequirePermission(Permission.SEQUENCE_MANAGE)
    public SequenceVersionDto publish(int sequenceId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = authService.getCurrentUser().getId();
        sequenceService.lockManagePermission(workspaceId, actorId);
        Sequence sequence = sequenceService.requireVisibleForUpdate(workspaceId, sequenceId, actorId);
        if (sequence.getArchivedAt() != null) {
            throw SequenceException.notFound("Sequence not found");
        }
        List<SequenceStepDto> steps = sequenceService.loadStepsForShare(workspaceId, sequenceId);
        if (steps.isEmpty()) {
            throw SequenceException.badRequest(
                "SEQUENCE_DRAFT_EMPTY", "A sequence needs at least one step before publishing");
        }
        String definitionJson = serialize(new SequenceDefinition(1, steps));
        byte[] hash = digest(definitionJson);
        SequenceVersion version = new SequenceVersion();
        version.setWorkspaceId(workspaceId);
        version.setSequenceId(sequenceId);
        version.setVersionNumber(versionMapper.nextVersionNumberForUpdate(workspaceId, sequenceId));
        version.setDefinitionJson(definitionJson);
        version.setDefinitionHash(hash);
        version.setPublishedById(actorId);
        versionMapper.insertVersion(version);
        versionMapper.insertVersionPublisher(workspaceId, version.getId(), actorId);
        sequenceMapper.markPublished(workspaceId, sequenceId, actorId);
        auditService.record(
            "sequence.publish", "sequence", sequenceId, sequence.getName(),
            "Published sequence version", Map.of(
                "version", version.getVersionNumber(),
                "definitionHash", HexFormat.of().formatHex(hash),
                "stepCount", steps.size()));
        return getInternal(workspaceId, sequenceId, version.getVersionNumber());
    }

    /** Lists published immutable versions visible to the current member. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    @RequirePermission(Permission.SEQUENCE_VIEW)
    public List<SequenceVersionDto> list(int sequenceId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = authService.getCurrentUser().getId();
        sequenceService.requireViewPermission(workspaceId, actorId);
        sequenceService.requireVisible(workspaceId, sequenceId, actorId);
        return versionMapper.getVersions(workspaceId, sequenceId).stream()
            .map(this::toDto)
            .toList();
    }

    /** Returns one published immutable version visible to the current member. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    @RequirePermission(Permission.SEQUENCE_VIEW)
    public SequenceVersionDto get(int sequenceId, int versionNumber) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = authService.getCurrentUser().getId();
        sequenceService.requireViewPermission(workspaceId, actorId);
        sequenceService.requireVisible(workspaceId, sequenceId, actorId);
        return getInternal(workspaceId, sequenceId, versionNumber);
    }

    SequenceVersion requireVersion(int workspaceId, int sequenceId, int versionNumber) {
        if (versionNumber < 1) {
            throw SequenceException.notFound("Sequence version not found");
        }
        SequenceVersion version = versionMapper.getVersion(workspaceId, sequenceId, versionNumber);
        if (version == null) {
            throw SequenceException.notFound("Sequence version not found");
        }
        return version;
    }

    List<SequenceStepDto> parseSteps(SequenceVersion version) {
        try {
            SequenceDefinition definition = objectMapper.readValue(
                version.getDefinitionJson(), SequenceDefinition.class);
            if (definition.schemaVersion() != 1 || definition.steps() == null) {
                throw storedDefinitionInvalid();
            }
            return List.copyOf(definition.steps());
        } catch (SequenceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw storedDefinitionInvalid();
        }
    }

    private SequenceVersionDto getInternal(int workspaceId, int sequenceId, int versionNumber) {
        return toDto(requireVersion(workspaceId, sequenceId, versionNumber));
    }

    private SequenceVersionDto toDto(SequenceVersion version) {
        return new SequenceVersionDto(
            version.getVersionNumber(),
            HexFormat.of().formatHex(version.getDefinitionHash()),
            parseSteps(version),
            version.getPublishedById(),
            version.getCreatedAt());
    }

    private String serialize(SequenceDefinition definition) {
        try {
            String json = objectMapper.writeValueAsString(definition);
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_DEFINITION_BYTES) {
                throw SequenceException.badRequest(
                    "SEQUENCE_DEFINITION_TOO_LARGE", "Sequence definition is too large");
            }
            return json;
        } catch (SequenceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw SequenceException.badRequest(
                "SEQUENCE_DEFINITION_INVALID", "Sequence definition is invalid");
        }
    }

    private static byte[] digest(String definitionJson) {
        try {
            return MessageDigest.getInstance("SHA-256")
                .digest(definitionJson.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static SequenceException storedDefinitionInvalid() {
        return new SequenceException(
            org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
            "SEQUENCE_DEFINITION_CORRUPT",
            "Stored sequence definition is invalid");
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SequenceDefinition(int schemaVersion, List<SequenceStepDto> steps) {
        SequenceDefinition {
            steps = steps == null ? null : List.copyOf(steps);
        }
    }
}
